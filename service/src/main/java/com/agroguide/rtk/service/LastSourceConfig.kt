package com.agroguide.rtk.service

import android.content.Context
import android.content.Intent

/**
 * Last explicit source configuration, persisted so a START_STICKY restart can
 * resume the SAME source. Without this the service used to fall back to the
 * demo simulator on a null intent, silently replacing a real receiver with a
 * synthetic tractor.
 */
object LastSourceConfig {
    private const val PREFS = "last_source"
    private const val K_SET = "configured"

    fun save(context: Context, intent: Intent) {
        val mode = intent.getStringExtra(GuidanceService.EXTRA_MODE) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putBoolean(K_SET, true)
            putString(GuidanceService.EXTRA_MODE, mode)
            putString(GuidanceService.EXTRA_BT_ADDRESS, intent.getStringExtra(GuidanceService.EXTRA_BT_ADDRESS))
            putBoolean(GuidanceService.EXTRA_MOCK, intent.getBooleanExtra(GuidanceService.EXTRA_MOCK, false))
            putBoolean(GuidanceService.EXTRA_NTRIP, intent.getBooleanExtra(GuidanceService.EXTRA_NTRIP, false))
            putString(GuidanceService.EXTRA_NTRIP_HOST, intent.getStringExtra(GuidanceService.EXTRA_NTRIP_HOST))
            putInt(GuidanceService.EXTRA_NTRIP_PORT, intent.getIntExtra(GuidanceService.EXTRA_NTRIP_PORT, 2101))
            putString(GuidanceService.EXTRA_NTRIP_USER, intent.getStringExtra(GuidanceService.EXTRA_NTRIP_USER))
            putString(GuidanceService.EXTRA_NTRIP_PASS, intent.getStringExtra(GuidanceService.EXTRA_NTRIP_PASS))
            putString(GuidanceService.EXTRA_NTRIP_MOUNT, intent.getStringExtra(GuidanceService.EXTRA_NTRIP_MOUNT))
            putBoolean(GuidanceService.EXTRA_NTRIP_V2, intent.getBooleanExtra(GuidanceService.EXTRA_NTRIP_V2, false))
            putBoolean(GuidanceService.EXTRA_NTRIP_GGA, intent.getBooleanExtra(GuidanceService.EXTRA_NTRIP_GGA, true))
            putInt(GuidanceService.EXTRA_NTRIP_GGA_INT, intent.getIntExtra(GuidanceService.EXTRA_NTRIP_GGA_INT, 10))
        }.apply()
    }

    /** Rebuilds the last start intent, or null if the user never started a source. */
    fun restore(context: Context): Intent? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(K_SET, false)) return null
        val mode = p.getString(GuidanceService.EXTRA_MODE, null) ?: return null
        return Intent(context, GuidanceService::class.java)
            .putExtra(GuidanceService.EXTRA_MODE, mode)
            .putExtra(GuidanceService.EXTRA_BT_ADDRESS, p.getString(GuidanceService.EXTRA_BT_ADDRESS, null))
            .putExtra(GuidanceService.EXTRA_MOCK, p.getBoolean(GuidanceService.EXTRA_MOCK, false))
            .putExtra(GuidanceService.EXTRA_NTRIP, p.getBoolean(GuidanceService.EXTRA_NTRIP, false))
            .putExtra(GuidanceService.EXTRA_NTRIP_HOST, p.getString(GuidanceService.EXTRA_NTRIP_HOST, null))
            .putExtra(GuidanceService.EXTRA_NTRIP_PORT, p.getInt(GuidanceService.EXTRA_NTRIP_PORT, 2101))
            .putExtra(GuidanceService.EXTRA_NTRIP_USER, p.getString(GuidanceService.EXTRA_NTRIP_USER, null))
            .putExtra(GuidanceService.EXTRA_NTRIP_PASS, p.getString(GuidanceService.EXTRA_NTRIP_PASS, null))
            .putExtra(GuidanceService.EXTRA_NTRIP_MOUNT, p.getString(GuidanceService.EXTRA_NTRIP_MOUNT, null))
            .putExtra(GuidanceService.EXTRA_NTRIP_V2, p.getBoolean(GuidanceService.EXTRA_NTRIP_V2, false))
            .putExtra(GuidanceService.EXTRA_NTRIP_GGA, p.getBoolean(GuidanceService.EXTRA_NTRIP_GGA, true))
            .putExtra(GuidanceService.EXTRA_NTRIP_GGA_INT, p.getInt(GuidanceService.EXTRA_NTRIP_GGA_INT, 10))
    }

    /** Mode last chosen by the user, for the source dialog's default selection. */
    fun lastMode(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(GuidanceService.EXTRA_MODE, null)

    fun lastBtAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(GuidanceService.EXTRA_BT_ADDRESS, null)
}
