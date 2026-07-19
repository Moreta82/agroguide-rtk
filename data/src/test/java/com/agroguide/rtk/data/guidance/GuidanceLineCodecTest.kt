package com.agroguide.rtk.data.guidance

import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.guidance.GuidanceLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GuidanceLineCodecTest {
    @Test fun `AB roundtrip`() {
        val line = GuidanceLine.Ab(EnuPoint(1.5, 2.5), EnuPoint(100.0, 200.0))
        val back = GuidanceLineCodec.fromJson(GuidanceLineCodec.toJson(line)!!)
        assertIs<GuidanceLine.Ab>(back)
        assertEquals(1.5, back.a.x, 1e-9); assertEquals(200.0, back.b.y, 1e-9)
    }

    @Test fun `APlus roundtrip keeps heading`() {
        val line = GuidanceLine.APlus(EnuPoint(0.0, 0.0), headingDeg = 47.5)
        val back = GuidanceLineCodec.fromJson(GuidanceLineCodec.toJson(line)!!)
        assertIs<GuidanceLine.APlus>(back)
        assertEquals(47.5, back.headingDeg, 1e-9)
    }

    @Test fun `Curve roundtrip keeps all points`() {
        val line = GuidanceLine.Curve((0..10).map { EnuPoint(it.toDouble(), it * 2.0) })
        val back = GuidanceLineCodec.fromJson(GuidanceLineCodec.toJson(line)!!)
        assertIs<GuidanceLine.Curve>(back)
        assertEquals(11, back.points.size)
        assertEquals(20.0, back.points[10].y, 1e-9)
    }

    @Test fun `null and garbage decode to null`() {
        assertNull(GuidanceLineCodec.toJson(null))
        assertNull(GuidanceLineCodec.fromJson("garbage"))
        assertNull(GuidanceLineCodec.fromJson("""{"type":"XX","points":[]}"""))
    }
}
