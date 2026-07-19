package com.agroguide.rtk.geo

/** Fix quality mirrored here so :core:geo stays independent of :core:nmea. */
enum class GnssQuality { NO_FIX, GPS, DGPS, RTK_FLOAT, RTK_FIXED, DEAD_RECKONING }

/**
 * The single source of truth consumed by guidance, coverage and UI.
 * Position is ENU meters on the active job's tangent plane; lat/lon kept
 * for display and export only.
 */
data class VehicleState(
    val x: Double, val y: Double,            // ENU antenna position
    val latDeg: Double, val lonDeg: Double,
    val headingDeg: Double,                  // course over ground, true
    val speedMps: Double,
    val quality: GnssQuality,
    val satellites: Int?,
    val hdop: Double?,
    val correctionAgeS: Double?,
    val fixMonotonicMs: Long,                // when the underlying fix arrived
    val projected: Boolean,                  // true if CV-extrapolated, not a raw fix
)
