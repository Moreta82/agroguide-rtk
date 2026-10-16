package com.agroguide.rtk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.data.NtripProfile
import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.service.GuidanceService
import com.agroguide.rtk.service.PositionRepository

/**
 * Source picker dialog: demo / BT (with paired RTK receivers + NTRIP toggle) /
 * Android location, plus connection state and the start/stop button.
 */
@Composable
fun SourceDialog(
    pairedRtkDevices: List<Pair<String, String>>,   // name to MAC
    activeNtripProfile: NtripProfile?,
    mockProvider: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val connection by PositionRepository.connection.collectAsStateWithLifecycle()
    val active by PositionRepository.sourceName.collectAsStateWithLifecycle()
    val ntripStats by PositionRepository.ntrip.collectAsStateWithLifecycle()
    // Never preselect demo: use what is running, else the last explicit choice,
    // else Bluetooth when a receiver is paired.
    var mode by remember {
        mutableStateOf(
            when (PositionRepository.sourceName.value) {
                "BT-SPP" -> GuidanceService.MODE_BT
                "Android" -> GuidanceService.MODE_ANDROID
                "Demo" -> GuidanceService.MODE_DEMO
                else -> com.agroguide.rtk.service.LastSourceConfig.lastMode(context)
                    ?: if (pairedRtkDevices.isNotEmpty()) GuidanceService.MODE_BT
                    else GuidanceService.MODE_ANDROID
            }
        )
    }
    var btAddress by remember {
        mutableStateOf(
            com.agroguide.rtk.service.LastSourceConfig.lastBtAddress(context)
                ?.takeIf { addr -> pairedRtkDevices.any { it.second == addr } }
                ?: pairedRtkDevices.firstOrNull()?.second
        )
    }
    var ntripEnabled by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_button)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SourceOption(stringResource(R.string.source_demo), mode == GuidanceService.MODE_DEMO) {
                    mode = GuidanceService.MODE_DEMO
                }
                SourceOption(stringResource(R.string.source_bt), mode == GuidanceService.MODE_BT) {
                    mode = GuidanceService.MODE_BT
                }
                if (mode == GuidanceService.MODE_BT) {
                    if (pairedRtkDevices.isEmpty()) {
                        Text(stringResource(R.string.no_paired_rtk), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        pairedRtkDevices.forEach { (name, mac) ->
                            SourceOption(name, btAddress == mac) { btAddress = mac }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(stringResource(R.string.ntrip_corrections))
                            Text(
                                activeNtripProfile?.name ?: stringResource(R.string.ntrip_need_profile),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = ntripEnabled && activeNtripProfile != null,
                            onCheckedChange = { ntripEnabled = it },
                            enabled = activeNtripProfile != null,
                        )
                    }
                    if (active != null && activeNtripProfile != null && ntripEnabled) {
                        val failure = ntripStats.failure
                        val gga by PositionRepository.lastGga.collectAsStateWithLifecycle()
                        Text(
                            when {
                                ntripStats.connected ->
                                    stringResource(R.string.ntrip_connected) + " · ${ntripStats.bytesReceived / 1024} KB"
                                failure != null -> ntripFailureText(failure, ntripStats.lastError, gga != null)
                                else -> stringResource(R.string.ntrip_connecting)
                            },
                            color = if (ntripStats.connected) Color(0xFF2E7D32) else Color(0xFFC62828),
                        )
                    }
                }
                SourceOption(stringResource(R.string.source_android), mode == GuidanceService.MODE_ANDROID) {
                    mode = GuidanceService.MODE_ANDROID
                }
                ConnectionBanner(connection)
            }
        },
        confirmButton = {
            Button(
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
                        GuidanceService.start(context, mode, btAddress, ntrip, mockProvider)
                    } else {
                        GuidanceService.stop(context)
                    }
                },
                enabled = mode != GuidanceService.MODE_BT || btAddress != null,
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(if (active == null) R.string.btn_start else R.string.btn_stop))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SourceOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
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
