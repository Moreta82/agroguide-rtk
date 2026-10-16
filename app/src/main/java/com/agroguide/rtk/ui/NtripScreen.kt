package com.agroguide.rtk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.data.NtripProfile
import com.agroguide.rtk.data.NtripProfileStore
import com.agroguide.rtk.data.NtripSettings
import com.agroguide.rtk.ntrip.MountPoint
import com.agroguide.rtk.ntrip.NtripClient
import com.agroguide.rtk.ntrip.NtripCredentials
import com.agroguide.rtk.ntrip.NtripVersion
import kotlinx.coroutines.launch
import java.util.UUID

/** NTRIP tab: profile list (radio = active), editor dialog with sourcetable browser. */
@Composable
fun NtripScreen(settings: NtripSettings, store: NtripProfileStore) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<NtripProfile?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings.profiles.isEmpty()) {
            Text(stringResource(R.string.ntrip_no_profiles), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(settings.profiles, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().height(56.dp)
                        .clickable { scope.launch { store.update { it.copy(activeProfileId = p.id) } } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = p.id == settings.activeProfileId,
                        onClick = { scope.launch { store.update { it.copy(activeProfileId = p.id) } } },
                    )
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${p.host}:${p.port}/${p.mountPoint}" + if (p.ntripV2) " · v2" else " · v1",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { editing = p }) { Text(stringResource(R.string.ntrip_edit)) }
                }
            }
        }
        Button(
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(R.string.ntrip_new_profile)) }
    }

    if (creating) {
        ProfileDialog(
            initial = NtripProfile(id = UUID.randomUUID().toString(), name = "", host = ""),
            onSave = { p ->
                scope.launch {
                    store.update { it.copy(profiles = it.profiles + p, activeProfileId = it.activeProfileId ?: p.id) }
                }
                creating = false
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
    editing?.let { profile ->
        ProfileDialog(
            initial = profile,
            onSave = { p ->
                scope.launch { store.update { s -> s.copy(profiles = s.profiles.map { if (it.id == p.id) p else it }) } }
                editing = null
            },
            onDelete = {
                scope.launch {
                    store.update { s ->
                        s.copy(
                            profiles = s.profiles.filterNot { it.id == profile.id },
                            activeProfileId = s.activeProfileId.takeIf { it != profile.id },
                        )
                    }
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ProfileDialog(
    initial: NtripProfile,
    onSave: (NtripProfile) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var mount by remember { mutableStateOf(initial.mountPoint) }
    var user by remember { mutableStateOf(initial.username) }
    var pass by remember { mutableStateOf(initial.password) }
    var v2 by remember { mutableStateOf(initial.ntripV2) }
    var gga by remember { mutableStateOf(initial.uploadGga) }
    var ggaInt by remember { mutableStateOf(initial.ggaIntervalS.toString()) }
    var browsing by remember { mutableStateOf(false) }
    var mounts by remember { mutableStateOf<List<MountPoint>?>(null) }
    var fetchError by remember { mutableStateOf<com.agroguide.rtk.ntrip.NtripFailure?>(null) }
    var fetchDetail by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<com.agroguide.rtk.ntrip.NtripProbeResult?>(null) }
    val vehicle by com.agroguide.rtk.service.PositionRepository.vehicle
        .collectAsStateWithLifecycle()

    // Cap the scrollable body to a fraction of the actual screen so every field
    // is reachable on short/landscape displays; a fixed 420 dp cut the last
    // fields off and left the scroll unable to reach them.
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = screenHeight * 0.92f),
        title = { Text(if (onDelete == null) stringResource(R.string.ntrip_new_profile) else name) },
        text = {
            Column(
                Modifier
                    .heightIn(max = screenHeight * 0.6f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ntrip_name)) }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.ntrip_host)) }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.ntrip_port)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mount, { mount = it }, label = { Text(stringResource(R.string.ntrip_mount)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            fetchError = null; fetchDetail = null; browsing = true; mounts = null
                            val creds = NtripCredentials(host.trim(), port.toIntOrNull() ?: 2101, user, pass)
                            val version = if (v2) NtripVersion.V2 else NtripVersion.V1
                            scope.launch {
                                runCatching { NtripClient.fetchSourceTable(creds, version) }
                                    .onSuccess { list ->
                                        // Nearest first: picking a distant base is the
                                        // classic reason RTK never reaches FIXED.
                                        val v = vehicle
                                        mounts = if (v == null) list else list.sortedBy {
                                            it.distanceKm(v.latDeg, v.lonDeg) ?: Double.MAX_VALUE
                                        }
                                    }
                                    .onFailure {
                                        fetchError = com.agroguide.rtk.ntrip.ntripFailureOf(it)
                                        fetchDetail = it.message
                                    }
                            }
                        },
                        enabled = host.isNotBlank(),
                    ) { Text(stringResource(R.string.ntrip_browse)) }
                }
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.ntrip_user)) }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text(stringResource(R.string.ntrip_pass)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                LabeledSwitch(stringResource(R.string.ntrip_v2), v2) { v2 = it }
                LabeledSwitch(stringResource(R.string.ntrip_send_gga), gga) { gga = it }
                OutlinedTextField(ggaInt, { ggaInt = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.ntrip_gga_interval)) }, singleLine = true)
                fetchError?.let {
                    Text(
                        stringResource(R.string.ntrip_fetch_error, ntripFailureText(it, fetchDetail)),
                        color = Color(0xFFC62828),
                    )
                }
                // Real end-to-end check of this exact profile: tells a wrong
                // password from a wrong mountpoint from no coverage.
                Button(
                    onClick = {
                        testing = true; testResult = null
                        val creds = NtripCredentials(host.trim(), port.toIntOrNull() ?: 2101, user, pass)
                        val version = if (v2) NtripVersion.V2 else NtripVersion.V1
                        val ggaLine = if (gga) com.agroguide.rtk.service.PositionRepository.lastGga.value else null
                        scope.launch {
                            testResult = NtripClient.probe(creds, mount, version, ggaLine)
                            testing = false
                        }
                    },
                    enabled = host.isNotBlank() && mount.isNotBlank() && !testing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(if (testing) R.string.ntrip_testing else R.string.ntrip_test)) }
                testResult?.let { r ->
                    val f = r.failure
                    when {
                        f == null -> Text(
                            stringResource(R.string.ntrip_test_ok, "%.1f".format(r.bytes / 1024.0)),
                            color = Color(0xFF2E7D32),
                        )
                        f == com.agroguide.rtk.ntrip.NtripFailure.NO_DATA && !r.sentGga -> Text(
                            stringResource(R.string.ntrip_test_ok_nogga),
                            color = Color(0xFFB07800),
                        )
                        else -> Text(
                            stringResource(R.string.ntrip_test_fail, ntripFailureText(f, r.detail, r.sentGga)),
                            color = Color(0xFFC62828),
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.ntrip_delete), color = Color(0xFFC62828))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Store what the caster needs, not what was pasted
                    // ("http://host:2101/" → host + port; "/MOUNT" → MOUNT).
                    val ep = com.agroguide.rtk.ntrip.NtripEndpoint.parse(host, port.toIntOrNull() ?: 2101)
                    onSave(
                        initial.copy(
                            name = name.ifBlank { ep.host }, host = ep.host,
                            port = ep.port,
                            mountPoint = com.agroguide.rtk.ntrip.NtripEndpoint.normalizeMount(mount),
                            username = user.trim(), password = pass, ntripV2 = v2,
                            uploadGga = gga, ggaIntervalS = ggaInt.toIntOrNull() ?: 10,
                        )
                    )
                },
                enabled = host.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (browsing && mounts != null) {
        AlertDialog(
            onDismissRequest = { browsing = false },
            title = { Text(stringResource(R.string.ntrip_browse)) },
            text = {
                LazyColumn(Modifier.heightIn(max = screenHeight * 0.6f)) {
                    items(mounts!!, key = { it.mountPoint }) { m ->
                        Row(
                            Modifier.fillMaxWidth().height(56.dp)
                                .clickable { mount = m.mountPoint; browsing = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.mountPoint, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOf(m.identifier, m.format, m.country).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val v = vehicle
                            val km = v?.let { m.distanceKm(it.latDeg, it.lonDeg) }
                            Text(
                                when {
                                    km != null -> stringResource(R.string.ntrip_km, "%.0f".format(km))
                                    m.latDeg == 0.0 && m.lonDeg == 0.0 -> stringResource(R.string.ntrip_network)
                                    else -> ""
                                },
                                Modifier.padding(end = 8.dp),
                                color = when {
                                    km == null -> MaterialTheme.colorScheme.onSurface
                                    km <= 30 -> Color(0xFF2E7D32)
                                    km <= 60 -> Color(0xFFB07800)
                                    else -> Color(0xFFC62828)
                                },
                            )
                            if (m.requiresGga) Text(stringResource(R.string.ntrip_badge_gga), Modifier.padding(end = 4.dp))
                            if (m.requiresAuth) Text(stringResource(R.string.ntrip_badge_auth))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { browsing = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.ntrip_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.ntrip_delete), color = Color(0xFFC62828))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
