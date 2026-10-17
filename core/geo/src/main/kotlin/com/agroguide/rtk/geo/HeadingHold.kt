package com.agroguide.rtk.geo

/**
 * Course over ground is only meaningful while moving: receivers leave it empty
 * when stationary and report noise at crawl speed. Below [minSpeedMps] the last
 * trustworthy heading is held, so the heading-up view, the implement swath and
 * the guidance frame do not spin when the tractor stops.
 */
class HeadingHold(private val minSpeedMps: Double = 0.3) {
    private var held = 0.0

    fun update(courseDeg: Double?, speedMps: Double): Double {
        if (courseDeg != null && speedMps >= minSpeedMps) held = courseDeg
        return held
    }
}
