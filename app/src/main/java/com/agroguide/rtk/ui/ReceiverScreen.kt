package com.agroguide.rtk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.R
import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.nmea.ubx.UbxValSet
import com.agroguide.rtk.service.GuidanceService
import com.agroguide.rtk.service.PositionRepository
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Receiver utilities: one-tap optimize / restore over the live BT link, with ACK feedback. */
@Composable
fun ReceiverScreen() {
    val sourceName by PositionRepository.sourceName.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val msgOk = stringResource(R.string.receiver_ok)
    val msgNak = stringResource(R.string.receiver_nak)
    val msgTimeout = stringResource(R.string.receiver_timeout)

    fun sendAndConfirm(frame: ByteArray) {
        val bt = GuidanceService.activeBt ?: return
        val events = PositionRepository.sharedEvents ?: return
        scope.launch {
            bt.write(frame)
            val ack = withTimeoutOrNull(3000) {
                events.filterIsInstance<SourceEvent.UbxAckEvent>().first()
            }
            snackbar.showSnackbar(
                when {
                    ack == null -> msgTimeout
                    ack.ok -> msgOk
                    else -> msgNak
                }
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.receiver_hint), style = MaterialTheme.typography.bodyLarge)
            if (sourceName != "BT-SPP") {
                Text(
                    stringResource(R.string.receiver_need_bt),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = { sendAndConfirm(UbxValSet.optimize()) },
                enabled = sourceName == "BT-SPP",
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(stringResource(R.string.receiver_optimize), style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(
                onClick = { sendAndConfirm(UbxValSet.restoreDefaults()) },
                enabled = sourceName == "BT-SPP",
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(stringResource(R.string.receiver_restore), style = MaterialTheme.typography.titleMedium) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
