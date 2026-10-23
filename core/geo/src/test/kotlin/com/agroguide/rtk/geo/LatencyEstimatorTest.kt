package com.agroguide.rtk.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatencyEstimatorTest {
    @Test fun `parses NMEA time of day`() {
        assertEquals(12 * 3_600_000L + 34 * 60_000L + 56_200L, LatencyEstimator.parseNmeaTime("123456.20"))
        assertEquals(0L, LatencyEstimator.parseNmeaTime("000000.00"))
        assertNull(LatencyEstimator.parseNmeaTime(""))
        assertNull(LatencyEstimator.parseNmeaTime("12ab56"))
        assertNull(LatencyEstimator.parseNmeaTime("256000.00"))
    }

    @Test fun `zero until enough samples, then the median`() {
        val e = LatencyEstimator(window = 9, minSamples = 5)
        val base = 40_000_000L
        listOf(120L, 130L, 110L, 125L).forEachIndexed { i, d -> e.onFix(base + i * 200, base + i * 200 + d) }
        assertEquals(0, e.latencyMs)
        e.onFix(base + 1000, base + 1000 + 500)          // one slow outlier
        assertEquals(125, e.latencyMs)                    // median ignores it
    }

    @Test fun `implausible samples from an unsynchronised clock are ignored`() {
        val e = LatencyEstimator(minSamples = 1)
        e.onFix(40_000_000, 40_000_000 - 300)              // arrival before the fix: clock behind
        e.onFix(40_000_000, 40_000_000 + 5_000)            // 5 s: clock ahead
        assertEquals(0, e.latencyMs)
        e.onFix(40_000_000, 40_000_000 + 140)
        assertEquals(140, e.latencyMs)
    }

    @Test fun `handles the UTC midnight rollover`() {
        val e = LatencyEstimator(minSamples = 1)
        e.onFix(LatencyEstimator.DAY_MS - 50, 70)         // fix 23:59:59.950, arrival 00:00:00.070
        assertEquals(120, e.latencyMs)
    }
}
