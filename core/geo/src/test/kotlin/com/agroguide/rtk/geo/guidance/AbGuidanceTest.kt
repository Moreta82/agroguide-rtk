package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AbGuidanceTest {
    // Base line: A=(0,0) B=(0,100) -> pointing north. Width 6 m.
    private val line = GuidanceLine.Ab(EnuPoint(0.0, 0.0), EnuPoint(0.0, 100.0))
    private val width = 6.0

    @Test fun `on the line, xte is zero, pass 0`() {
        val g = line.guide(EnuPoint(0.0, 50.0), headingDeg = 0.0, widthM = width)!!
        assertEquals(0.0, g.xteM, 1e-9)
        assertEquals(0, g.passIndex)
        assertEquals(0.0, g.targetHeadingDeg, 1e-9)
    }

    @Test fun `2 m east of line driving north gives positive xte (steer left)`() {
        val g = line.guide(EnuPoint(2.0, 50.0), 0.0, width)!!
        assertEquals(2.0, g.xteM, 1e-9)
        assertEquals(0, g.passIndex)
    }

    @Test fun `snaps to nearest pass`() {
        // 13 m east = pass 2 (12 m) plus 1 m
        val g = line.guide(EnuPoint(13.0, 50.0), 0.0, width)!!
        assertEquals(2, g.passIndex)
        assertEquals(1.0, g.xteM, 1e-9)
        // 14.9 m east -> pass 2 center 12, xte 2.9
        assertEquals(2.9, line.guide(EnuPoint(14.9, 50.0), 0.0, width)!!.xteM, 1e-9)
        // 15.1 m east -> pass 3 (18 m), xte -2.9
        val g3 = line.guide(EnuPoint(15.1, 50.0), 0.0, width)!!
        assertEquals(3, g3.passIndex)
        assertEquals(-2.9, g3.xteM, 1e-9)
    }

    @Test fun `driving south flips xte sign and target heading`() {
        val g = line.guide(EnuPoint(2.0, 50.0), headingDeg = 180.0, widthM = width)!!
        assertEquals(-2.0, g.xteM, 1e-9)          // east of line but on driver's LEFT now
        assertEquals(180.0, g.targetHeadingDeg, 1e-9)
    }

    @Test fun `west of line is negative pass indices`() {
        val g = line.guide(EnuPoint(-6.0, 10.0), 0.0, width)!!
        assertEquals(-1, g.passIndex)
        assertEquals(0.0, g.xteM, 1e-9)
    }

    @Test fun `A plus builds the same geometry from point and heading`() {
        val ap = GuidanceLine.APlus(EnuPoint(0.0, 0.0), headingDeg = 0.0)
        val g = ap.guide(EnuPoint(2.0, 50.0), 0.0, width)!!
        assertEquals(2.0, g.xteM, 1e-9)
    }

    @Test fun `degenerate AB (too short) guides null`() {
        val bad = GuidanceLine.Ab(EnuPoint(0.0, 0.0), EnuPoint(0.0, 0.5))
        assertNull(bad.guide(EnuPoint(1.0, 0.0), 0.0, width))
    }

    @Test fun `diagonal line target heading follows line azimuth`() {
        val diag = GuidanceLine.Ab(EnuPoint(0.0, 0.0), EnuPoint(100.0, 100.0)) // NE, 45 deg
        // wide implement (30 m) so pass stays 0 and xte is the raw perpendicular offset
        val g = diag.guide(EnuPoint(0.0, 10.0), headingDeg = 45.0, widthM = 30.0)!!
        assertEquals(45.0, g.targetHeadingDeg, 1e-9)
        assertEquals(0, g.passIndex)
        // (0,10) relative to NE line: left side -> negative xte = -10*sin(45)
        assertEquals(-10.0 * kotlin.math.sin(kotlin.math.PI / 4), g.xteM, 1e-6)
    }
}
