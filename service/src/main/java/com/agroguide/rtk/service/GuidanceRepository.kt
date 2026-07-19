package com.agroguide.rtk.service

import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.VehicleState
import com.agroguide.rtk.geo.guidance.CurveRecorder
import com.agroguide.rtk.geo.guidance.GuidanceLine
import com.agroguide.rtk.geo.guidance.GuidanceResult
import com.agroguide.rtk.geo.guidance.ImplementConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

enum class GuidanceMode { FREE, AB, A_PLUS, CURVE }

/**
 * Guidance state shared between UI and (later) job recording: active mode,
 * line, implement config, live result and the on-screen trail. update() is
 * called by the field-view collector for every VehicleState — math is O(1)
 * for lines and O(n segments) for curves, trivial at 15 Hz.
 */
object GuidanceRepository {
    private val _mode = MutableStateFlow(GuidanceMode.FREE)
    val mode: StateFlow<GuidanceMode> = _mode.asStateFlow()

    private val _line = MutableStateFlow<GuidanceLine?>(null)
    val line: StateFlow<GuidanceLine?> = _line.asStateFlow()

    private val _implement = MutableStateFlow(ImplementConfig(widthM = 6.0))
    val implement: StateFlow<ImplementConfig> = _implement.asStateFlow()

    private val _result = MutableStateFlow<GuidanceResult?>(null)
    val result: StateFlow<GuidanceResult?> = _result.asStateFlow()

    private val _pendingA = MutableStateFlow<EnuPoint?>(null)
    val pendingA: StateFlow<EnuPoint?> = _pendingA.asStateFlow()

    val recorder = CurveRecorder()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _trail = MutableStateFlow<List<EnuPoint>>(emptyList())
    val trail: StateFlow<List<EnuPoint>> = _trail.asStateFlow()
    private var lastTrail: EnuPoint? = null

    fun setImplement(c: ImplementConfig) { _implement.value = c }

    fun setMode(m: GuidanceMode) {
        _mode.value = m
        if (m == GuidanceMode.FREE) _result.value = null
    }

    fun markA(state: VehicleState) {
        _pendingA.value = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
    }

    fun markB(state: VehicleState): Boolean {
        val a = _pendingA.value ?: return false
        val b = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
        val line = GuidanceLine.Ab(a, b)
        if (line.guide(b, state.headingDeg, _implement.value.widthM) == null) return false
        _line.value = line; _pendingA.value = null; _mode.value = GuidanceMode.AB
        return true
    }

    fun markAPlus(state: VehicleState) {
        val a = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
        _line.value = GuidanceLine.APlus(a, state.headingDeg)
        _pendingA.value = null; _mode.value = GuidanceMode.A_PLUS
    }

    fun startCurve() { recorder.clear(); _recording.value = true }

    fun stopCurve(): Boolean {
        _recording.value = false
        if (recorder.points.size < 2) return false
        _line.value = GuidanceLine.Curve(recorder.points.toList())
        _mode.value = GuidanceMode.CURVE
        return true
    }

    fun clearLine() { _line.value = null; _result.value = null; _pendingA.value = null }

    /** Called for every VehicleState by the field-view collector. */
    fun update(state: VehicleState) {
        val work = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
        if (_recording.value) recorder.add(work)
        val last = lastTrail
        if (last == null || hypot(work.x - last.x, work.y - last.y) > 0.5) {
            lastTrail = work
            _trail.value = (_trail.value + work).let { if (it.size > 4000) it.drop(1000) else it }
        }
        _result.value = if (_mode.value == GuidanceMode.FREE) null
        else _line.value?.guide(work, state.headingDeg, _implement.value.widthM)
    }

    fun clearTrail() { _trail.value = emptyList(); lastTrail = null }
}
