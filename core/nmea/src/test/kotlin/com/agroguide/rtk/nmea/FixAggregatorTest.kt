package com.agroguide.rtk.nmea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FixAggregatorTest {
    private fun cs(body: String): String {
        var x = 0; for (c in body.drop(1)) x = x xor c.code
        return body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }

    @Test fun `GGA alone emits fix without speed`() {
        val agg = FixAggregator()
        val fix = agg.onSentence(
            NmeaParser.parse(cs("\$GNGGA,120000.00,4807.03800,N,01131.00000,E,1,08,1.1,500.0,M,46.9,M,,"))!!,
            monotonicMs = 1000L,
        )
        assertNotNull(fix)
        assertEquals(48.1173, fix.latDeg, 1e-6)
        assertEquals(FixQuality.GPS, fix.quality)
        assertNull(fix.speedMps)
        assertEquals(1000L, fix.monotonicMs)
    }

    @Test fun `RMC then GGA merges speed and course into the GGA fix`() {
        val agg = FixAggregator()
        assertNull(agg.onSentence(NmeaParser.parse(cs("\$GNRMC,120000.00,A,4807.03800,N,01131.00000,E,2.0,90.0,230326,,,A,V"))!!, 990L))
        val fix = agg.onSentence(NmeaParser.parse(cs("\$GNGGA,120000.00,4807.03800,N,01131.00000,E,4,12,0.6,500.0,M,46.9,M,1.0,0000"))!!, 1000L)!!
        assertEquals(2.0 * 0.514444, fix.speedMps!!, 1e-6)
        assertEquals(90.0, fix.courseDeg!!, 1e-9)
        assertEquals(FixQuality.RTK_FIXED, fix.quality)
    }

    @Test fun `GSV updates satellites in view carried on next fix`() {
        val agg = FixAggregator()
        agg.onSentence(NmeaParser.parse(cs("\$GPGSV,3,1,11,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45"))!!, 900L)
        val fix = agg.onSentence(NmeaParser.parse(cs("\$GNGGA,120000.00,4807.03800,N,01131.00000,E,1,08,1.1,500.0,M,46.9,M,,"))!!, 1000L)!!
        assertEquals(11, fix.satsInView)
    }

    @Test fun `GGA without position emits null`() {
        val agg = FixAggregator()
        assertNull(agg.onSentence(NmeaParser.parse(cs("\$GNGGA,,,,,,0,,,,M,,M,,"))!!, 1000L))
    }
}
