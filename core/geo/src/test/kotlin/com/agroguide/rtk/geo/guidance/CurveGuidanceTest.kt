package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurveGuidanceTest {
    // L-shaped path north then east, corner at (0,100)
    private val curve = GuidanceLine.Curve(
        (0..100 step 10).map { EnuPoint(0.0, it.toDouble()) } +
            (10..100 step 10).map { EnuPoint(it.toDouble(), 100.0) }
    )

    @Test fun `near first leg behaves like the north line`() {
        val g = curve.guide(EnuPoint(2.0, 50.0), 0.0, 6.0)!!
        assertEquals(2.0, g.xteM, 1e-6)
        assertEquals(0, g.passIndex)
        assertEquals(0.0, g.targetHeadingDeg, 1e-6)
    }

    @Test fun `near second leg target heading is east`() {
        val g = curve.guide(EnuPoint(50.0, 99.0), 90.0, 6.0)!!
        assertEquals(90.0, g.targetHeadingDeg, 1e-6)
        // 1 m south of east-bound leg = right side -> positive xte
        assertEquals(1.0, g.xteM, 1e-6)
    }

    @Test fun `snaps to offset passes of the curve`() {
        val g = curve.guide(EnuPoint(7.0, 50.0), 0.0, 6.0)!!   // 7 m east of north leg
        assertEquals(1, g.passIndex)
        assertEquals(1.0, g.xteM, 1e-6)
    }

    @Test fun `driving back along the curve flips the frame`() {
        val g = curve.guide(EnuPoint(2.0, 50.0), 180.0, 6.0)!!
        assertEquals(-2.0, g.xteM, 1e-6)
        assertEquals(180.0, g.targetHeadingDeg, 1e-6)
    }

    @Test fun `fewer than 2 points guides null`() {
        assertNull(GuidanceLine.Curve(listOf(EnuPoint(0.0, 0.0))).guide(EnuPoint(0.0, 0.0), 0.0, 6.0))
    }

    @Test fun `recorder enforces minimum spacing`() {
        val r = CurveRecorder(minSpacingM = 0.5)
        r.add(EnuPoint(0.0, 0.0))
        r.add(EnuPoint(0.1, 0.0))     // too close, dropped
        r.add(EnuPoint(0.6, 0.0))
        r.add(EnuPoint(1.2, 0.0))
        assertEquals(3, r.points.size)
        assertTrue(r.points.zipWithNext().all { (p, q) -> kotlin.math.hypot(q.x - p.x, q.y - p.y) >= 0.5 })
    }
}
