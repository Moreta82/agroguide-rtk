package com.agroguide.rtk.device

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.agroguide.rtk.nmea.FixQuality
import com.agroguide.rtk.nmea.GnssFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Mode 2: standard Android location (GPS provider), which is what external
 * mock providers (GNSS Master etc.) feed. Emits DirectFix — no NMEA parsing.
 * Quality is approximated from accuracy since the framework hides RTK state:
 * <0.1 m -> RTK_FIXED, <0.5 m -> RTK_FLOAT, <2 m -> DGPS, else GPS.
 */
@SuppressLint("MissingPermission")  // caller must hold ACCESS_FINE_LOCATION
class AndroidLocationSource(context: Context, private val intervalMs: Long = 200) : PositionSource {
    override val name = "Android"
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override val events: Flow<SourceEvent> = callbackFlow {
        val listener = LocationListener { loc: Location ->
            trySend(SourceEvent.DirectFix(loc.toGnssFix()))
        }
        trySend(SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.CONNECTED, "gps"))
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toGnssFix(): GnssFix {
        val acc = if (hasAccuracy()) accuracy.toDouble() else Double.MAX_VALUE
        val q = when {
            acc < 0.1 -> FixQuality.RTK_FIXED
            acc < 0.5 -> FixQuality.RTK_FLOAT
            acc < 2.0 -> FixQuality.DGPS
            else -> FixQuality.GPS
        }
        return GnssFix(
            latDeg = latitude, lonDeg = longitude, altitudeM = if (hasAltitude()) altitude else null,
            quality = q, satellites = extras?.getInt("satellites"), satsInView = null,
            hdop = null, correctionAgeS = null,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            courseDeg = if (hasBearing()) bearing.toDouble() else null,
            sigmaLatM = if (hasAccuracy()) acc else null, sigmaLonM = if (hasAccuracy()) acc else null,
            timeUtc = null, monotonicMs = SystemClock.elapsedRealtime(),
        )
    }
}
