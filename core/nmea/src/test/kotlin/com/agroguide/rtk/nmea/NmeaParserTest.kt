package com.agroguide.rtk.nmea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NmeaParserTest {
    // Real ZED-F9P style sentences (checksums valid).
    private val gga = "\$GNGGA,123519.00,4807.03800,N,01131.00000,E,4,12,0.58,545.4,M,46.9,M,1.2,0000*60"
    private val rmc = "\$GNRMC,123519.00,A,4807.03800,N,01131.00000,E,3.5,84.4,230326,,,R,V*1E"

    @Test fun `parses GGA with RTK fixed quality`() {
        val s = NmeaParser.parse(gga)
        assertIs<NmeaSentence.Gga>(s)
        assertEquals(FixQuality.RTK_FIXED, s.quality)
        assertEquals(48.117300, s.latDeg!!, 1e-6)
        assertEquals(11.516667, s.lonDeg!!, 1e-6)
        assertEquals(12, s.satellites)
        assertEquals(0.58, s.hdop!!, 1e-9)
        assertEquals(545.4, s.altitudeM!!, 1e-9)
        assertEquals(1.2, s.correctionAgeS!!, 1e-9)
    }

    @Test fun `parses RMC speed knots to mps and course`() {
        val s = NmeaParser.parse(rmc)
        assertIs<NmeaSentence.Rmc>(s)
        assertTrue(s.valid)
        assertEquals(3.5 * 0.514444, s.speedMps!!, 1e-6)
        assertEquals(84.4, s.courseDeg!!, 1e-9)
    }

    @Test fun `southern and western hemispheres are negative`() {
        val line = withChecksum("\$GNGGA,000000.00,3345.00000,S,07030.00000,W,1,08,1.0,10.0,M,0.0,M,,")
        val s = NmeaParser.parse(line) as NmeaSentence.Gga
        assertEquals(-33.75, s.latDeg!!, 1e-9)
        assertEquals(-70.5, s.lonDeg!!, 1e-9)
    }

    @Test fun `rejects wrong checksum`() {
        assertNull(NmeaParser.parse(gga.dropLast(2) + "00"))
    }

    @Test fun `rejects truncated sentence`() {
        assertNull(NmeaParser.parse("\$GNGGA,1235"))          // no checksum
        assertNull(NmeaParser.parse(gga.substring(0, 20)))
    }

    @Test fun `rejects garbage and empty`() {
        assertNull(NmeaParser.parse(""))
        assertNull(NmeaParser.parse("not nmea at all"))
        assertNull(NmeaParser.parse("\$*00"))
    }

    @Test fun `GGA with empty fields yields nulls and NO_FIX`() {
        val line = withChecksum("\$GNGGA,,,,,,0,,,,M,,M,,")
        val s = NmeaParser.parse(line) as NmeaSentence.Gga
        assertEquals(FixQuality.NO_FIX, s.quality)
        assertNull(s.latDeg); assertNull(s.satellites); assertNull(s.correctionAgeS)
    }

    @Test fun `RMC void has valid=false`() {
        val line = withChecksum("\$GNRMC,,V,,,,,,,,,,N,V")
        val s = NmeaParser.parse(line) as NmeaSentence.Rmc
        assertFalse(s.valid)
    }

    @Test fun `parses VTG GSV GST`() {
        val vtg = withChecksum("\$GNVTG,84.4,T,,M,3.5,N,6.5,K,D")
        assertEquals(84.4, (NmeaParser.parse(vtg) as NmeaSentence.Vtg).courseDeg!!, 1e-9)
        val gsv = withChecksum("\$GPGSV,3,1,11,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45")
        assertEquals(11, (NmeaParser.parse(gsv) as NmeaSentence.Gsv).totalInView)
        val gst = withChecksum("\$GNGST,123519.00,1.2,0.03,0.02,12.3,0.01,0.02,0.04")
        val g = NmeaParser.parse(gst) as NmeaSentence.Gst
        assertEquals(0.01, g.latErrM!!, 1e-9); assertEquals(0.02, g.lonErrM!!, 1e-9); assertEquals(0.04, g.altErrM!!, 1e-9)
    }

    @Test fun `unknown talker sentence returns Unknown`() {
        val line = withChecksum("\$GNZDA,123519.00,23,03,2026,00,00")
        assertEquals("ZDA", (NmeaParser.parse(line) as NmeaSentence.Unknown).type)
    }

    /** Helper: append correct *hh checksum to a sentence body starting with '$'. */
    private fun withChecksum(body: String): String {
        var x = 0; for (c in body.drop(1)) x = x xor c.code
        return body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }
}
