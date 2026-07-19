package com.agroguide.rtk.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.data.db.AgroDb
import com.agroguide.rtk.data.db.ParcelEntity
import com.agroguide.rtk.data.parcel.ParcelCodec
import kotlinx.coroutines.launch
import java.util.Locale

fun ringToString(ring: List<Pair<Double, Double>>): String =
    ring.joinToString(" ") { "${it.first},${it.second}" }

fun stringToRing(s: String): List<Pair<Double, Double>> =
    s.split(' ').mapNotNull { pair ->
        val parts = pair.split(',')
        if (parts.size != 2) return@mapNotNull null
        val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
        val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
        lon to lat
    }

/** Parcels tab: list/rename/delete/export, import via SAF, create via drive or taps. */
@Composable
fun ParcelsScreen(onImport: () -> Unit, onGoToField: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AgroDb.get(context) }
    val scope = rememberCoroutineScope()
    val parcels by db.parcels().all().collectAsStateWithLifecycle(initialValue = emptyList())
    var renaming by remember { mutableStateOf<ParcelEntity?>(null) }
    var deleting by remember { mutableStateOf<ParcelEntity?>(null) }
    var showNew by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (parcels.isEmpty()) {
            Text(stringResource(R.string.parcel_none), style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(parcels, key = { it.id }) { p ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                String.format(Locale.getDefault(), "%.2f ha · %.0f m", p.areaM2 / 10_000, p.perimeterM),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { renaming = p }) { Text(stringResource(R.string.parcel_rename)) }
                        TextButton(onClick = { deleting = p }) {
                            Text(stringResource(R.string.parcel_delete), color = Color(0xFFC62828))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            ExportFiles.writeAndShare(
                                context, "${p.name}.geojson", "application/geo+json",
                                ParcelCodec.toGeoJson(p.name, stringToRing(p.ringLonLat)).toByteArray(),
                            )
                        }) { Text(stringResource(R.string.parcel_export_geojson)) }
                        TextButton(onClick = {
                            ExportFiles.writeAndShare(
                                context, "${p.name}.kml", "application/vnd.google-earth.kml+xml",
                                ParcelCodec.toKml(p.name, stringToRing(p.ringLonLat)).toByteArray(),
                            )
                        }) { Text(stringResource(R.string.parcel_export_kml)) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showNew = true }, modifier = Modifier.weight(1f).height(56.dp)) {
                Text(stringResource(R.string.parcel_new))
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f).height(56.dp)) {
                Text(stringResource(R.string.parcel_import))
            }
        }
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text(stringResource(R.string.parcel_new)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (com.agroguide.rtk.service.PositionRepository.activePlane == null) {
                                Toast.makeText(context, R.string.parcel_need_position, Toast.LENGTH_LONG).show()
                            } else {
                                ParcelDraft.startDrive(); showNew = false; onGoToField()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(stringResource(R.string.parcel_new_drive)) }
                    Button(
                        onClick = {
                            if (com.agroguide.rtk.service.PositionRepository.activePlane == null) {
                                Toast.makeText(context, R.string.parcel_need_position, Toast.LENGTH_LONG).show()
                            } else {
                                ParcelDraft.startTap(); showNew = false; onGoToField()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(stringResource(R.string.parcel_new_tap)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showNew = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.parcel_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { db.parcels().rename(p.id, name.ifBlank { p.name }) }
                    renaming = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.parcel_delete_confirm, p.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { db.parcels().delete(p.id) }
                    deleting = null
                }) { Text(stringResource(R.string.parcel_delete), color = Color(0xFFC62828)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
