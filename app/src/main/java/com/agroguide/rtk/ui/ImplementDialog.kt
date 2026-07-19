package com.agroguide.rtk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.agroguide.rtk.R
import com.agroguide.rtk.data.ImplementSettings

/** Numeric field accepting both decimal comma and dot. */
private fun parseM(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

@Composable
fun ImplementDialog(
    initial: ImplementSettings,
    onSave: (ImplementSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var width by remember { mutableStateOf(initial.widthM.toString()) }
    var lateral by remember { mutableStateOf(initial.lateralOffsetM.toString()) }
    var longitudinal by remember { mutableStateOf(initial.longitudinalOffsetM.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.implement_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(width, { width = it }, label = { Text(stringResource(R.string.implement_width)) }, singleLine = true)
                OutlinedTextField(lateral, { lateral = it }, label = { Text(stringResource(R.string.implement_lateral)) }, singleLine = true)
                OutlinedTextField(longitudinal, { longitudinal = it }, label = { Text(stringResource(R.string.implement_longitudinal)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ImplementSettings(
                            widthM = (parseM(width) ?: initial.widthM).coerceIn(0.5, 60.0),
                            lateralOffsetM = (parseM(lateral) ?: initial.lateralOffsetM).coerceIn(-30.0, 30.0),
                            longitudinalOffsetM = (parseM(longitudinal) ?: initial.longitudinalOffsetM).coerceIn(-10.0, 30.0),
                        )
                    )
                },
                enabled = parseM(width) != null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
