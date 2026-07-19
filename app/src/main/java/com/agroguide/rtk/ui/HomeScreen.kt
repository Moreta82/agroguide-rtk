package com.agroguide.rtk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.data.NtripProfile
import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.service.GuidanceService
import com.agroguide.rtk.service.PositionRepository

/**
 * Guidance home: pick source (demo / BT / Android location), toggle NTRIP
 * corrections (BT mode), start/stop the foreground service, watch the fix
 * panel. Field canvas arrives in phase 3.
 */
@Composable
fun HomeScreen(
    pairedRtkDevices: List<Pair<String, String>>,  // name to MAC
    activeNtripProfile: NtripProfile?,
) {
    val context = LocalContext.current
    val vehicle by PositionRepository.vehicle.collectAsStateWithLifecycle()
    val connection by PositionRepository.connection.collectAsStateWithLifecycle()
    val active by PositionRepository.sourceName.collectAsStateWithLifecycle()
    val ntripStats by PositionRepository.ntrip.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(GuidanceService.MODE_DEMO) }
    var btAddress by remember { mutableStateOf(pairedRtkDevices.firstOrNull()?.second) }
    var ntripEnabled by remember { mutableStateOf(true) }

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceOption(stringResource(R.string.source_demo), mode == GuidanceService.MODE_DEMO) {
                mode = GuidanceService.MODE_DEMO
            }
            SourceOption(stringResource(R.string.source_bt), mode == GuidanceService.MODE_BT) {
                mode = GuidanceService.MODE_BT
            }
            if (mode == GuidanceService.MODE_BT) {
                if (pairedRtkDevices.isEmpty()) {
                    Text(stringResource(R.string.no_paired_rtk))
                } else {
                    pairedRtkDevices.forEach { (name, mac) ->
                        SourceOption(name, btAddress == mac) { btAddress = mac }
                    }
                }
            }
            SourceOption(stringResource(R.string.source_android), mode == GuidanceService.MODE_ANDROID) {
                mode = GuidanceService.MODE_ANDROID
            }
            if (mode == GuidanceService.MODE_BT) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(stringResource(R.string.ntrip_corrections), style = MaterialTheme.typography.titleMedium)
                        if (activeNtripProfile == null) {
                            Text(stringResource(R.string.ntrip_need_profile), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(activeNtripProfile.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(
                        checked = ntripEnabled && activeNtripProfile != null,
                        onCheckedChange = { ntripEnabled = it },
                        enabled = activeNtripProfile != null,
                    )
                }
                if (active != null && activeNtripProfile != null && ntripEnabled) {
                    val ntripText = if (ntripStats.connected) {
                        stringResource(R.string.ntrip_connected) + " · ${ntripStats.bytesReceived / 1024} KB"
                    } else {
                        stringResource(R.string.ntrip_disconnected)
                    }
                    Text(
                        ntripText,
                        color = if (ntripStats.connected) Color(0xFF2E7D32) else Color(0xFFC62828),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            ConnectionBanner(connection)
            Button(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                onClick = {
                    if (active == null) {
                        val ntrip = activeNtripProfile?.takeIf {
                            ntripEnabled && mode == GuidanceService.MODE_BT
                        }?.let {
                            GuidanceService.NtripStartConfig(
                                host = it.host, port = it.port, username = it.username,
                                password = it.password, mountPoint = it.mountPoint,
                                v2 = it.ntripV2, uploadGga = it.uploadGga, ggaIntervalS = it.ggaIntervalS,
                            )
                        }
                        GuidanceService.start(context, mode, btAddress, ntrip)
                    } else {
                        GuidanceService.stop(context)
                    }
                },
                enabled = mode != GuidanceService.MODE_BT || btAddress != null,
            ) {
                Text(
                    stringResource(if (active == null) R.string.btn_start else R.string.btn_stop),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        FixPanel(vehicle, Modifier.weight(1f))
    }
}

@Composable
private fun SourceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ConnectionBanner(c: SourceEvent.ConnectionState) {
    val text = when (c.state) {
        SourceEvent.ConnectionState.State.CONNECTING -> stringResource(R.string.state_connecting)
        SourceEvent.ConnectionState.State.CONNECTED -> stringResource(R.string.state_connected)
        SourceEvent.ConnectionState.State.DISCONNECTED -> stringResource(R.string.state_disconnected)
        SourceEvent.ConnectionState.State.ERROR -> stringResource(R.string.state_error) +
            (c.detail?.let { ": $it" } ?: "")
    }
    Text(text, style = MaterialTheme.typography.bodyLarge)
}
