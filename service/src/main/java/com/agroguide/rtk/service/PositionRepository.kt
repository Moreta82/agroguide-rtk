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

    /**
     * Every real receiver fix, in order and never conflated. [vehicle] is a
     * StateFlow shared with the 15 Hz projected states, so a slow collector can
     * miss real fixes there; coverage and the job journal must not.
     */
    private val _fixes = kotlinx.coroutines.flow.MutableSharedFlow<VehicleState>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val fixes: kotlinx.coroutines.flow.SharedFlow<VehicleState> = _fixes

    private val _connection = MutableStateFlow(
        SourceEvent.ConnectionState(SourceEvent.ConnectionState.State.DISCONNECTED)
    )
    val connection: StateFlow<SourceEvent.ConnectionState> = _connection.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    private val _ntrip = MutableStateFlow(com.agroguide.rtk.ntrip.NtripStats())
    val ntrip: StateFlow<com.agroguide.rtk.ntrip.NtripStats> = _ntrip.asStateFlow()
    fun publishNtrip(stats: com.agroguide.rtk.ntrip.NtripStats) { _ntrip.value = stats }

    /** True while an NTRIP session is running for the current source. */
    private val _ntripEnabled = MutableStateFlow(false)
    val ntripEnabled: StateFlow<Boolean> = _ntripEnabled.asStateFlow()
    fun setNtripEnabled(on: Boolean) { _ntripEnabled.value = on }

    /**
     * Latest raw GGA sentence from the receiver. VRS casters need it, and the
     * profile editor's connection test sends it so the test is realistic.
     */
    private val _lastGga = MutableStateFlow<String?>(null)
    val lastGga: StateFlow<String?> = _lastGga.asStateFlow()

    /** Shared per-session source events, for consumers beyond the pipeline (NTRIP GGA, UBX ACK UI). */
    @Volatile var sharedEvents: kotlinx.coroutines.flow.SharedFlow<SourceEvent>? = null
        private set

    /** Plane forced by an active job so old and new coverage share one ENU frame. */
    @Volatile private var pinnedPlane: com.agroguide.rtk.geo.TangentPlane? = null
    @Volatile private var activePipeline: PositionPipeline? = null

    /** ENU frame currently in use (job-pinned or auto-anchored). */
    val activePlane: com.agroguide.rtk.geo.TangentPlane?
        get() = pinnedPlane ?: activePipeline?.plane

    /** Origin of the ENU frame currently in use (job-pinned or auto-anchored). */
    val activePlaneOrigin: Pair<Double, Double>?
        get() = activePlane?.let { it.originLatDeg to it.originLonDeg }

    /** Pin (or clear with null) the tangent plane; applies to the live pipeline too. */
    fun pinPlane(plane: com.agroguide.rtk.geo.TangentPlane?) {
        pinnedPlane = plane
        if (plane != null) activePipeline?.plane = plane
    }

    private var scope: CoroutineScope? = null
    // 30 Hz: at 15 Hz the drawn tractor and paint advanced in visible ~20 cm
    // steps at work speed, which reads as the paint lagging.
    private const val RENDER_HZ = 30

    /**
     * Latency compensation for the displayed position. Fixes reach the app
     * 100–200 ms after the receiver measured them (solution + Bluetooth +
     * Android), so the tractor, the paint and the lightbar are drawn where the
     * implement was, not where it is. -1 = automatic (measured from the NMEA
     * time stamps), 0 = off, otherwise a fixed lead in ms. Only the display and
     * guidance use it; coverage and the job journal keep the true fixes.
     */
    @Volatile var latencyCompensationMs: Int = -1

    private val _measuredLatencyMs = MutableStateFlow(0L)
    /** Last measured fix latency (0 = unknown / not measurable for this source). */
    val measuredLatencyMs: StateFlow<Long> = _measuredLatencyMs.asStateFlow()

    private fun leadMs(pipeline: PositionPipeline): Long = when (val mode = latencyCompensationMs) {
        -1 -> pipeline.latencyMs
        else -> mode.toLong().coerceIn(0, 1000)
    }

    /**
     * Called by GuidanceService when a source is (re)selected. The source flow
     * is shared so the underlying transport (e.g. a BT socket) is opened once
     * even though both the pipeline and the connection monitor collect it.
     */
    fun attach(source: PositionSource, nowMs: () -> Long) {
        detach()
        // Safety net: a source that throws (permission revoked, vendor BT stack
        // quirk) must degrade to an ERROR banner, never take down the process.
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            android.util.Log.e("PositionRepository", "source failed", e)
            _connection.value = SourceEvent.ConnectionState(
                SourceEvent.ConnectionState.State.ERROR,
                e.message ?: e.javaClass.simpleName,
            )
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        scope = s
        _sourceName.value = source.name
        val shared = source.events.shareIn(s, SharingStarted.Eagerly)
        sharedEvents = shared
        val sharedSource = object : PositionSource {
            override val name = source.name
            override val events = shared
        }
        val pipeline = PositionPipeline(sharedSource, pinnedPlane) { monotonicMs ->
            // local UTC at a given elapsedRealtime instant
            System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - monotonicMs)
        }
        activePipeline = pipeline
        val projector = CvProjector()
        s.launch {
            shared.filterIsInstance<SourceEvent.ConnectionState>().collect { _connection.value = it }
        }
        s.launch {
            shared.filterIsInstance<SourceEvent.Nmea>().collect {
                // "$GNGGA,...": talker id is 2 chars, sentence type at index 3
                if (it.line.length > 6 && it.line.regionMatches(3, "GGA", 0, 3)) _lastGga.value = it.line
            }
        }
        s.launch {
            pipeline.states.collect {
                projector.onFix(it)
                _measuredLatencyMs.value = pipeline.latencyMs
                // Display the fix where the vehicle is now, not where it was
                // when the receiver measured it.
                _vehicle.value = projector.projectAt(nowMs() + leadMs(pipeline)) ?: it
                if (!it.projected) _fixes.tryEmit(it)
            }
        }
        s.launch {
            while (isActive) {
                delay(1000L / RENDER_HZ)
                projector.projectAt(nowMs() + leadMs(pipeline))?.let { if (it.projected) _vehicle.value = it }
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
        _ntripEnabled.value = false
        _lastGga.value = null
    }
}
