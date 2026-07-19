package com.agroguide.rtk.nmea

/**
 * One accepted position fix, aggregated from GGA (authoritative for position/quality)
 * plus the most recent RMC/VTG (speed, course), GSV (sats in view) and GST (sigma).
 * [monotonicMs] is the receive timestamp on the local monotonic clock.
 */
data class GnssFix(
    val latDeg: Double,
    val lonDeg: Double,
    val altitudeM: Double?,
    val quality: FixQuality,
    val satellites: Int?,      // used in solution (GGA field 7)
    val satsInView: Int?,      // from GSV
    val hdop: Double?,
    val correctionAgeS: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val sigmaLatM: Double?,
    val sigmaLonM: Double?,
    val timeUtc: String?,
    val monotonicMs: Long,
)

/**
 * Stateful merger: feed every parsed sentence; a non-null return means
 * "new fix ready" (emitted once per GGA, which paces the stream).
 */
class FixAggregator {
    private var lastRmc: NmeaSentence.Rmc? = null
    private var lastVtg: NmeaSentence.Vtg? = null
    private var lastGsv: NmeaSentence.Gsv? = null
    private var lastGst: NmeaSentence.Gst? = null

    fun onSentence(s: NmeaSentence, monotonicMs: Long): GnssFix? {
        when (s) {
            is NmeaSentence.Rmc -> lastRmc = s
            is NmeaSentence.Vtg -> lastVtg = s
            is NmeaSentence.Gsv -> lastGsv = s
            is NmeaSentence.Gst -> lastGst = s
            is NmeaSentence.Gga -> {
                val lat = s.latDeg ?: return null
                val lon = s.lonDeg ?: return null
                return GnssFix(
                    latDeg = lat, lonDeg = lon, altitudeM = s.altitudeM,
                    quality = s.quality, satellites = s.satellites,
                    satsInView = lastGsv?.totalInView, hdop = s.hdop,
                    correctionAgeS = s.correctionAgeS,
                    speedMps = lastRmc?.speedMps ?: lastVtg?.speedMps,
                    courseDeg = lastRmc?.courseDeg ?: lastVtg?.courseDeg,
                    sigmaLatM = lastGst?.latErrM, sigmaLonM = lastGst?.lonErrM,
                    timeUtc = s.timeUtc, monotonicMs = monotonicMs,
                )
            }
            is NmeaSentence.Unknown -> {}
        }
        return null
    }
}
