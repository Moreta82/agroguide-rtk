package com.agroguide.rtk.geo

/**
 * Measures how old a GNSS fix is when it reaches the app: receiver solution
 * time + UART/Bluetooth transfer + Android stack. The fix carries the UTC time
 * of its measurement epoch (NMEA GGA), so latency = local UTC at arrival −
 * epoch time. At 10 km/h, 150 ms of latency puts the drawn tractor and the
 * paint ~40 cm behind the real implement; projecting forward by this amount
 * removes that.
 *
 * Robust to a phone clock that is not synchronised: samples outside
 * [0, [maxMs]] are discarded, and the estimate is the median of the last
 * [window] accepted samples (0 until [minSamples] exist).
 */
class LatencyEstimator(
    private val window: Int = 25,
    private val minSamples: Int = 5,
    private val maxMs: Long = 600,
) {
    private val samples = ArrayDeque<Long>()

    /** Current estimate in ms, 0 when unknown. */
    @Volatile var latencyMs: Long = 0; private set

    /**
     * @param fixUtcMsOfDay epoch time of the fix, ms since UTC midnight
     * @param arrivalUtcMsOfDay local UTC clock when the fix arrived, ms since UTC midnight
     */
    @Synchronized
    fun onFix(fixUtcMsOfDay: Long, arrivalUtcMsOfDay: Long) {
        var d = arrivalUtcMsOfDay - fixUtcMsOfDay
        // fix just before midnight, arrival just after (or the reverse)
        if (d < -DAY_MS / 2) d += DAY_MS
        if (d > DAY_MS / 2) d -= DAY_MS
        if (d < 0 || d > maxMs) return
        samples.addLast(d)
        while (samples.size > window) samples.removeFirst()
        latencyMs = if (samples.size < minSamples) 0 else samples.sorted()[samples.size / 2]
    }

    @Synchronized
    fun reset() { samples.clear(); latencyMs = 0 }

    companion object {
        const val DAY_MS = 86_400_000L

        /** NMEA "hhmmss.ss" to ms since UTC midnight, or null if malformed. */
        fun parseNmeaTime(t: String?): Long? {
            if (t == null || t.length < 6) return null
            val h = t.substring(0, 2).toIntOrNull() ?: return null
            val m = t.substring(2, 4).toIntOrNull() ?: return null
            val s = t.substring(4).toDoubleOrNull() ?: return null
            if (h > 23 || m > 59 || s >= 61.0) return null
            return h * 3_600_000L + m * 60_000L + Math.round(s * 1000)
        }
    }
}
