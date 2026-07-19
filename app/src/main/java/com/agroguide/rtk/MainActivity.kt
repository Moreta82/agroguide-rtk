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
import com.agroguide.rtk.ui.FieldScreen
import com.agroguide.rtk.ui.JobsScreen
import com.agroguide.rtk.ui.NtripScreen
import com.agroguide.rtk.ui.ParcelsScreen
import com.agroguide.rtk.ui.ReceiverScreen
import com.agroguide.rtk.ui.ringToString
import com.agroguide.rtk.ui.theme.AgroGuideTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = it.values.all { granted -> granted }
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
        setContent {
            AgroGuideTheme {
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
                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }) { TabLabel(R.string.tab_guidance) }
                        Tab(selected = tab == 1, onClick = { tab = 1 }) { TabLabel(R.string.tab_parcels) }
                        Tab(selected = tab == 2, onClick = { tab = 2 }) { TabLabel(R.string.tab_jobs) }
                        Tab(selected = tab == 3, onClick = { tab = 3 }) { TabLabel(R.string.tab_ntrip) }
                        Tab(selected = tab == 4, onClick = { tab = 4 }) { TabLabel(R.string.tab_receiver) }
                    }
                    when (tab) {
                        0 -> FieldScreen(
                            pairedRtkDevices = if (permissionsGranted) pairedRtkDevices() else emptyList(),
                            activeNtripProfile = settings.activeProfile,
                            implementStore = implementStore,
                        )
                        1 -> ParcelsScreen(
                            onImport = { importLauncher.launch(arrayOf("*/*")) },
                            onGoToField = { tab = 0 },
                        )
                        2 -> JobsScreen()
                        3 -> NtripScreen(settings, store)
                        else -> ReceiverScreen()
                    }
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) permissionsGranted = true
        else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Paired devices whose name starts with RTK_GNSS (ArduSimple kit naming). */
    @SuppressLint("MissingPermission")
    private fun pairedRtkDevices(): List<Pair<String, String>> {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .filter { (it.name ?: "").startsWith("RTK_GNSS", ignoreCase = true) }
                .map { (it.name ?: it.address) to it.address }
        } catch (_: SecurityException) { emptyList() }
    }
}

@Composable
private fun TabLabel(res: Int) {
    Text(stringResource(res), Modifier.padding(vertical = 16.dp))
}
