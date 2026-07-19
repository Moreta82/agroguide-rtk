package com.agroguide.rtk.nmea.replay

import com.agroguide.rtk.nmea.FixQuality
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic NMEA source for demo mode and tests: drives a point at
 * [speedMps] on [headingDeg] from the start position, emitting GGA+RMC+VTG
 * per epoch. Heading/speed/quality can be changed live (demo UI turns the
 * "tractor"). All formatting is Locale.US — NMEA requires dot decimals.
 */
class SyntheticNmeaGenerator(
    startLatDeg: Double,
    startLonDeg: Double,
    var speedMps: Double = 3.0,
    var headingDeg: Double = 0.0,
    var quality: FixQuality = FixQuality.RTK_FIXED,
    private val epochIntervalS: Double = 1.0,
) {
    private var lat = startLatDeg
    private var lon = startLonDeg
    private var utcS = 12 * 3600.0
    private var emittedFirst = false

    fun next(): List<String> {
        if (emittedFirst) {
            val dNorth = speedMps * epochIntervalS * cos(headingDeg * PI / 180)
            val dEast = speedMps * epochIntervalS * sin(headingDeg * PI / 180)
            lat += dNorth / 111_320.0
            lon += dEast / (111_320.0 * cos(lat * PI / 180))
            utcS += epochIntervalS
        }
        emittedFirst = true
        val time = utcTime()
        val (latV, latH) = nmeaLat(lat)
        val (lonV, lonH) = nmeaLon(lon)
        val speedKn = speedMps / 0.514444
        val gga = cs(
            "\$GNGGA,$time,$latV,$latH,$lonV,$lonH,${quality.code},12,0.60,500.0,M,46.9,M," +
                "${if (quality.code >= 2) "1.0" else ""},0000"
        )
        val rmc = cs(
            "\$GNRMC,$time,A,$latV,$latH,$lonV,$lonH,${fmt("%.2f", speedKn)},${fmt("%.1f", headingDeg)}," +
                "010126,,,R,V"
        )
        val vtg = cs(
            "\$GNVTG,${fmt("%.1f", headingDeg)},T,,M,${fmt("%.2f", speedKn)},N," +
                "${fmt("%.2f", speedKn * 1.852)},K,D"
        )
        return listOf(gga, rmc, vtg)
    }

    private fun fmt(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

    private fun utcTime(): String {
        val h = (utcS / 3600).toInt() % 24
        val m = ((utcS % 3600) / 60).toInt()
        val s = utcS % 60
        return fmt("%02d%02d%05.2f", h, m, s)
    }

    private fun nmeaLat(d: Double): Pair<String, String> {
        val a = abs(d); val deg = a.toInt(); val min = (a - deg) * 60
        return fmt("%02d%08.5f", deg, min) to if (d < 0) "S" else "N"
    }

    private fun nmeaLon(d: Double): Pair<String, String> {
        val a = abs(d); val deg = a.toInt(); val min = (a - deg) * 60
        return fmt("%03d%08.5f", deg, min) to if (d < 0) "W" else "E"
    }

    private fun cs(body: String): String {
        var x = 0; for (c in body.drop(1)) x = x xor c.code
        return body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }
}
