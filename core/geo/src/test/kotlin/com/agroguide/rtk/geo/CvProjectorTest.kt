package com.agroguide.rtk.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CvProjectorTest {
    private fun state(x: Double, y: Double, headingDeg: Double, speed: Double, tMs: Long) = VehicleState(
        x = x, y = y, latDeg = 0.0, lonDeg = 0.0, headingDeg = headingDeg, speedMps = speed,
        quality = GnssQuality.RTK_FIXED, satellites = 12, hdop = 0.6, correctionAgeS = 1.0,
        fixMonotonicMs = tMs, projected = false,
    )

    @Test fun `projects along heading at constant speed`() {
        val p = CvProjector()
        p.onFix(state(0.0, 0.0, headingDeg = 90.0, speed = 2.0, tMs = 0))
        val s = p.projectAt(500)!!   // 0.5 s later, heading east
        assertEquals(1.0, s.x, 1e-9)
        assertEquals(0.0, s.y, 1e-9)
        assertTrue(s.projected)
    }

    @Test fun `does not extrapolate beyond cap`() {
        val p = CvProjector(maxExtrapolationMs = 2000)
        p.onFix(state(0.0, 0.0, 0.0, 5.0, 0))
        val s = p.projectAt(10_000)!!
        assertEquals(10.0, s.y, 1e-9)  // frozen at cap: 5 m/s * 2 s
    }

    @Test fun `fresh fix resets projection base`() {
        val p = CvProjector()
        p.onFix(state(0.0, 0.0, 0.0, 5.0, 0))
        p.onFix(state(0.0, 4.9, 0.0, 5.0, 1000))
        val s = p.projectAt(1200)!!
        assertEquals(5.9, s.y, 1e-9)
    }

    @Test fun `null before any fix`() {
        assertNull(CvProjector().projectAt(100))
    }
}
