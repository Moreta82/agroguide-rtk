package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.TangentPlane
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Expresses this line, whose coordinates are ENU in [from], in the ENU frame
 * of [to], keeping it on the same ground. Needed whenever the app's frame
 * changes (a new session anchors on its first fix, a job pins its own plane):
 * raw ENU coordinates reused in another frame land metres away.
 */
fun GuidanceLine.reproject(from: TangentPlane, to: TangentPlane): GuidanceLine {
    if (from.originLatDeg == to.originLatDeg && from.originLonDeg == to.originLonDeg) return this
    fun move(p: EnuPoint): EnuPoint {
        val (lat, lon) = from.toGeo(p.x, p.y)
        return to.toEnu(lat, lon)
    }
    return when (this) {
        is GuidanceLine.Ab -> GuidanceLine.Ab(move(a), move(b))
        is GuidanceLine.Curve -> GuidanceLine.Curve(points.map(::move))
        is GuidanceLine.APlus -> {
            // Heading is relative to the frame's north; carry a point 100 m
            // along the line across so the bearing stays true on the ground.
            val h = headingDeg * PI / 180
            val a2 = move(a)
            val ahead = move(EnuPoint(a.x + 100 * sin(h), a.y + 100 * cos(h)))
            val deg = atan2(ahead.x - a2.x, ahead.y - a2.y) * 180 / PI
            GuidanceLine.APlus(a2, (deg + 360) % 360)
        }
    }
}
