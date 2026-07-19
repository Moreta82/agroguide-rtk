package com.agroguide.rtk.nmea

/**
 * Parser for the NMEA 0183 sentences emitted by u-blox ZED-F9P.
 * Returns null for anything that fails checksum or basic framing —
 * callers treat null as line noise, never as an error.
 */
object NmeaParser {

    fun parse(line: String): NmeaSentence? {
        val trimmed = line.trim()
        if (trimmed.length < 9 || !trimmed.startsWith("$")) return null
        val star = trimmed.lastIndexOf('*')
        if (star < 0 || star + 3 > trimmed.length) return null
        val body = trimmed.substring(1, star)
        val expected = trimmed.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
        var x = 0; for (c in body) x = x xor c.code
        if (x != expected) return null

        val f = body.split(',')
        val type = f[0].takeIf { it.length >= 5 }?.substring(2) ?: return null
        return try {
            when (type) {
                "GGA" -> parseGga(f)
                "RMC" -> parseRmc(f)
                "VTG" -> parseVtg(f)
                "GSV" -> NmeaSentence.Gsv(f.getOrNull(3)?.toIntOrNull())
                "GST" -> NmeaSentence.Gst(
                    latErrM = f.getOrNull(6)?.toDoubleOrNull(),
                    lonErrM = f.getOrNull(7)?.toDoubleOrNull(),
                    altErrM = f.getOrNull(8)?.toDoubleOrNull(),
                )
                else -> NmeaSentence.Unknown(type)
            }
        } catch (_: IndexOutOfBoundsException) {
            null // truncated field list
        }
    }

    private fun parseGga(f: List<String>): NmeaSentence.Gga? {
        if (f.size < 14) return null
        return NmeaSentence.Gga(
            timeUtc = f[1].ifEmpty { null },
            latDeg = parseLat(f[2], f[3]),
            lonDeg = parseLon(f[4], f[5]),
            quality = FixQuality.fromCode(f[6].toIntOrNull() ?: 0),
            satellites = f[7].toIntOrNull(),
            hdop = f[8].toDoubleOrNull(),
            altitudeM = f[9].toDoubleOrNull(),
            correctionAgeS = f.getOrNull(13)?.toDoubleOrNull(),
        )
    }

    private fun parseRmc(f: List<String>): NmeaSentence.Rmc? {
        if (f.size < 10) return null
        return NmeaSentence.Rmc(
            timeUtc = f[1].ifEmpty { null },
            valid = f[2] == "A",
            latDeg = parseLat(f[3], f[4]),
            lonDeg = parseLon(f[5], f[6]),
            speedMps = f[7].toDoubleOrNull()?.times(KNOT_MPS),
            courseDeg = f[8].toDoubleOrNull(),
            dateDdMmYy = f[9].ifEmpty { null },
        )
    }

    private fun parseVtg(f: List<String>): NmeaSentence.Vtg {
        return NmeaSentence.Vtg(
            courseDeg = f.getOrNull(1)?.toDoubleOrNull(),
            speedMps = f.getOrNull(5)?.toDoubleOrNull()?.times(KNOT_MPS),
        )
    }

    /** "4807.03800","N" -> 48.1173; ddmm.mmmm format. */
    private fun parseLat(v: String, hemi: String): Double? {
        if (v.length < 4) return null
        val deg = v.substring(0, 2).toDoubleOrNull() ?: return null
        val min = v.substring(2).toDoubleOrNull() ?: return null
        val d = deg + min / 60.0
        return if (hemi == "S") -d else d
    }

    /** "01131.00000","E" -> 11.516667; dddmm.mmmm format. */
    private fun parseLon(v: String, hemi: String): Double? {
        if (v.length < 5) return null
        val deg = v.substring(0, 3).toDoubleOrNull() ?: return null
        val min = v.substring(3).toDoubleOrNull() ?: return null
        val d = deg + min / 60.0
        return if (hemi == "W") -d else d
    }

    private const val KNOT_MPS = 0.514444
}
