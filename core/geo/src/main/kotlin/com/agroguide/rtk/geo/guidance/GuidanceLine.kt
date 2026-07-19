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
