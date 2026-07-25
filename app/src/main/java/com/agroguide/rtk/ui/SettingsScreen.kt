package com.agroguide.rtk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.geo.tiles.WebMercator
import com.agroguide.rtk.map.MapProvider
import com.agroguide.rtk.map.TileCache
import com.agroguide.rtk.service.PositionRepository
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agroguide.rtk.R
import com.agroguide.rtk.data.AppSettings
import com.agroguide.rtk.data.AppSettingsStore
import com.agroguide.rtk.data.ThemeMode
import com.agroguide.rtk.device.BatteryVendors
import kotlinx.coroutines.launch

fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun requestBatteryExemption(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
fun vendorHint(): String? = when (BatteryVendors.adviceFor(Build.MANUFACTURER)) {
    BatteryVendors.VendorAdvice.MIUI -> stringResource(R.string.battery_hint_miui)
    BatteryVendors.VendorAdvice.EMUI -> stringResource(R.string.battery_hint_emui)
    BatteryVendors.VendorAdvice.ONE_UI -> stringResource(R.string.battery_hint_oneui)
    null -> null
}

/** Ajustes tab: theme, keep-screen-on, mock output, battery exemption, version. */
@Composable
fun SettingsScreen(settings: AppSettings, store: AppSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exempt = remember { isBatteryExempt(context) }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ThemeOption(R.string.theme_auto, settings.themeMode == ThemeMode.AUTO) {
                scope.launch { store.update { it.copy(theme = ThemeMode.AUTO.name) } }
            }
            ThemeOption(R.string.theme_day, settings.themeMode == ThemeMode.DAY) {
                scope.launch { store.update { it.copy(theme = ThemeMode.DAY.name) } }
            }
            ThemeOption(R.string.theme_night, settings.themeMode == ThemeMode.NIGHT) {
                scope.launch { store.update { it.copy(theme = ThemeMode.NIGHT.name) } }
            }
        }

        SettingSwitch(stringResource(R.string.setting_keep_screen), settings.keepScreenOn) { on ->
            scope.launch { store.update { it.copy(keepScreenOn = on) } }
        }

        SettingSwitch(stringResource(R.string.setting_mock), settings.mockProvider) { on ->
            scope.launch { store.update { it.copy(mockProvider = on) } }
        }
        Text(stringResource(R.string.setting_mock_hint), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.basemap_label), style = MaterialTheme.typography.titleMedium)
        BasemapOption(R.string.basemap_none, MapProvider.NONE, settings.basemap) { p ->
            scope.launch { store.update { it.copy(basemap = p.id) } }
        }
        BasemapOption(R.string.basemap_osm, MapProvider.OSM, settings.basemap) { p ->
            scope.launch { store.update { it.copy(basemap = p.id) } }
        }
        BasemapOption(R.string.basemap_esri, MapProvider.ESRI_SATELLITE, settings.basemap) { p ->
            scope.launch { store.update { it.copy(basemap = p.id) } }
        }
        BasemapOption(R.string.basemap_google, MapProvider.GOOGLE_SATELLITE, settings.basemap) { p ->
            scope.launch { store.update { it.copy(basemap = p.id) } }
        }
        if (MapProvider.fromId(settings.basemap) == MapProvider.GOOGLE_SATELLITE) {
            var key by remember(settings.googleMapsApiKey) { mutableStateOf(settings.googleMapsApiKey) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(stringResource(R.string.basemap_google_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { scope.launch { store.update { it.copy(googleMapsApiKey = key.trim()) } } },
                modifier = Modifier.height(48.dp),
            ) { Text(stringResource(R.string.save)) }
            Text(stringResource(R.string.basemap_google_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (MapProvider.fromId(settings.basemap) != MapProvider.NONE) {
            Text(stringResource(R.string.basemap_hint), style = MaterialTheme.typography.bodySmall)
            val downloading by TileCache.downloading.collectAsStateWithLifecycle()
            if (downloading > 0) {
                Text(stringResource(R.string.basemap_downloading, downloading))
            }
            var cacheMb by remember { mutableStateOf(0L) }
            LaunchedEffect(downloading) {
                cacheMb = TileCache.cacheSizeBytes(context) / (1024 * 1024)
            }
            Text(stringResource(R.string.basemap_cache, cacheMb.toString()))
            Button(
                onClick = {
                    val plane = PositionRepository.activePlane
                    val v = PositionRepository.vehicle.value
                    if (plane == null || v == null) {
                        Toast.makeText(context, R.string.basemap_need_position, Toast.LENGTH_LONG).show()
                    } else {
                        // ~1 km around the tractor, at the three most useful zooms
                        val provider = MapProvider.fromId(settings.basemap)
                        val half = 1000.0
                        val c = listOf(
                            plane.toGeo(v.x - half, v.y - half), plane.toGeo(v.x + half, v.y + half),
                        )
                        val minLat = c.minOf { it.first }; val maxLat = c.maxOf { it.first }
                        val minLon = c.minOf { it.second }; val maxLon = c.maxOf { it.second }
                        val tiles = (16..provider.maxZoom.coerceAtMost(18)).flatMap { z ->
                            WebMercator.tilesForBounds(minLat, maxLat, minLon, maxLon, z, maxTiles = 200)
                        }
                        TileCache.prefetch(context, provider, tiles, settings.googleMapsApiKey.ifBlank { null })
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.basemap_download)) }
            OutlinedButton(
                onClick = { TileCache.clear(context) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.basemap_clear)) }
        }

        Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
        if (exempt) {
            Text(stringResource(R.string.battery_ok), color = Color(0xFF2E7D32))
        } else {
            Text(stringResource(R.string.battery_warn), color = Color(0xFFC62828))
            vendorHint()?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = { requestBatteryExemption(context) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.battery_request)) }
        }

        Text(
            stringResource(R.string.app_version, com.agroguide.rtk.BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BasemapOption(
    labelRes: Int,
    provider: MapProvider,
    currentId: String,
    onSelect: (MapProvider) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = MapProvider.fromId(currentId) == provider,
            onClick = { onSelect(provider) },
        )
        Text(stringResource(labelRes))
    }
}

@Composable
private fun ThemeOption(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(stringResource(labelRes))
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
