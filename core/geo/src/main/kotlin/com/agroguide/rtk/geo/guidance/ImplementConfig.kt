package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Implement geometry relative to the GNSS antenna.
 * [lateralOffsetM] > 0: implement center is right of the antenna.
 * [longitudinalOffsetM] > 0: implement works behind the antenna.
 */
data class ImplementConfig(
    val widthM: Double,
    val lateralOffsetM: Double = 0.0,
    val longitudinalOffsetM: Double = 0.0,
    val sections: Int = 1,               // used from phase 4
) {
    /** Point on the ground the implement actually works at, given antenna pose. */
    fun workPoint(antenna: EnuPoint, headingDeg: Double): EnuPoint {
        val h = headingDeg * PI / 180
        val fx = sin(h); val fy = cos(h)          // forward
        val rx = cos(h); val ry = -sin(h)         // right
        return EnuPoint(
            antenna.x - fx * longitudinalOffsetM + rx * lateralOffsetM,
            antenna.y - fy * longitudinalOffsetM + ry * lateralOffsetM,
        )
    }
}
