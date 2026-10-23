package com.agroguide.rtk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { AUTO, DAY, NIGHT }
enum class ScreenOrientation { LANDSCAPE, PORTRAIT, AUTO }
enum class VehicleIcon { TRACTOR, ARROW, SQUARE }

@Serializable
data class AppSettings(
    // Night by default: high-contrast light text, matching the field view.
    val theme: String = "NIGHT",             // ThemeMode name
    val keepScreenOn: Boolean = true,
    val mockProvider: Boolean = false,
    val batteryDialogShown: Boolean = false,
    val basemap: String = "none",            // MapProvider id
    val googleMapsApiKey: String = "",
    val orientation: String = "LANDSCAPE",   // ScreenOrientation name
    /** "Pausar pintado al detenerse": off = continuous painting. */
    val pauseWhenStopped: Boolean = false,
    /** Field view colours as ARGB. Defaults: white field, translucent grey paint. */
    val fieldBackground: Long = 0xFFFFFFFF,
    val coverageColor: Long = 0x73707070L,
    val overlapColor: Long = 0x99E53935,
    val showGrid: Boolean = true,
    val vehicleIcon: String = "TRACTOR",     // VehicleIcon name
    /** Receiver latency compensation: -1 = automatic, 0 = off, else fixed ms. */
    val latencyCompensationMs: Int = -1,
) {
    val themeMode: ThemeMode get() = runCatching { ThemeMode.valueOf(theme) }.getOrDefault(ThemeMode.AUTO)
    val screenOrientation: ScreenOrientation
        get() = runCatching { ScreenOrientation.valueOf(orientation) }.getOrDefault(ScreenOrientation.LANDSCAPE)
    val vehicle: VehicleIcon get() = runCatching { VehicleIcon.valueOf(vehicleIcon) }.getOrDefault(VehicleIcon.TRACTOR)
}

private val Context.appDataStore by preferencesDataStore("app_settings")
private val KEY_APP = stringPreferencesKey("app_settings")
private val json = Json { ignoreUnknownKeys = true }

class AppSettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.appDataStore.data.map { prefs ->
        prefs[KEY_APP]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appDataStore.edit { prefs ->
            val current = prefs[KEY_APP]?.let {
                runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull()
            } ?: AppSettings()
            prefs[KEY_APP] = json.encodeToString(AppSettings.serializer(), transform(current))
        }
    }
}
