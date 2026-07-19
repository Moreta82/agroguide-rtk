package com.agroguide.rtk.geo

import kotlin.math.hypot
import kotlin.math.max

/**
 * Speed-gated outlier rejection. A fix is accepted when its displacement from
 * the last accepted position is explainable by (reported speed + margin) over
 * the elapsed time. After [persistToRelocate] consecutive rejections that are
 * mutually consistent (within 25 m of the first rejected point), the filter
 * relocates — a real long jump (receiver cold start, transport in a vehicle).
 */
class JumpFilter(
    private val marginMps: Double = 3.0,
    private val gateFactor: Double = 2.0,
    private val persistToRelocate: Int = 4,
) {
    private var last: EnuPoint? = null
    private var lastMs: Long = 0
    private var rejectAnchor: EnuPoint? = null
    private var rejectCount = 0

    fun accept(p: EnuPoint, speedMps: Double, monotonicMs: Long): Boolean {
        val prev = last ?: return acceptInternal(p, monotonicMs)
        val dtS = max((monotonicMs - lastMs) / 1000.0, 0.05)
        val allowed = (speedMps + marginMps) * dtS * gateFactor + 1.0  // +1 m jitter floor
        val d = hypot(p.x - prev.x, p.y - prev.y)
        if (d <= allowed) return acceptInternal(p, monotonicMs)

        // Rejected: track whether the "new place" is self-consistent.
        val anchor = rejectAnchor
        if (anchor != null && hypot(p.x - anchor.x, p.y - anchor.y) < 25.0) {
            rejectCount++
            if (rejectCount >= persistToRelocate) return acceptInternal(p, monotonicMs)
        } else {
            rejectAnchor = p
            rejectCount = 1
        }
        return false
    }

    private fun acceptInternal(p: EnuPoint, ms: Long): Boolean {
        last = p; lastMs = ms; rejectAnchor = null; rejectCount = 0
        return true
    }
}
