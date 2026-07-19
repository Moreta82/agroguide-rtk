package com.agroguide.rtk.device

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.agroguide.rtk.geo.GnssQuality
import com.agroguide.rtk.geo.VehicleState

/**
 * Republishes the RTK position as the system GPS test provider so other apps
 * can consume it. Requires the user to select AgroGuide as the mock location
 * app in developer options — start() returns false otherwise.
 */
class MockLocationPublisher(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (started) return true
        return try {
            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, true, false, false, true, true, true,
                Criteria.POWER_HIGH, Criteria.ACCURACY_FINE,
            )
            lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            started = true
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun publish(state: VehicleState) {
        if (!started) return
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = state.latDeg
            longitude = state.lonDeg
            accuracy = when (state.quality) {
                GnssQuality.RTK_FIXED -> 0.02f
                GnssQuality.RTK_FLOAT -> 0.3f
                GnssQuality.DGPS -> 1.5f
                else -> 4.0f
            }
            speed = state.speedMps.toFloat()
            bearing = state.headingDeg.toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try { lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc) } catch (_: Exception) {}
    }

    fun stop() {
        if (!started) return
        started = false
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
    }
}
