package com.agroguide.rtk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NtripProfile(
    val id: String,                  // UUID string
    val name: String,
    val host: String,
    val port: Int = 2101,
    val mountPoint: String = "",
    val username: String = "",
    val password: String = "",
    val ntripV2: Boolean = false,
    val uploadGga: Boolean = true,
    val ggaIntervalS: Int = 10,
)

@Serializable
data class NtripSettings(
    val profiles: List<NtripProfile> = emptyList(),
    val activeProfileId: String? = null,
) {
    val activeProfile: NtripProfile? get() = profiles.firstOrNull { it.id == activeProfileId }
}

private val Context.dataStore by preferencesDataStore("settings")
private val KEY_NTRIP = stringPreferencesKey("ntrip_settings")
private val json = Json { ignoreUnknownKeys = true }

/** Profiles live in DataStore as one JSON blob — flat list, no queries needed. */
class NtripProfileStore(private val context: Context) {
    val settings: Flow<NtripSettings> = context.dataStore.data.map { prefs ->
        prefs[KEY_NTRIP]?.let { runCatching { json.decodeFromString<NtripSettings>(it) }.getOrNull() }
            ?: NtripSettings()
    }

    suspend fun update(transform: (NtripSettings) -> NtripSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_NTRIP]?.let {
                runCatching { json.decodeFromString<NtripSettings>(it) }.getOrNull()
            } ?: NtripSettings()
            prefs[KEY_NTRIP] = json.encodeToString(NtripSettings.serializer(), transform(current))
        }
    }
}
