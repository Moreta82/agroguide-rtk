package com.agroguide.rtk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.agroguide.rtk.data.AppSettings
import com.agroguide.rtk.data.VehicleIcon

/**
 * Everything the field canvas needs to know about appearance. Line colours are
 * derived from the background so they stay readable whether the user picks a
 * white or a dark field (white pass lines vanish on a white background).
 */
data class FieldStyle(
    val background: Color = Color.White,
    val coverage: Color = Color(0x73707070),
    val overlap: Color = Color(0x99E53935),
    val showGrid: Boolean = true,
    val vehicle: VehicleIcon = VehicleIcon.TRACTOR,
) {
    val isLight: Boolean get() = background.luminance() > 0.5f

    val grid: Color get() = if (isLight) Color(0x1F000000) else Color(0x2200FF00)
    val trail: Color get() = if (isLight) Color(0x59000000) else Color(0x66FFFFFF)
    val pass: Color get() = if (isLight) Color(0x66000000) else Color(0x88FFFFFF)
    val currentPass: Color get() = if (isLight) Color(0xFF1565C0) else Color(0xFFFFEB3B)
    val implement: Color get() = if (isLight) Color(0xFF00838F) else Color(0xFF00E5FF)
    val outline: Color get() = if (isLight) Color(0xCC000000) else Color(0xCCFFFFFF)
    val draft: Color get() = if (isLight) Color(0xFF00838F) else Color(0xFF00E5FF)
    val onField: Color get() = if (isLight) Color(0xFF14201A) else Color.White

    companion object {
        fun from(s: AppSettings) = FieldStyle(
            background = Color(s.fieldBackground.toInt()),
            coverage = Color(s.coverageColor.toInt()),
            overlap = Color(s.overlapColor.toInt()),
            showGrid = s.showGrid,
            vehicle = s.vehicle,
        )

        /** Swatches offered in Ajustes (ARGB). Paint colours are translucent on purpose. */
        val BACKGROUNDS = listOf(0xFFFFFFFF, 0xFFE9ECE6, 0xFFF1E6C8, 0xFF1B2E1B, 0xFF101010)
        val COVERAGES = listOf(0x73707070L, 0x6632CD32L, 0x661E88E5L, 0x80FBC02DL, 0x738D6E63L)
        val OVERLAPS = listOf(0x99E53935, 0x99FB8C00, 0x998E24AA, 0x991565C0)
    }
}
