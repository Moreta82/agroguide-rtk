package com.agroguide.rtk.service

import com.agroguide.rtk.device.bt.BtSppPositionSource
import com.agroguide.rtk.device.bt.ReconnectPolicy
import com.agroguide.rtk.ntrip.NtripClient
import com.agroguide.rtk.ntrip.NtripCredentials
import com.agroguide.rtk.ntrip.NtripFailure
import com.agroguide.rtk.ntrip.NtripStats
import com.agroguide.rtk.ntrip.NtripVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val creds: NtripCredentials,
    private val mountPoint: String,
    private val version: NtripVersion,
    private val uploadGga: Boolean,
    ggaIntervalS: Int,
    private val ggaProvider: () -> String?,
) {
    private val _stats = MutableStateFlow(NtripStats())
    val stats: StateFlow<NtripStats> = _stats.asStateFlow()

    private val ggaIntervalMs = ggaIntervalS * 1000L
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            val policy = ReconnectPolicy()
            while (isActive) {
                val client = NtripClient(creds, mountPoint, version, ggaIntervalMs)
                val statsMirror = launch { client.stats.collect { _stats.value = it } }
                client.run(
                    rtcmSink = { bt.write(it) },
                    ggaProvider = { if (uploadGga) ggaProvider() else null },
                )
                statsMirror.cancel()
                _stats.value = client.stats.value
                if (!isActive) break
                val ended = client.stats.value
                // A session that delivered corrections was healthy: start the
                // backoff over instead of letting it climb to the 60 s cap.
                if (ended.bytesReceived > 0) policy.reset()
                // Retrying a wrong password or a missing mountpoint every
                // second achieves nothing and gets the IP banned on community
                // casters: wait long, the user will fix the profile.
                val wait = if (ended.failure in PERMANENT) PERMANENT_RETRY_MS else policy.nextDelayMs()
                delay(wait)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private companion object {
        val PERMANENT = setOf(
            NtripFailure.UNAUTHORIZED, NtripFailure.FORBIDDEN, NtripFailure.MOUNTPOINT_NOT_FOUND,
        )
        const val PERMANENT_RETRY_MS = 60_000L
    }
}
