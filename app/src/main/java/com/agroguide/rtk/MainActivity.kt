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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.agroguide.rtk.ui.HomeScreen
import com.agroguide.rtk.ui.theme.AgroGuideTheme

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = it.values.all { granted -> granted }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNeededPermissions()
        setContent {
            AgroGuideTheme {
                HomeScreen(pairedRtkDevices = if (permissionsGranted) pairedRtkDevices() else emptyList())
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
