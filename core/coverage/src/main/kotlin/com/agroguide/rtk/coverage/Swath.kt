package com.agroguide.rtk.coverage

import com.agroguide.rtk.geo.EnuPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One work pose: implement work point + vehicle heading. */
data class WorkPose(val p: EnuPoint, val headingDeg: Double)

object Swath {
    val factory = GeometryFactory()
    const val MIN_SEG_M = 0.05
    const val MAX_SEG_M = 30.0

    /** Lateral [from, to] of section [index] out of [sections], 0 = leftmost. */
    fun sectionExtent(widthM: Double, sections: Int, index: Int): Pair<Double, Double> {
        val step = widthM / sections
        val from = -widthM / 2 + index * step
        return from to from + step
    }

    /**
     * Quad covered by the lateral band [fromM, toM] while moving pose a→b.
     * Edges use each pose's own right vector so turns paint correctly.
     * Bowties (sharp turns) are healed with buffer(0). Null when degenerate.
     */
    fun quad(a: WorkPose, b: WorkPose, fromM: Double, toM: Double): Polygon? {
        val d = hypot(b.p.x - a.p.x, b.p.y - a.p.y)
        if (d < MIN_SEG_M || d > MAX_SEG_M) return null
        val (arx, ary) = rightVec(a.headingDeg)
        val (brx, bry) = rightVec(b.headingDeg)
        val ring = arrayOf(
            Coordinate(a.p.x + arx * fromM, a.p.y + ary * fromM),
            Coordinate(b.p.x + brx * fromM, b.p.y + bry * fromM),
            Coordinate(b.p.x + brx * toM, b.p.y + bry * toM),
            Coordinate(a.p.x + arx * toM, a.p.y + ary * toM),
        )
        val poly = factory.createPolygon(ring + ring[0])
        return when {
            poly.isValid -> poly
            else -> poly.buffer(0.0) as? Polygon
        }
    }

    private fun rightVec(headingDeg: Double): Pair<Double, Double> {
        val h = headingDeg * PI / 180
        return cos(h) to -sin(h)
    }
}
