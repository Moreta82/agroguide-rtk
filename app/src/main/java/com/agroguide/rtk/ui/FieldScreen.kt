package com.agroguide.rtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

    var showSource by remember { mutableStateOf(false) }
    var showImplement by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Lightbar(result?.xteM)
        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                FieldView(
                    vehicle = vehicle, line = line, passIndex = result?.passIndex,
                    pendingA = pendingA, trail = trail, implement = implement,
                )
                if (vehicle == null) {
                    Text(
                        stringResource(R.string.field_no_position),
                        Modifier.align(Alignment.Center),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Column(
                Modifier.width(272.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusStrip()
                Text(
                    "${stringResource(R.string.label_pass)}: ${result?.passIndex ?: "—"}",
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
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
            }
        }
    }

    if (showSource) {
        SourceDialog(pairedRtkDevices, activeNtripProfile) { showSource = false }
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
