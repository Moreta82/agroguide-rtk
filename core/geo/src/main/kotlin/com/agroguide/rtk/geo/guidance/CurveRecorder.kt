package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.math.hypot

/** Collects a driven curve, dropping points closer than [minSpacingM]. */
class CurveRecorder(private val minSpacingM: Double = 0.5) {
    private val _points = mutableListOf<EnuPoint>()
    val points: List<EnuPoint> get() = _points

    fun add(p: EnuPoint) {
        val last = _points.lastOrNull()
        if (last == null || hypot(p.x - last.x, p.y - last.y) >= minSpacingM) _points += p
    }

    fun clear() = _points.clear()
}
