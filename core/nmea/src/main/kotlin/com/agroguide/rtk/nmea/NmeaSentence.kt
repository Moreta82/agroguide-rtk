package com.agroguide.rtk.nmea

/** GGA fix quality per NMEA 0183. */
enum class FixQuality(val code: Int) {
    NO_FIX(0), GPS(1), DGPS(2), PPS(3), RTK_FIXED(4), RTK_FLOAT(5), DEAD_RECKONING(6);
    companion object {
        fun fromCode(code: Int): FixQuality = entries.firstOrNull { it.code == code } ?: NO_FIX
    }
}

sealed interface NmeaSentence {
    /** $--GGA: position, fix quality, sats, HDOP, altitude, correction age. */
    data class Gga(
        val timeUtc: String?,          // "hhmmss.ss" as sent, null if empty
        val latDeg: Double?,           // signed decimal degrees, null if no fix
        val lonDeg: Double?,
        val quality: FixQuality,
        val satellites: Int?,
        val hdop: Double?,
        val altitudeM: Double?,
        val correctionAgeS: Double?,   // field 13, empty when no DGPS/RTK
    ) : NmeaSentence

    /** $--RMC: position, speed over ground, course over ground, date. */
    data class Rmc(
        val timeUtc: String?,
        val valid: Boolean,            // A=valid, V=void
        val latDeg: Double?,
        val lonDeg: Double?,
        val speedMps: Double?,         // converted from knots
        val courseDeg: Double?,        // true course, null when not moving
        val dateDdMmYy: String?,
    ) : NmeaSentence

    /** $--VTG: course and speed over ground. */
    data class Vtg(val courseDeg: Double?, val speedMps: Double?) : NmeaSentence

    /** $--GSV: satellites in view (only total count parsed; per-sat data ignored in v1). */
    data class Gsv(val totalInView: Int?) : NmeaSentence

    /** $--GST: position error statistics (1-sigma, meters). */
    data class Gst(val latErrM: Double?, val lonErrM: Double?, val altErrM: Double?) : NmeaSentence

    data class Unknown(val type: String) : NmeaSentence
}
