package com.agroguide.rtk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Day scheme: dark text on a light background, for bright sunlight. */
private val DayColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    secondary = Color(0xFF00695C),
    onSecondary = Color.White,
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101010),
    surfaceVariant = Color(0xFFE3E8E3),
    onSurfaceVariant = Color(0xFF1A1A1A),
    background = Color(0xFFF3F5F2),
    onBackground = Color(0xFF101010),
    outline = Color(0xFF6B726B),
)

/** Night scheme: high-contrast light text on dark, glove- and cabin-friendly. */
private val NightColors = darkColorScheme(
    primary = Color(0xFF7CDB8A),
    onPrimary = Color(0xFF06250B),
    secondary = Color(0xFF66D6C4),
    onSecondary = Color(0xFF04231E),
    surface = Color(0xFF16201A),
    onSurface = Color(0xFFF2F5F2),
    surfaceVariant = Color(0xFF243028),
    onSurfaceVariant = Color(0xFFE2E8E2),
    background = Color(0xFF0E1510),
    onBackground = Color(0xFFF2F5F2),
    outline = Color(0xFF9AA79C),
)

/**
 * [night] null = follow system.
 *
 * The Surface is essential: without it, screens that draw no background of
 * their own let the (dark) window background show through while text uses the
 * scheme's onBackground, which made every tab unreadable.
 */
@Composable
fun AgroGuideTheme(night: Boolean? = null, content: @Composable () -> Unit) {
    val dark = night ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
