package com.agroguide.rtk

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.agroguide.rtk.data.ImplementSettingsStore
import com.agroguide.rtk.data.NtripProfileStore
import com.agroguide.rtk.data.NtripSettings
import com.agroguide.rtk.service.GuidanceRepository
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.agroguide.rtk.data.db.AgroDb
import com.agroguide.rtk.data.db.ParcelEntity
import com.agroguide.rtk.data.parcel.ParcelCodec
import com.agroguide.rtk.data.parcel.ParcelGeometry
import com.agroguide.rtk.geo.TangentPlane
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.agroguide.rtk.data.AppSettings
import com.agroguide.rtk.data.AppSettingsStore
import com.agroguide.rtk.data.ScreenOrientation
import com.agroguide.rtk.data.ThemeMode
import com.agroguide.rtk.ui.FieldStyle
import com.agroguide.rtk.ui.FieldScreen
import com.agroguide.rtk.ui.JobsScreen
import com.agroguide.rtk.ui.NtripScreen
import com.agroguide.rtk.ui.ParcelsScreen
import com.agroguide.rtk.ui.ReceiverScreen
import com.agroguide.rtk.ui.SettingsScreen
import com.agroguide.rtk.ui.isBatteryExempt
import com.agroguide.rtk.ui.requestBatteryExemption
import com.agroguide.rtk.ui.ringToString
import com.agroguide.rtk.ui.theme.AgroGuideTheme
import com.agroguide.rtk.ui.vendorHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Bluetooth listing must not depend on unrelated grants (notifications).
            permissionsGranted = hasBluetoothPermission()
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importParcels(uri)
        }

    /** Parse a GeoJSON or KML document and insert every polygon as a parcel. */
    private fun importParcels(uri: android.net.Uri) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: return@runCatching 0
                    val imported = ParcelCodec.fromGeoJson(text).ifEmpty { ParcelCodec.fromKml(text) }
                    val dao = AgroDb.get(applicationContext).parcels()
                    var inserted = 0
                    for (p in imported) {
                        // measure on a local plane anchored at the first vertex
                        val plane = TangentPlane(p.ringLonLat[0].second, p.ringLonLat[0].first)
                        val ringEnu = p.ringLonLat.map { (lon, lat) -> plane.toEnu(lat, lon) }
                        val measured = ParcelGeometry.measure(ringEnu) ?: continue
                        dao.insert(
                            ParcelEntity(
                                id = UUID.randomUUID().toString(), name = p.name,
                                createdAtMs = System.currentTimeMillis(),
                                ringLonLat = ringToString(p.ringLonLat),
                                areaM2 = measured.areaM2, perimeterM = measured.perimeterM,
                            )
                        )
                        inserted++
                    }
                    inserted
                }.getOrDefault(0)
            }
            if (count > 0) {
                Toast.makeText(this@MainActivity, getString(R.string.parcel_import_ok, count), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.parcel_import_fail, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNeededPermissions()
        val store = NtripProfileStore(applicationContext)
        val implementStore = ImplementSettingsStore(applicationContext)
        val appStore = AppSettingsStore(applicationContext)
        setContent {
            val appSettings by appStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val night: Boolean? = when (appSettings.themeMode) {
                ThemeMode.AUTO -> null
                ThemeMode.DAY -> false
                ThemeMode.NIGHT -> true
            }
            LaunchedEffect(appSettings.keepScreenOn) {
                if (appSettings.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            LaunchedEffect(appSettings.screenOrientation) {
                requestedOrientation = when (appSettings.screenOrientation) {
                    ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
            }
            LaunchedEffect(appSettings.pauseWhenStopped) {
                com.agroguide.rtk.service.CoverageRepository.pauseWhenStopped = appSettings.pauseWhenStopped
            }
            LaunchedEffect(appSettings.latencyCompensationMs) {
                com.agroguide.rtk.service.PositionRepository.latencyCompensationMs = appSettings.latencyCompensationMs
            }
            AgroGuideTheme(night = night) {
                val settings by store.settings.collectAsStateWithLifecycle(initialValue = NtripSettings())
                LaunchedEffect(Unit) {
                    implementStore.settings.collect {
                        GuidanceRepository.setImplement(it.toConfig())
                        com.agroguide.rtk.service.CoverageRepository.configure(it.widthM, it.sections)
                    }
                }
                val lineStore = remember { com.agroguide.rtk.data.GuidanceLineStore(applicationContext) }
                LaunchedEffect(Unit) {
                    // restore once, then persist every change (including clearing)
                    if (GuidanceRepository.line.value == null) {
                        lineStore.load()?.let { GuidanceRepository.restoreLine(it) }
                    }
                    var first = true
                    GuidanceRepository.line.collect { line ->
                        if (first) { first = false } else { lineStore.save(line) }
                    }
                }
                var tab by remember { mutableIntStateOf(0) }
                // Starting a job jumps to the field in full screen; "Datos"
                // brings the tabs and the panel back while the job runs.
                val activeJob by com.agroguide.rtk.service.JobManager.activeJob.collectAsStateWithLifecycle()
                var fullscreen by remember { mutableStateOf(false) }
                LaunchedEffect(activeJob?.id) {
                    if (activeJob != null) { tab = 0; fullscreen = true } else fullscreen = false
                }
                val immersive = fullscreen && tab == 0
                LaunchedEffect(immersive) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    if (immersive) {
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.navigationBars())
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    if (!immersive) {
                        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }) { TabLabel(R.string.tab_guidance) }
                            Tab(selected = tab == 1, onClick = { tab = 1 }) { TabLabel(R.string.tab_parcels) }
                            Tab(selected = tab == 2, onClick = { tab = 2 }) { TabLabel(R.string.tab_jobs) }
                            Tab(selected = tab == 3, onClick = { tab = 3 }) { TabLabel(R.string.tab_ntrip) }
                            Tab(selected = tab == 4, onClick = { tab = 4 }) { TabLabel(R.string.tab_receiver) }
                            Tab(selected = tab == 5, onClick = { tab = 5 }) { TabLabel(R.string.tab_settings) }
                        }
                    }
                    when (tab) {
                        0 -> FieldScreen(
                            pairedRtkDevices = if (permissionsGranted) pairedRtkDevices() else emptyList(),
                            activeNtripProfile = settings.activeProfile,
                            implementStore = implementStore,
                            mockProvider = appSettings.mockProvider,
                            basemap = com.agroguide.rtk.map.MapProvider.fromId(appSettings.basemap),
                            basemapApiKey = appSettings.googleMapsApiKey.ifBlank { null },
                            style = FieldStyle.from(appSettings),
                            fullscreen = immersive,
                            onFullscreenChange = { fullscreen = it },
                        )
                        1 -> ParcelsScreen(
                            onImport = { importLauncher.launch(arrayOf("*/*")) },
                            onGoToField = { tab = 0 },
                        )
                        2 -> JobsScreen()
                        3 -> NtripScreen(settings, store)
                        4 -> ReceiverScreen()
                        else -> SettingsScreen(appSettings, appStore)
                    }
                }

                // first-run onboarding for aggressive battery managers
                var showBattery by remember { mutableStateOf(false) }
                LaunchedEffect(appSettings.batteryDialogShown) {
                    showBattery = !appSettings.batteryDialogShown && !isBatteryExempt(this@MainActivity)
                }
                if (showBattery) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showBattery = false },
                        title = { Text(stringResource(R.string.battery_title)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.battery_dialog_text))
                                vendorHint()?.let {
                                    Text(it, Modifier.padding(top = 8.dp))
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                requestBatteryExemption(this@MainActivity)
                                showBattery = false
                            }) { Text(stringResource(R.string.battery_request)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showBattery = false
                                lifecycleScope.launch { appStore.update { it.copy(batteryDialogShown = true) } }
                            }) { Text(stringResource(R.string.battery_dismiss)) }
                        },
                    )
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        permissionsGranted = hasBluetoothPermission()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * Listing paired receivers only needs BLUETOOTH_CONNECT — denying
     * notifications must not hide the receiver list.
     */
    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * All paired devices, RTK receivers first. Filtering strictly by the
     * RTK_GNSS prefix hid receivers renamed by the user or shipped with a
     * different name, so the list is complete and merely ordered.
     */
    @SuppressLint("MissingPermission")
    private fun pairedRtkDevices(): List<Pair<String, String>> {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .map { (it.name ?: it.address) to it.address }
                .sortedByDescending { it.first.startsWith("RTK", ignoreCase = true) }
        } catch (_: SecurityException) { emptyList() }
    }
}

@Composable
private fun TabLabel(res: Int) {
    Text(stringResource(res), Modifier.padding(vertical = 16.dp))
}
