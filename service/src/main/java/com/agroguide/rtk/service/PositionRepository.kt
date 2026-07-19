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

    private val _ntrip = MutableStateFlow(com.agroguide.rtk.ntrip.NtripStats())
    val ntrip: StateFlow<com.agroguide.rtk.ntrip.NtripStats> = _ntrip.asStateFlow()
    fun publishNtrip(stats: com.agroguide.rtk.ntrip.NtripStats) { _ntrip.value = stats }

    /** Shared per-session source events, for consumers beyond the pipeline (NTRIP GGA, UBX ACK UI). */
    @Volatile var sharedEvents: kotlinx.coroutines.flow.SharedFlow<SourceEvent>? = null
        private set

    /** Plane forced by an active job so old and new coverage share one ENU frame. */
    @Volatile private var pinnedPlane: com.agroguide.rtk.geo.TangentPlane? = null
    @Volatile private var activePipeline: PositionPipeline? = null

    /** Origin of the ENU frame currently in use (job-pinned or auto-anchored). */
    val activePlaneOrigin: Pair<Double, Double>?
        get() = (pinnedPlane ?: activePipeline?.plane)?.let { it.originLatDeg to it.originLonDeg }

    /** Pin (or clear with null) the tangent plane; applies to the live pipeline too. */
    fun pinPlane(plane: com.agroguide.rtk.geo.TangentPlane?) {
        pinnedPlane = plane
        if (plane != null) activePipeline?.plane = plane
    }

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
        sharedEvents = shared
        val sharedSource = object : PositionSource {
            override val name = source.name
            override val events = shared
        }
        val pipeline = PositionPipeline(sharedSource, pinnedPlane)
        activePipeline = pipeline
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
        activePipeline = null
        sharedEvents = null
        _sourceName.value = null
        _connection.value = SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.DISCONNECTED)
        _ntrip.value = com.agroguide.rtk.ntrip.NtripStats()
    }
}
