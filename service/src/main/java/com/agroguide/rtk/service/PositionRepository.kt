package com.agroguide.rtk.service

import com.agroguide.rtk.device.PositionPipeline
import com.agroguide.rtk.device.PositionSource
import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.geo.CvProjector
import com.agroguide.rtk.geo.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Single source of truth for the app: latest VehicleState (render-paced via
 * CvProjector at [RENDER_HZ]) and connection state. The foreground service
 * drives it; Compose collects it.
 */
object PositionRepository {
    private val _vehicle = MutableStateFlow<VehicleState?>(null)
    val vehicle: StateFlow<VehicleState?> = _vehicle.asStateFlow()

    private val _connection = MutableStateFlow(
        SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.DISCONNECTED)
    )
    val connection: StateFlow<SourceEvent.ConnectionState> = _connection.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    private var scope: CoroutineScope? = null
    private const val RENDER_HZ = 15

    /**
     * Called by GuidanceService when a source is (re)selected. The source flow
     * is shared so the underlying transport (e.g. a BT socket) is opened once
     * even though both the pipeline and the connection monitor collect it.
     */
    fun attach(source: PositionSource, nowMs: () -> Long) {
        detach()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        _sourceName.value = source.name
        val shared = source.events.shareIn(s, SharingStarted.Eagerly)
        val sharedSource = object : PositionSource {
            override val name = source.name
            override val events = shared
        }
        val pipeline = PositionPipeline(sharedSource)
        val projector = CvProjector()
        s.launch {
            shared.filterIsInstance<SourceEvent.ConnectionState>().collect { _connection.value = it }
        }
        s.launch {
            pipeline.states.collect { projector.onFix(it); _vehicle.value = it }
        }
        s.launch {
            while (isActive) {
                delay(1000L / RENDER_HZ)
                projector.projectAt(nowMs())?.let { if (it.projected) _vehicle.value = it }
            }
        }
    }

    fun detach() {
        scope?.cancel(); scope = null
        _sourceName.value = null
        _connection.value = SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.DISCONNECTED)
    }
}
