package com.agroguide.rtk.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Constant-velocity dead reckoning between GNSS fixes so guidance and render
 * stay smooth at 1-5 Hz input. Extrapolation is capped: beyond the cap the
 * position freezes (stale data must be visible, not invented).
 */
class CvProjector(private val maxExtrapolationMs: Long = 2000) {
    private var base: VehicleState? = null

    fun onFix(s: VehicleState) { base = s.copy(projected = false) }

    fun projectAt(nowMonotonicMs: Long): VehicleState? {
        val b = base ?: return null
        val dtMs = min(nowMonotonicMs - b.fixMonotonicMs, maxExtrapolationMs).coerceAtLeast(0)
        if (dtMs == 0L) return b
        val dt = dtMs / 1000.0
        val rad = b.headingDeg * PI / 180
        return b.copy(
            x = b.x + b.speedMps * dt * sin(rad),
            y = b.y + b.speedMps * dt * cos(rad),
            projected = true,
        )
    }
}
