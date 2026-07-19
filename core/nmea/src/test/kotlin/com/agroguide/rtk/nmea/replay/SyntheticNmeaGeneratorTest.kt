package com.agroguide.rtk.nmea.replay

import com.agroguide.rtk.nmea.FixQuality
import com.agroguide.rtk.nmea.NmeaParser
import com.agroguide.rtk.nmea.NmeaSentence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SyntheticNmeaGeneratorTest {
    @Test fun `generates parseable GGA and RMC pairs along a track`() {
        val gen = SyntheticNmeaGenerator(
            startLatDeg = 42.0, startLonDeg = -5.0,
            speedMps = 3.0, quality = FixQuality.RTK_FIXED,
        )
        val sentences = gen.next()  // one epoch = [GGA, RMC, VTG]
        assertEquals(3, sentences.size)
        val gga = NmeaParser.parse(sentences[0])
        assertIs<NmeaSentence.Gga>(gga)
        assertEquals(FixQuality.RTK_FIXED, gga.quality)
        assertEquals(42.0, gga.latDeg!!, 1e-4)
        val rmc = NmeaParser.parse(sentences[1])
        assertIs<NmeaSentence.Rmc>(rmc)
        assertEquals(3.0, rmc.speedMps!!, 0.01)
    }

    @Test fun `position advances between epochs at commanded speed`() {
        val gen = SyntheticNmeaGenerator(42.0, -5.0, speedMps = 5.0, headingDeg = 0.0, epochIntervalS = 1.0)
        val g1 = NmeaParser.parse(gen.next()[0]) as NmeaSentence.Gga
        val g2 = NmeaParser.parse(gen.next()[0]) as NmeaSentence.Gga
        // 5 m north ~ 5 / 111320 degrees latitude
        assertEquals(5.0 / 111_320.0, g2.latDeg!! - g1.latDeg!!, 1e-7)
    }

    @Test fun `sentences use dot decimal separator regardless of default locale`() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("es", "ES"))
            val gen = SyntheticNmeaGenerator(42.0, -5.0)
            assertFalse(gen.next()[0].contains(",60"))  // would appear if "%.2f" used es locale
            assertNotNull(NmeaParser.parse(gen.next()[0]))
        } finally { java.util.Locale.setDefault(prev) }
    }
}
