package com.agroguide.rtk.device

import com.agroguide.rtk.geo.GnssQuality
import com.agroguide.rtk.geo.JumpFilter
import com.agroguide.rtk.geo.TangentPlane
import com.agroguide.rtk.geo.VehicleState
import com.agroguide.rtk.nmea.FixAggregator
import com.agroguide.rtk.nmea.FixQuality
import com.agroguide.rtk.nmea.GnssFix
import com.agroguide.rtk.nmea.NmeaParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * source events -> parse -> aggregate -> tangent plane (auto-anchored on first
 * fix) -> jump filter -> VehicleState. One instance per source session; the
 * tangent-plane origin is replaced when a job supplies its own.
 */
class PositionPipeline(
    source: PositionSource,
    @Volatile var plane: TangentPlane? = null,
    /**
     * Local UTC wall-clock ms at a given monotonic receive time, used to
     * measure fix latency; null disables the measurement (unit tests).
     */
    private val arrivalWallMs: ((monotonicMs: Long) -> Long)? = null,
) {
    private val aggregator = FixAggregator()
    private val jumpFilter = JumpFilter()
    private val headingHold = com.agroguide.rtk.geo.HeadingHold()
    private val latency = com.agroguide.rtk.geo.LatencyEstimator()
    private var filterPlane: TangentPlane? = null

    /** Measured fix age on arrival (receiver + Bluetooth + Android), 0 if unknown. */
    val latencyMs: Long get() = latency.latencyMs

    val states: Flow<VehicleState> = source.events.transform { ev ->
        val fix: GnssFix = when (ev) {
            is SourceEvent.Nmea -> {
                val sentence = NmeaParser.parse(ev.line) ?: return@transform
                aggregator.onSentence(sentence, ev.monotonicMs) ?: return@transform
            }
            is SourceEvent.DirectFix -> ev.fix
            is SourceEvent.ConnectionState -> return@transform
            is SourceEvent.UbxAckEvent -> return@transform
        }
        if (fix.quality == FixQuality.NO_FIX) return@transform
        val clock = arrivalWallMs
        val fixTod = com.agroguide.rtk.geo.LatencyEstimator.parseNmeaTime(fix.timeUtc)
        if (clock != null && fixTod != null) {
            val arrival = Math.floorMod(clock(fix.monotonicMs), com.agroguide.rtk.geo.LatencyEstimator.DAY_MS)
            latency.onFix(fixTod, arrival)
        }
        val p = plane ?: TangentPlane(fix.latDeg, fix.lonDeg).also { plane = it }
        // A job pinning another frame moves every ENU coordinate at once; that
        // is not a GNSS jump, so the filter must start over in the new frame.
        if (p !== filterPlane) { jumpFilter.reset(); filterPlane = p }
        val enu = p.toEnu(fix.latDeg, fix.lonDeg)
        val speed = fix.speedMps ?: 0.0
        if (!jumpFilter.accept(enu, speed, fix.monotonicMs)) return@transform
        emit(
            VehicleState(
                x = enu.x, y = enu.y, latDeg = fix.latDeg, lonDeg = fix.lonDeg,
                headingDeg = headingHold.update(fix.courseDeg, speed), speedMps = speed,
                quality = fix.quality.toGnssQuality(), satellites = fix.satellites,
                hdop = fix.hdop, correctionAgeS = fix.correctionAgeS,
                fixMonotonicMs = fix.monotonicMs, projected = false,
            )
        )
    }
}

fun FixQuality.toGnssQuality(): GnssQuality = when (this) {
    FixQuality.NO_FIX -> GnssQuality.NO_FIX
    FixQuality.GPS, FixQuality.PPS -> GnssQuality.GPS
    FixQuality.DGPS -> GnssQuality.DGPS
    FixQuality.RTK_FIXED -> GnssQuality.RTK_FIXED
    FixQuality.RTK_FLOAT -> GnssQuality.RTK_FLOAT
    FixQuality.DEAD_RECKONING -> GnssQuality.DEAD_RECKONING
}
