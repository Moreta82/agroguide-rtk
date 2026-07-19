package com.agroguide.rtk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agroguide.rtk.geo.guidance.ImplementConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ImplementSettings(
    val widthM: Double = 6.0,
    val lateralOffsetM: Double = 0.0,
    val longitudinalOffsetM: Double = 0.0,
    val sections: Int = 1,
) {
    fun toConfig() = ImplementConfig(widthM, lateralOffsetM, longitudinalOffsetM, sections)
}

private val Context.implementDataStore by preferencesDataStore("implement")
private val KEY_IMPLEMENT = stringPreferencesKey("implement_settings")
private val json = Json { ignoreUnknownKeys = true }

/** Implement geometry persisted as one JSON blob in DataStore. */
class ImplementSettingsStore(private val context: Context) {
    val settings: Flow<ImplementSettings> = context.implementDataStore.data.map { prefs ->
        prefs[KEY_IMPLEMENT]?.let { runCatching { json.decodeFromString<ImplementSettings>(it) }.getOrNull() }
            ?: ImplementSettings()
    }

    suspend fun save(settings: ImplementSettings) {
        context.implementDataStore.edit { prefs ->
            prefs[KEY_IMPLEMENT] = json.encodeToString(ImplementSettings.serializer(), settings)
        }
    }
}
