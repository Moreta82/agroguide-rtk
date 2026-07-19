package com.agroguide.rtk.coverage

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwathTest {
    @Test fun `section extents split the width evenly`() {
        val e = Swath.sectionExtent(widthM = 6.0, sections = 3, index = 0)
        assertEquals(-3.0, e.first, 1e-9); assertEquals(-1.0, e.second, 1e-9)
        val last = Swath.sectionExtent(6.0, 3, 2)
        assertEquals(1.0, last.first, 1e-9); assertEquals(3.0, last.second, 1e-9)
    }

    @Test fun `straight segment quad area equals width times length`() {
        val q = Swath.quad(
            WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(0.0, 10.0), 0.0),
            fromM = -3.0, toM = 3.0,
        )!!
        assertEquals(60.0, q.area, 1e-6)
    }

    @Test fun `quad follows per-pose headings on a turn and stays valid`() {
        val q = Swath.quad(
            WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(2.0, 2.0), 90.0),
            fromM = -3.0, toM = 3.0,
        )!!
        assertTrue(q.isValid)
        assertTrue(q.area > 0)
    }

    @Test fun `degenerate segments are rejected`() {
        assertNull(Swath.quad(WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(0.0, 0.01), 0.0), -3.0, 3.0))
        assertNull(Swath.quad(WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(0.0, 50.0), 0.0), -3.0, 3.0))
    }
}
