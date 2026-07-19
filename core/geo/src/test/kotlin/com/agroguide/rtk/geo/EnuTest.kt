package com.agroguide.rtk.geo

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnuTest {
    @Test fun `origin maps to 0,0`() {
        val p = TangentPlane(42.0, -5.0)
        val e = p.toEnu(42.0, -5.0)
        assertEquals(0.0, e.x, 1e-9); assertEquals(0.0, e.y, 1e-9)
    }

    @Test fun `one degree latitude north is about 111 km`() {
        val p = TangentPlane(42.0, -5.0)
        val e = p.toEnu(43.0, -5.0)
        assertEquals(0.0, e.x, 1.0)                 // no east component
        assertEquals(111_000.0, e.y, 1_000.0)       // ~110.9 km at 42N
    }

    @Test fun `east displacement shrinks with cos(lat)`() {
        val p = TangentPlane(60.0, 0.0)
        val e = p.toEnu(60.0, 0.001)
        // 0.001 deg lon at 60N ~ 111320*cos(60)*0.001 ~ 55.7 m
        assertEquals(55.7, e.x, 0.5)
        assertEquals(0.0, e.y, 0.1)
    }

    @Test fun `roundtrip toGeo inverts toEnu within 1 mm over 2 km`() {
        val p = TangentPlane(42.123456, -5.654321)
        val e = p.toEnu(42.13, -5.63)
        val (lat, lon) = p.toGeo(e.x, e.y)
        val back = p.toEnu(lat, lon)
        assertTrue(hypot(back.x - e.x, back.y - e.y) < 0.001)
    }

    @Test fun `small offsets are metrically accurate against meridian arc truth`() {
        // Meridian arc length per degree at 42N (Snyder series):
        // 111132.954 - 559.822*cos(2*42) + 1.175*cos(4*42) = 111073.3 m/deg
        val p = TangentPlane(42.0, -5.0)
        val e = p.toEnu(42.0 + 100.0 / 111_073.3, -5.0)
        assertEquals(100.0, e.y, 0.01)
    }
}
