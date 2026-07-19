package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkPointTest {
    @Test fun `no offsets means work point equals antenna`() {
        val c = ImplementConfig(widthM = 6.0)
        val w = c.workPoint(EnuPoint(10.0, 20.0), headingDeg = 45.0)
        assertEquals(10.0, w.x, 1e-9); assertEquals(20.0, w.y, 1e-9)
    }

    @Test fun `longitudinal offset shifts work point backwards along heading`() {
        val c = ImplementConfig(widthM = 6.0, longitudinalOffsetM = 3.0)
        val w = c.workPoint(EnuPoint(0.0, 0.0), headingDeg = 0.0)   // facing north
        assertEquals(0.0, w.x, 1e-9); assertEquals(-3.0, w.y, 1e-9) // 3 m south
    }

    @Test fun `lateral offset shifts work point to the right of heading`() {
        val c = ImplementConfig(widthM = 6.0, lateralOffsetM = 1.5)
        val w = c.workPoint(EnuPoint(0.0, 0.0), headingDeg = 0.0)   // facing north
        assertEquals(1.5, w.x, 1e-9); assertEquals(0.0, w.y, 1e-9)  // 1.5 m east
    }

    @Test fun `offsets rotate with heading`() {
        val c = ImplementConfig(widthM = 6.0, lateralOffsetM = 2.0, longitudinalOffsetM = 4.0)
        val w = c.workPoint(EnuPoint(0.0, 0.0), headingDeg = 90.0)  // facing east
        // behind = west (-4 east), right of east = south (-2 north)
        assertEquals(-4.0, w.x, 1e-9); assertEquals(-2.0, w.y, 1e-9)
    }
}
