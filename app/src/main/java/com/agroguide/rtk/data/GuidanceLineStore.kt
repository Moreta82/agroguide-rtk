package com.agroguide.rtk.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.agroguide.rtk.data.guidance.GuidanceLineCodec
import com.agroguide.rtk.geo.guidance.GuidanceLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.lineDataStore by preferencesDataStore("guidance_line")
private val KEY_LINE = stringPreferencesKey("guidance_line")

/** Persists the active guidance line so it survives app restarts. */
class GuidanceLineStore(private val context: Context) {
    suspend fun load(): GuidanceLine? =
        context.lineDataStore.data.map { it[KEY_LINE] }.first()?.let { GuidanceLineCodec.fromJson(it) }

    suspend fun save(line: GuidanceLine?) {
        context.lineDataStore.edit { prefs ->
            val jsonText = GuidanceLineCodec.toJson(line)
            if (jsonText == null) prefs.remove(KEY_LINE) else prefs[KEY_LINE] = jsonText
        }
    }
}
