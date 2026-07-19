package com.agroguide.rtk.service

import com.agroguide.rtk.coverage.CoverageEngine
import com.agroguide.rtk.coverage.CoverageStats
import com.agroguide.rtk.coverage.TileKey
import com.agroguide.rtk.coverage.TileSnapshot
import com.agroguide.rtk.coverage.WorkPose
import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.VehicleState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Owns the CoverageEngine on a single-threaded dispatcher. UI reads immutable
 * snapshots (stats + per-tile geometry). Sections are manual toggles; painting
 * happens only while moving (> 0.2 m/s) with some section active.
 */
object CoverageRepository {
    private val engine = CoverageEngine(widthM = 6.0, sections = 1)

    private val mutationDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "coverage").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + mutationDispatcher)

    private val _stats = MutableStateFlow(CoverageStats())
    val stats: StateFlow<CoverageStats> = _stats.asStateFlow()

    private val _sections = MutableStateFlow(listOf(true))
    val sections: StateFlow<List<Boolean>> = _sections.asStateFlow()

    private val _tiles = MutableStateFlow<Map<TileKey, TileSnapshot>>(emptyMap())
    val tiles: StateFlow<Map<TileKey, TileSnapshot>> = _tiles.asStateFlow()

    fun configure(widthM: Double, sections: Int) {
        scope.launch {
            engine.widthM = widthM
            engine.sections = sections
            val current = _sections.value
            if (current.size != sections) {
                _sections.value = List(sections) { current.getOrElse(it) { true } }
            }
        }
    }

    fun toggleSection(i: Int) {
        _sections.value = _sections.value.mapIndexed { idx, v -> if (idx == i) !v else v }
    }

    fun setAllSections(on: Boolean) { _sections.value = _sections.value.map { on } }

    fun reset() {
        scope.launch { engine.reset(); publish() }
    }

    /** Called by the service's vehicle collector for every state. */
    fun update(state: VehicleState, workPoint: EnuPoint) {
        val active = _sections.value.toBooleanArray()
        scope.launch {
            if (state.speedMps > 0.2) {
                engine.update(WorkPose(workPoint, state.headingDeg), active)
            } else {
                engine.breakSegment()
            }
            publish()
        }
    }

    private fun publish() {
        _stats.value = engine.stats
        _tiles.value = engine.tiles()
    }
}
