package com.agroguide.rtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drive-to lightbar: XTE > 0 (right of line) lights LEDs on the LEFT — steer
 * toward the light. Center number is |XTE| in cm, sized to read from 2 m.
 * [ledStepM]: ground meters per LED (default 0.10 m).
 */
@Composable
fun Lightbar(xteM: Double?, ledStepM: Double = 0.10, ledsPerSide: Int = 7) {
    val onTarget = xteM != null && abs(xteM) < ledStepM / 2
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color(0xFF101010)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lit = if (xteM == null) 0 else (abs(xteM) / ledStepM).roundToInt().coerceAtMost(ledsPerSide)
        // left side LEDs light when xte > 0 (steer left); innermost LED first
        for (i in ledsPerSide downTo 1) {
            Led(active = xteM != null && xteM > 0 && i <= lit, index = i, perSide = ledsPerSide)
        }
        Box(
            Modifier.padding(horizontal = 12.dp)
                .size(width = 128.dp, height = 56.dp)
                .background(if (onTarget) Color(0xFF2E7D32) else Color(0xFF263238), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (xteM == null) "—" else "${(abs(xteM) * 100).roundToInt()}",
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
            )
        }
        for (i in 1..ledsPerSide) {
            Led(active = xteM != null && xteM < 0 && i <= lit, index = i, perSide = ledsPerSide)
        }
    }
}

@Composable
private fun Led(active: Boolean, index: Int, perSide: Int) {
    val severity = index.toFloat() / perSide
    val color = when {
        !active -> Color(0xFF2A2A2A)
        severity < 0.4f -> Color(0xFF43A047)
        severity < 0.75f -> Color(0xFFF9A825)
        else -> Color(0xFFE53935)
    }
    Box(Modifier.padding(3.dp).size(22.dp).background(color, CircleShape))
}
