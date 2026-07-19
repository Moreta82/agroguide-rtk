package com.agroguide.rtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agroguide.rtk.R
import com.agroguide.rtk.geo.GnssQuality
import com.agroguide.rtk.geo.VehicleState

fun qualityColor(q: GnssQuality): Color = when (q) {
    GnssQuality.RTK_FIXED -> Color(0xFF2E7D32)
    GnssQuality.RTK_FLOAT -> Color(0xFFF9A825)
    GnssQuality.DGPS -> Color(0xFFEF6C00)
    GnssQuality.GPS -> Color(0xFFC62828)
    GnssQuality.DEAD_RECKONING -> Color(0xFF6A1B9A)
    GnssQuality.NO_FIX -> Color(0xFF424242)
}

@Composable
fun qualityLabel(q: GnssQuality): String = stringResource(
    when (q) {
        GnssQuality.RTK_FIXED -> R.string.fix_rtk_fixed
        GnssQuality.RTK_FLOAT -> R.string.fix_rtk_float
        GnssQuality.DGPS -> R.string.fix_dgps
        GnssQuality.GPS -> R.string.fix_gps
        GnssQuality.DEAD_RECKONING -> R.string.fix_dead_reckoning
        GnssQuality.NO_FIX -> R.string.fix_no_fix
    }
)

/** Live fix-quality panel: big colored quality chip + sats/HDOP/corr-age/speed. */
@Composable
fun FixPanel(state: VehicleState?, modifier: Modifier = Modifier) {
    val quality = state?.quality ?: GnssQuality.NO_FIX
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(qualityColor(quality), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(qualityLabel(quality), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        StatRow(stringResource(R.string.label_satellites), state?.satellites?.toString() ?: "—")
        StatRow(stringResource(R.string.label_hdop), state?.hdop?.let { "%.2f".format(it) } ?: "—")
        StatRow(
            stringResource(R.string.label_corr_age),
            state?.correctionAgeS?.let { "%.1f s".format(it) } ?: "—",
        )
        val kmh = stringResource(R.string.unit_kmh)
        StatRow(
            stringResource(R.string.label_speed),
            state?.let { "%.1f %s".format(it.speedMps * 3.6, kmh) } ?: "—",
        )
        Text(
            state?.let { "%.7f, %.7f".format(it.latDeg, it.lonDeg) } ?: "—",
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 18.sp)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
