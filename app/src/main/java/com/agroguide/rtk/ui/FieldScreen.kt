package com.agroguide.rtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.data.ImplementSettings
import com.agroguide.rtk.data.ImplementSettingsStore
import com.agroguide.rtk.data.NtripProfile
import com.agroguide.rtk.service.GuidanceMode
import com.agroguide.rtk.service.GuidanceRepository
import com.agroguide.rtk.service.PositionRepository
import kotlinx.coroutines.launch

/** Primary guidance display: lightbar + field canvas + right-hand controls. */
@Composable
fun FieldScreen(
    pairedRtkDevices: List<Pair<String, String>>,
    activeNtripProfile: NtripProfile?,
    implementStore: ImplementSettingsStore,
    mockProvider: Boolean = false,
    basemap: com.agroguide.rtk.map.MapProvider = com.agroguide.rtk.map.MapProvider.NONE,
    basemapApiKey: String? = null,
    style: FieldStyle = FieldStyle(),
    fullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val vehicle by PositionRepository.vehicle.collectAsStateWithLifecycle()
    val result by GuidanceRepository.result.collectAsStateWithLifecycle()
    val mode by GuidanceRepository.mode.collectAsStateWithLifecycle()
    val line by GuidanceRepository.line.collectAsStateWithLifecycle()
    val pendingA by GuidanceRepository.pendingA.collectAsStateWithLifecycle()
    val recording by GuidanceRepository.recording.collectAsStateWithLifecycle()
    val trail by GuidanceRepository.trail.collectAsStateWithLifecycle()
    val implement by GuidanceRepository.implement.collectAsStateWithLifecycle()
    val implementSettings by implementStore.settings.collectAsStateWithLifecycle(initialValue = ImplementSettings())
    val tiles by com.agroguide.rtk.service.CoverageRepository.tiles.collectAsStateWithLifecycle()
    val coverageStats by com.agroguide.rtk.service.CoverageRepository.stats.collectAsStateWithLifecycle()
    val sectionStates by com.agroguide.rtk.service.CoverageRepository.sections.collectAsStateWithLifecycle()

    var showSource by remember { mutableStateOf(false) }
    var showImplement by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val activeJob by com.agroguide.rtk.service.JobManager.activeJob.collectAsStateWithLifecycle()
    val draftMode by ParcelDraft.mode.collectAsStateWithLifecycle()
    val draftPoints by ParcelDraft.points.collectAsStateWithLifecycle()
    var pendingRing by remember { mutableStateOf<List<com.agroguide.rtk.geo.EnuPoint>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // DRIVE draft: collect work points from the vehicle flow while active
    LaunchedEffect(draftMode) {
        if (draftMode == ParcelDraft.Mode.DRIVE) {
            PositionRepository.vehicle.collect { state ->
                if (state != null) {
                    ParcelDraft.addPoint(
                        implement.workPoint(com.agroguide.rtk.geo.EnuPoint(state.x, state.y), state.headingDeg)
                    )
                }
            }
        }
    }

    // active job's parcel ring in ENU (for the outline)
    val jobParcelRing by androidx.compose.runtime.produceState<List<com.agroguide.rtk.geo.EnuPoint>?>(
        initialValue = null, activeJob?.parcelId,
    ) {
        val parcelId = activeJob?.parcelId
        val plane = PositionRepository.activePlane
        value = if (parcelId == null || plane == null) null else {
            com.agroguide.rtk.data.db.AgroDb.get(context).parcels().byId(parcelId)?.let { p ->
                stringToRing(p.ringLonLat).map { (lon, lat) ->
                    val e = plane.toEnu(lat, lon)
                    com.agroguide.rtk.geo.EnuPoint(e.x, e.y)
                }
            }
        }
    }

    // The canvas with its overlays; shared by every layout.
    val canvasArea: @Composable (Modifier) -> Unit = { areaModifier ->
            Box(areaModifier) {
                FieldView(
                    vehicle = vehicle, line = line, passIndex = result?.passIndex,
                    pendingA = pendingA, trail = trail, implement = implement,
                    tiles = tiles,
                    parcelRing = jobParcelRing,
                    draftRing = draftPoints,
                    onTapWorld = if (draftMode == ParcelDraft.Mode.TAP) {
                        { p -> ParcelDraft.addPoint(p) }
                    } else null,
                    basemap = basemap,
                    basemapApiKey = basemapApiKey,
                    plane = PositionRepository.activePlane,
                    style = style,
                )
                // One row so the job banner (left) gives way to the badges
                // (right) and wraps instead of overlapping them in portrait.
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    activeJob?.let { job ->
                        val activeState = job.state == com.agroguide.rtk.service.JobManager.STATE_ACTIVE
                        Text(
                            job.name + "  ·  " + stringResource(
                                if (activeState) R.string.job_active else R.string.job_paused
                            ),
                            Modifier
                                .background(
                                    if (activeState) Color(0xCC2E7D32) else Color(0xCCF9A825),
                                    androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // In full screen the data panel is hidden: keep the essentials.
                    if (fullscreen) FullscreenStatus(coverageStats)
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SourceBadge()
                    NtripBadge()
                }
                }
                if (fullscreen) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { onFullscreenChange(false) },
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 76.dp)
                            .height(60.dp),
                    ) { Text(stringResource(R.string.show_data), fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                }
                if (draftMode != ParcelDraft.Mode.NONE) {
                    Row(
                        Modifier.align(Alignment.TopCenter).padding(8.dp)
                            .background(Color(0xCC004D60), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.parcel_draft_banner, draftPoints.size),
                            color = Color.White,
                        )
                        androidx.compose.material3.TextButton(onClick = {
                            val ring = ParcelDraft.close()
                            if (ring == null) {
                                android.widget.Toast.makeText(context, R.string.parcel_too_few, android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                pendingRing = ring
                            }
                        }) { Text(stringResource(R.string.parcel_close, draftPoints.size), color = Color.White) }
                        androidx.compose.material3.TextButton(onClick = { ParcelDraft.cancel() }) {
                            Text(stringResource(R.string.cancel), color = Color.White)
                        }
                    }
                }
                if (vehicle == null) {
                    Text(
                        stringResource(R.string.field_no_position),
                        Modifier.align(Alignment.Center),
                        color = style.onField,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                SectionBar(
                    states = sectionStates,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                )
            }
    }

    // The data/controls panel; to the right in landscape, below in portrait.
    val panel: @Composable (Modifier) -> Unit = { panelModifier ->
            Column(
                panelModifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (activeJob != null) {
                    ActionButton(stringResource(R.string.fullscreen)) { onFullscreenChange(true) }
                }
                StatusStrip()
                Text(
                    "${stringResource(R.string.label_pass)}: ${result?.passIndex ?: "—"}",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                CountersStrip(coverageStats)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip(R.string.mode_free, mode == GuidanceMode.FREE) { GuidanceRepository.setMode(GuidanceMode.FREE) }
                    ModeChip(R.string.mode_ab, mode == GuidanceMode.AB) { GuidanceRepository.setMode(GuidanceMode.AB) }
                    ModeChip(R.string.mode_aplus, mode == GuidanceMode.A_PLUS) { GuidanceRepository.setMode(GuidanceMode.A_PLUS) }
                    ModeChip(R.string.mode_curve, mode == GuidanceMode.CURVE) { GuidanceRepository.setMode(GuidanceMode.CURVE) }
                }
                when (mode) {
                    GuidanceMode.AB -> {
                        ActionButton(stringResource(R.string.mark_a), enabled = vehicle != null) {
                            vehicle?.let(GuidanceRepository::markA)
                        }
                        ActionButton(stringResource(R.string.mark_b), enabled = vehicle != null && pendingA != null) {
                            vehicle?.let(GuidanceRepository::markB)
                        }
                    }
                    GuidanceMode.A_PLUS -> ActionButton(stringResource(R.string.mark_aplus), enabled = vehicle != null) {
                        vehicle?.let(GuidanceRepository::markAPlus)
                    }
                    GuidanceMode.CURVE -> ActionButton(
                        stringResource(if (recording) R.string.curve_stop else R.string.curve_start),
                        enabled = vehicle != null,
                    ) {
                        if (recording) GuidanceRepository.stopCurve() else GuidanceRepository.startCurve()
                    }
                    GuidanceMode.FREE -> {}
                }
                if (line != null || pendingA != null) {
                    OutlinedActionButton(stringResource(R.string.clear_line)) { GuidanceRepository.clearLine() }
                }
                OutlinedActionButton(stringResource(R.string.implement_button)) { showImplement = true }
                OutlinedActionButton(stringResource(R.string.source_button)) { showSource = true }
                OutlinedActionButton(stringResource(R.string.coverage_reset)) { showResetConfirm = true }
            }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        val panelMaxHeight = maxHeight * 0.42f
        Column(Modifier.fillMaxSize()) {
            Lightbar(result?.xteM)
            when {
                fullscreen -> canvasArea(Modifier.fillMaxSize())
                portrait -> Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    canvasArea(Modifier.weight(1f).fillMaxWidth())
                    panel(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight))
                }
                else -> Row(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    canvasArea(Modifier.weight(1f))
                    panel(Modifier.width(272.dp))
                }
            }
        }
    }

    if (showSource) {
        SourceDialog(pairedRtkDevices, activeNtripProfile, mockProvider) { showSource = false }
    }
    pendingRing?.let { ring ->
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRing = null },
            title = { Text(stringResource(R.string.parcel_name)) },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val plane = PositionRepository.activePlane
                    val measured = com.agroguide.rtk.data.parcel.ParcelGeometry.measure(ring)
                    if (plane != null && measured != null) {
                        val lonLat = ring.map { p ->
                            val (lat, lon) = plane.toGeo(p.x, p.y)
                            lon to lat
                        }
                        val entity = com.agroguide.rtk.data.db.ParcelEntity(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name.ifBlank { "Parcela" },
                            createdAtMs = System.currentTimeMillis(),
                            ringLonLat = ringToString(lonLat),
                            areaM2 = measured.areaM2,
                            perimeterM = measured.perimeterM,
                        )
                        scope.launch { com.agroguide.rtk.data.db.AgroDb.get(context).parcels().insert(entity) }
                    }
                    pendingRing = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingRing = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showResetConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            text = { Text(stringResource(R.string.coverage_reset_confirm)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.agroguide.rtk.service.CoverageRepository.reset()
                    GuidanceRepository.clearTrail()
                    showResetConfirm = false
                }) { Text(stringResource(R.string.coverage_reset)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showImplement) {
        ImplementDialog(
            initial = implementSettings,
            onSave = { s ->
                scope.launch { implementStore.save(s) }
                GuidanceRepository.setImplement(s.toConfig())
                showImplement = false
            },
            onDismiss = { showImplement = false },
        )
    }
}

/**
 * Always-visible indicator of WHICH source is feeding the screen. Demo is
 * flagged in orange so a simulated tractor can never be mistaken for a real
 * receiver; Bluetooth shows the actual device name reported on connect.
 */
@Composable
private fun SourceBadge() {
    val modifier: Modifier = Modifier
    val source by PositionRepository.sourceName.collectAsStateWithLifecycle()
    val connection by PositionRepository.connection.collectAsStateWithLifecycle()
    val (label, color) = when (source) {
        null -> stringResource(R.string.source_badge_none) to Color(0xCC424242)
        "Demo" -> stringResource(R.string.source_badge_demo) to Color(0xCCEF6C00)
        "Android" -> stringResource(R.string.source_badge_android) to Color(0xCC1565C0)
        else -> {
            val name = connection.detail?.takeIf {
                connection.state == com.agroguide.rtk.device.SourceEvent.ConnectionState.State.CONNECTED
            }
            (name ?: stringResource(R.string.source_bt)) to
                if (name != null) Color(0xCC2E7D32) else Color(0xCCC62828)
        }
    }
    Text(
        label,
        modifier
            .background(color, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    )
}

/**
 * NTRIP status on the main screen, with the server's actual reason when it is
 * failing. Hidden when no NTRIP session is configured for the source.
 */
@Composable
private fun NtripBadge() {
    val enabled by PositionRepository.ntripEnabled.collectAsStateWithLifecycle()
    if (!enabled) return
    val stats by PositionRepository.ntrip.collectAsStateWithLifecycle()
    val gga by PositionRepository.lastGga.collectAsStateWithLifecycle()
    val failure = stats.failure
    val (text, color) = when {
        stats.connected && stats.bytesReceived > 0 ->
            stringResource(R.string.ntrip_ok, "%.1f".format(stats.bytesReceived / 1024.0)) to Color(0xCC2E7D32)
        failure != null -> ntripFailureText(failure, stats.lastError, hasGga = gga != null) to Color(0xCCC62828)
        else -> stringResource(R.string.ntrip_connecting) to Color(0xCCF9A825)
    }
    Text(
        text,
        Modifier
            .widthIn(max = 260.dp)
            .background(color, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
    )
}

/**
 * What stays visible in full-screen mode: fix quality, speed and treated area,
 * in one translucent pill so the field keeps the whole screen.
 */
@Composable
private fun FullscreenStatus(stats: com.agroguide.rtk.coverage.CoverageStats) {
    val vehicle by PositionRepository.vehicle.collectAsStateWithLifecycle()
    val quality = vehicle?.quality ?: com.agroguide.rtk.geo.GnssQuality.NO_FIX
    Row(
        Modifier
            .background(Color(0xCC14201A), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            qualityLabel(quality),
            Modifier
                .background(qualityColor(quality), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
        Text(
            "%.1f km/h · %.2f ha".format((vehicle?.speedMps ?: 0.0) * 3.6, stats.treatedM2 / 10_000),
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        )
    }
}

/** Compact quality chip + sats + speed, reusing FixPanel color/label mapping. */
@Composable
private fun StatusStrip() {
    val vehicle by PositionRepository.vehicle.collectAsStateWithLifecycle()
    val quality = vehicle?.quality ?: com.agroguide.rtk.geo.GnssQuality.NO_FIX
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .background(qualityColor(quality), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(qualityLabel(quality), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            vehicle?.let { "%d sat · %.1f km/h".format(it.satellites ?: 0, it.speedMps * 3.6) } ?: "—",
            color = Color.White, fontSize = 14.sp,
        )
    }
}

@Composable
private fun ModeChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(labelRes)) })
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun OutlinedActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Manual per-section toggles across the implement width (0 = leftmost). */
@Composable
private fun SectionBar(states: List<Boolean>, modifier: Modifier = Modifier) {
    val repo = com.agroguide.rtk.service.CoverageRepository
    Row(modifier.height(56.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val allOn = states.all { it }
        // Own dark pill: the bar floats over the field, whose background is
        // user-configurable (white by default), so plain white text vanishes.
        Box(
            Modifier
                .height(56.dp)
                .background(Color(0xFF263238), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .clickable { repo.setAllSections(!allOn) }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.sections_all), color = Color.White, fontWeight = FontWeight.SemiBold) }
        states.forEachIndexed { i, on ->
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .background(
                        if (on) Color(0xFF2E7D32) else Color(0xFF37474F),
                        androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    )
                    .clickable { repo.toggleSection(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text("${i + 1}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Live job counters: treated area, overlap area/%, distance. */
@Composable
private fun CountersStrip(stats: com.agroguide.rtk.coverage.CoverageStats) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CounterRow(
            stringResource(R.string.counters_treated),
            "%.2f %s".format(stats.treatedM2 / 10_000.0, stringResource(R.string.unit_ha)),
        )
        CounterRow(
            stringResource(R.string.counters_overlap),
            "%.2f %s (%.1f %%)".format(
                stats.overlapM2 / 10_000.0, stringResource(R.string.unit_ha), stats.overlapPercent,
            ),
        )
        CounterRow(
            stringResource(R.string.counters_distance),
            "%.2f %s".format(stats.distanceM / 1000.0, stringResource(R.string.unit_km)),
        )
    }
}

@Composable
private fun CounterRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 16.sp)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
