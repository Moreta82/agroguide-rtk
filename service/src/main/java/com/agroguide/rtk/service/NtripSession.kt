package com.agroguide.rtk.service

import com.agroguide.rtk.device.SourceEvent
import com.agroguide.rtk.device.bt.BtSppPositionSource
import com.agroguide.rtk.device.bt.ReconnectPolicy
import com.agroguide.rtk.ntrip.NtripClient
import com.agroguide.rtk.ntrip.NtripCredentials
import com.agroguide.rtk.ntrip.NtripStats
import com.agroguide.rtk.ntrip.NtripVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the NTRIP loop for one BT session: reconnects with backoff, pipes RTCM
 * into the receiver socket, answers GGA upload requests with the latest GGA
 * observed on the NMEA stream. Config comes from the active NtripProfile.
 */
class NtripSession(
    private val scope: CoroutineScope,
    private val bt: BtSppPositionSource,
    private val sourceEvents: Flow<SourceEvent>,
    private val creds: NtripCredentials,
    private val mountPoint: String,
    private val version: NtripVersion,
    private val uploadGga: Boolean,
    ggaIntervalS: Int,
) {
    private val _stats = MutableStateFlow(NtripStats())
    val stats: StateFlow<NtripStats> = _stats.asStateFlow()

    @Volatile private var lastGga: String? = null
    private val ggaIntervalMs = ggaIntervalS * 1000L
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            // remember the latest GGA line for VRS upload
            launch {
                sourceEvents.filterIsInstance<SourceEvent.Nmea>()
                    .collect { if (it.line.length > 6 && it.line.regionMatches(3, "GGA", 0, 3)) lastGga = it.line }
            }
            val policy = ReconnectPolicy()
            while (isActive) {
                val client = NtripClient(creds, mountPoint, version, ggaIntervalMs)
                val statsMirror = launch { client.stats.collect { _stats.value = it } }
                client.run(
                    rtcmSink = { bt.write(it) },
                    ggaProvider = { if (uploadGga) lastGga else null },
                )
                statsMirror.cancel()
                if (!isActive) break
                delay(policy.nextDelayMs())
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
