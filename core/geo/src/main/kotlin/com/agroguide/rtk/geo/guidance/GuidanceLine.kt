package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Result of one guidance evaluation, already in the driver's frame. */
data class GuidanceResult(
    val xteM: Double,            // + = right of pass center -> steer left
    val passIndex: Int,          // 0 = base line, + to the base line's right
    val targetHeadingDeg: Double,
)

sealed interface GuidanceLine {
    /**
     * Evaluate guidance for [workPoint] with the vehicle on [headingDeg].
     * Returns null when the line is degenerate. XTE is driver-frame.
     */
    fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult?

    data class Ab(val a: EnuPoint, val b: EnuPoint) : GuidanceLine {
        override fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult? {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < MIN_AB_M) return null
            return lineGuide(workPoint, headingDeg, widthM, a, dx / len, dy / len)
        }
    }

    data class APlus(val a: EnuPoint, val headingDeg: Double) : GuidanceLine {
        override fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult? {
            val h = this.headingDeg * PI / 180
            return lineGuide(workPoint, headingDeg, widthM, a, sin(h), cos(h))
        }
    }

    /**
     * Recorded curve. Pass offsets are measured as perpendicular distance to
     * the ORIGINAL polyline — exact on straights and gentle bends, degrades on
     * tight corners (offset self-intersection is not handled in v1).
     */
    data class Curve(val points: List<EnuPoint>) : GuidanceLine {
        override fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult? {
            if (points.size < 2) return null
            // nearest segment by clamped perpendicular projection
            var best = Double.MAX_VALUE
            var bestUx = 0.0; var bestUy = 0.0; var bestOff = 0.0
            for (i in 0 until points.size - 1) {
                val p1 = points[i]; val p2 = points[i + 1]
                val dx = p2.x - p1.x; val dy = p2.y - p1.y
                val len2 = dx * dx + dy * dy
                if (len2 < 1e-12) continue
                val t = (((workPoint.x - p1.x) * dx + (workPoint.y - p1.y) * dy) / len2).coerceIn(0.0, 1.0)
                val cx = p1.x + t * dx; val cy = p1.y + t * dy
                val ex = workPoint.x - cx; val ey = workPoint.y - cy
                val d2 = ex * ex + ey * ey
                if (d2 < best) {
                    best = d2
                    val len = kotlin.math.sqrt(len2)
                    bestUx = dx / len; bestUy = dy / len
                    bestOff = ex * bestUy + ey * -bestUx   // signed via segment right normal
                }
            }
            // Reuse straight-line snapping in the local segment frame: anchor so
            // the field offset equals bestOff.
            val anchor = EnuPoint(workPoint.x - bestOff * bestUy, workPoint.y + bestOff * bestUx)
            return lineGuide(workPoint, headingDeg, widthM, anchor, bestUx, bestUy)
        }
    }

    companion object {
        const val MIN_AB_M = 2.0

        /** Shared straight-line math for Ab and APlus (and per-segment curve frames). */
        internal fun lineGuide(
            p: EnuPoint, vehicleHeadingDeg: Double, widthM: Double,
            a: EnuPoint, ux: Double, uy: Double,
        ): GuidanceResult {
            val rx = uy; val ry = -ux                       // right normal of line direction
            val off = (p.x - a.x) * rx + (p.y - a.y) * ry   // signed field-frame offset
            val pass = (off / widthM).roundToInt()
            var xte = off - pass * widthM
            var target = (atan2(ux, uy) * 180 / PI + 360) % 360
            // driver frame: flip when driving anti-parallel to the line
            val h = vehicleHeadingDeg * PI / 180
            val alongHeading = sin(h) * ux + cos(h) * uy
            if (alongHeading < 0) {
                xte = -xte
                target = (target + 180) % 360
            }
            return GuidanceResult(xte, pass, target)
        }
    }
}
