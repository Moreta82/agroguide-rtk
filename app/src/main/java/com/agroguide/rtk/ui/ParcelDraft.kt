package com.agroguide.rtk.ui

import com.agroguide.rtk.geo.EnuPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/** In-progress parcel creation shared between the Parcelas tab and the field view. */
object ParcelDraft {
    enum class Mode { NONE, DRIVE, TAP }

    private val _mode = MutableStateFlow(Mode.NONE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _points = MutableStateFlow<List<EnuPoint>>(emptyList())
    val points: StateFlow<List<EnuPoint>> = _points.asStateFlow()

    fun startDrive() { _points.value = emptyList(); _mode.value = Mode.DRIVE }
    fun startTap() { _points.value = emptyList(); _mode.value = Mode.TAP }
    fun cancel() { _points.value = emptyList(); _mode.value = Mode.NONE }

    /** Adds a point; DRIVE mode enforces >= 1 m spacing. */
    fun addPoint(p: EnuPoint) {
        val pts = _points.value
        if (_mode.value == Mode.DRIVE) {
            val last = pts.lastOrNull()
            if (last != null && hypot(p.x - last.x, p.y - last.y) < 1.0) return
        }
        _points.value = pts + p
    }

    /** Returns the ring and leaves draft mode; null if fewer than 3 points. */
    fun close(): List<EnuPoint>? {
        val ring = _points.value
        _mode.value = Mode.NONE
        _points.value = emptyList()
        return ring.takeIf { it.size >= 3 }
    }
}
