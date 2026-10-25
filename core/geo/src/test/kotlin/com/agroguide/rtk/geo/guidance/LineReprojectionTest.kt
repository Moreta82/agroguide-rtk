package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.TangentPlane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class LineReprojectionTest {
    // Two session frames ~1.5 km apart (León): the second session anchored
    // on a different first fix.
    private val planeA = TangentPlane(42.6000, -5.5700)
    private val planeB = TangentPlane(42.6100, -5.5550)

    private fun geo(plane: TangentPlane, p: EnuPoint) = plane.toGeo(p.x, p.y)

    @Test fun `AB line keeps its geographic position in another frame`() {
        val line = GuidanceLine.Ab(EnuPoint(10.0, 20.0), EnuPoint(310.0, 420.0))
        val moved = line.reproject(planeA, planeB)
        assertIs<GuidanceLine.Ab>(moved)
        for ((orig, new) in listOf(line.a to moved.a, line.b to moved.b)) {
            val (lat0, lon0) = geo(planeA, orig)
            val back = planeB.toEnu(lat0, lon0)
            assertEquals(back.x, new.x, 1e-6); assertEquals(back.y, new.y, 1e-6)
        }
        // and the raw coordinates really differ (the bug: reusing them as-is)
        assert(kotlin.math.abs(moved.a.x - line.a.x) > 500)
    }

    @Test fun `reprojecting there and back returns the original line`() {
        val line = GuidanceLine.Curve((0..20).map { EnuPoint(it * 5.0, it * it * 0.3) })
        val round = line.reproject(planeA, planeB).reproject(planeB, planeA)
        assertIs<GuidanceLine.Curve>(round)
        line.points.zip(round.points).forEach { (p, q) ->
            assertEquals(p.x, q.x, 1e-3); assertEquals(p.y, q.y, 1e-3)
        }
    }

    @Test fun `A+ heading stays true on the ground`() {
        val line = GuidanceLine.APlus(EnuPoint(50.0, -30.0), 37.0)
        val moved = line.reproject(planeA, planeB)
        assertIs<GuidanceLine.APlus>(moved)
        // a point 200 m along the original line must lie on the moved line
        val h = Math.toRadians(37.0)
        val far = geo(planeA, EnuPoint(50.0 + 200 * kotlin.math.sin(h), -30.0 + 200 * kotlin.math.cos(h)))
        val farB = planeB.toEnu(far.first, far.second)
        val res = moved.guide(farB, moved.headingDeg, 6.0)!!
        assertEquals(0.0, res.xteM, 0.02)
        assertEquals(0, res.passIndex)
    }

    @Test fun `same frame is a no-op`() {
        val line = GuidanceLine.Ab(EnuPoint(1.0, 2.0), EnuPoint(3.0, 4.0))
        assertSame(line, line.reproject(planeA, TangentPlane(planeA.originLatDeg, planeA.originLonDeg)))
    }
}
