package com.agroguide.rtk.ui.theme

import androidx.compose.material3.MaterialTheme
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

@Composable
fun AgroGuideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DayColors, content = content)
}
