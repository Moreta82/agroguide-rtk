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
    var fetchError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) stringResource(R.string.ntrip_new_profile) else name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ntrip_name)) }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.ntrip_host)) }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.ntrip_port)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mount, { mount = it }, label = { Text(stringResource(R.string.ntrip_mount)) }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            fetchError = null; browsing = true; mounts = null
                            val creds = NtripCredentials(host.trim(), port.toIntOrNull() ?: 2101, user, pass)
                            val version = if (v2) NtripVersion.V2 else NtripVersion.V1
                            scope.launch {
                                runCatching { NtripClient.fetchSourceTable(creds, version) }
                                    .onSuccess { mounts = it }
                                    .onFailure { fetchError = it.message ?: it.javaClass.simpleName }
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
                fetchError?.let { Text(stringResource(R.string.ntrip_fetch_error, it), color = Color(0xFFC62828)) }
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
                    onSave(
                        initial.copy(
                            name = name.ifBlank { host }, host = host.trim(),
                            port = port.toIntOrNull() ?: 2101, mountPoint = mount.trim(),
                            username = user, password = pass, ntripV2 = v2,
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
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
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
