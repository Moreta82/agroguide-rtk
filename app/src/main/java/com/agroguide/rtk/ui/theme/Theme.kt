package com.agroguide.rtk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DayColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    surface = Color(0xFFF5F5F0),
    onSurface = Color(0xFF101010),
    background = Color(0xFFECECE4),
    onBackground = Color(0xFF101010),
)

/** High-contrast night scheme: dark greens, bright text, glove-friendly. */
private val NightColors = darkColorScheme(
    primary = Color(0xFF7CDB8A),
    onPrimary = Color(0xFF06250B),
    surface = Color(0xFF16201A),
    onSurface = Color(0xFFF2F5F2),
    background = Color(0xFF0E1510),
    onBackground = Color(0xFFF2F5F2),
)

/** [night] null = follow system. */
@Composable
fun AgroGuideTheme(night: Boolean? = null, content: @Composable () -> Unit) {
    val dark = night ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) NightColors else DayColors, content = content)
}
