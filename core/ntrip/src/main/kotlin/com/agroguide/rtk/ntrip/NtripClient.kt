package com.agroguide.rtk.ntrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class NtripStats(
    val connected: Boolean = false,
    val bytesReceived: Long = 0,
    val rtcmFrames: Long = 0,
    val lastDataMonotonicMs: Long = 0,
    /** Why corrections are not flowing; null while healthy. */
    val failure: NtripFailure? = null,
    /** Raw detail for [failure] (server status line or exception text). */
    val lastError: String? = null,
)

/** A caster refusal, carrying its classified cause. */
class NtripException(val failure: NtripFailure, message: String) : IOException(message)

/** Maps any connection error to the cause shown to the user. */
fun ntripFailureOf(e: Throwable): NtripFailure = when (e) {
    is NtripException -> e.failure
    is UnknownHostException -> NtripFailure.HOST_NOT_FOUND
    is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> NtripFailure.UNREACHABLE
    else -> NtripFailure.REJECTED
}

/** Outcome of a one-shot connection test. */
data class NtripProbeResult(
    val failure: NtripFailure?,
    val detail: String?,
    val bytes: Long,
    val rtcmFrames: Long,
    val sentGga: Boolean,
)

/**
 * One NTRIP session: connect, stream RTCM into the sink, upload GGA (VRS
 * casters need it). run() returns when the socket drops, the caster refuses,
 * or the stream goes silent for [silenceTimeoutMs]; the caller owns the
 * reconnect policy. [stats] always carries the reason a session ended.
 */
class NtripClient(
    creds: NtripCredentials,
    mountPoint: String,
    private val version: NtripVersion,
    private val ggaIntervalMs: Long = 10_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val silenceTimeoutMs: Long = 30_000,
) {
    // Defensive cleanup: the session must work whatever the user typed.
    private val endpoint = NtripEndpoint.parse(creds.host, creds.port)
    private val creds = creds.copy(host = endpoint.host, port = endpoint.port)
    private val mountPoint = NtripEndpoint.normalizeMount(mountPoint)

    private val _stats = MutableStateFlow(NtripStats())
    val stats: StateFlow<NtripStats> = _stats.asStateFlow()

    suspend fun run(rtcmSink: (ByteArray) -> Unit, ggaProvider: () -> String?) {
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket().also { it.connect(InetSocketAddress(creds.host, creds.port), 10_000) }
                // Short read timeout: keeps the loop responsive to cancellation
                // and lets us notice a caster that went quiet.
                socket.soTimeout = READ_POLL_MS
                val out = socket.getOutputStream()
                out.write(NtripRequest.stream(creds, mountPoint, version).toByteArray())
                out.flush()
                val input = socket.getInputStream()
                val head = NtripResponse.classify(readHead(input))
                if (!head.ok) {
                    _stats.value = NtripStats(failure = head.failure, lastError = head.statusLine)
                    return@withContext
                }
                _stats.value = NtripStats(connected = true, lastDataMonotonicMs = nowMs())
                val decoder = ChunkedDecoder()
                coroutineScope {
                    // VRS/network mountpoints send nothing until they get a GGA:
                    // send the first one immediately (or as soon as a fix exists),
                    // then at the configured interval.
                    val uploader = launch {
                        var sentOnce = false
                        while (isActive) {
                            val gga = ggaProvider()
                            if (gga != null) {
                                try {
                                    out.write((gga + "\r\n").toByteArray()); out.flush(); sentOnce = true
                                } catch (_: IOException) { return@launch }
                            }
                            delay(if (sentOnce) ggaIntervalMs else 1000)
                        }
                    }
                    val buf = ByteArray(4096)
                    var lastData = nowMs()
                    try {
                        while (isActive) {
                            val n = try {
                                input.read(buf)
                            } catch (_: SocketTimeoutException) {
                                if (nowMs() - lastData > silenceTimeoutMs) {
                                    _stats.value = _stats.value.copy(
                                        failure = NtripFailure.NO_DATA,
                                        lastError = "sin datos durante ${silenceTimeoutMs / 1000} s",
                                    )
                                    break
                                }
                                continue
                            }
                            if (n < 0) {
                                _stats.value = _stats.value.copy(
                                    failure = if (_stats.value.bytesReceived == 0L) NtripFailure.NO_DATA
                                    else NtripFailure.UNREACHABLE,
                                    lastError = "el servidor cerró la conexión",
                                )
                                break
                            }
                            val data = if (head.chunked) decoder.feed(buf.copyOf(n)) else buf.copyOf(n)
                            if (data.isNotEmpty()) {
                                lastData = nowMs()
                                rtcmSink(data)
                                _stats.value = _stats.value.copy(
                                    bytesReceived = _stats.value.bytesReceived + data.size,
                                    rtcmFrames = _stats.value.rtcmFrames + data.count { it == 0xD3.toByte() },
                                    lastDataMonotonicMs = lastData,
                                    failure = null,
                                    lastError = null,
                                )
                            }
                        }
                    } catch (e: IOException) {
                        _stats.value = _stats.value.copy(failure = NtripFailure.UNREACHABLE, lastError = e.message)
                    }
                    uploader.cancel()
                }
            } catch (e: IOException) {
                _stats.value = _stats.value.copy(
                    connected = false,
                    failure = ntripFailureOf(e),
                    lastError = e.message ?: e.javaClass.simpleName,
                )
            } finally {
                _stats.value = _stats.value.copy(connected = false)
                try { socket?.close() } catch (_: IOException) {}
            }
        }
    }

    companion object {
        private const val READ_POLL_MS = 1000
        private const val HEAD_DEADLINE_MS = 10_000L

        /**
         * Reads the response head byte-by-byte so no stream data is swallowed.
         * NTRIP v1 casters may send only "ICY 200 OK\r\n" and start streaming.
         */
        private fun readHead(input: InputStream): String {
            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + HEAD_DEADLINE_MS
            while (!sb.endsWith("\r\n\r\n")) {
                val b = try {
                    input.read()
                } catch (e: SocketTimeoutException) {
                    if (System.currentTimeMillis() > deadline) throw e
                    continue
                }
                if (b < 0) break
                sb.append(b.toChar())
                if (sb.length == 12 && sb.startsWith("ICY 200 OK\r\n")) return sb.toString()
                if (sb.length > 16_384) break
            }
            return sb.toString()
        }

        /**
         * One-shot sourcetable fetch (empty mountpoint GET). Throws
         * [NtripException] when the caster refuses, so the caller can say why.
         */
        suspend fun fetchSourceTable(creds: NtripCredentials, version: NtripVersion): List<MountPoint> =
            withContext(Dispatchers.IO) {
                val ep = NtripEndpoint.parse(creds.host, creds.port)
                val clean = creds.copy(host = ep.host, port = ep.port)
                Socket().use { s ->
                    s.connect(InetSocketAddress(clean.host, clean.port), 10_000)
                    s.soTimeout = 10_000
                    s.getOutputStream().write(NtripRequest.sourceTable(clean, version).toByteArray())
                    s.getOutputStream().flush()
                    val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                    val status = text.lineSequence().firstOrNull().orEmpty()
                    if (!status.uppercase().startsWith("SOURCETABLE")) {
                        val head = NtripResponse.classify(text)
                        if (!head.ok) throw NtripException(head.failure ?: NtripFailure.REJECTED, head.statusLine)
                    }
                    val bodyStart = text.indexOf("\r\n\r\n")
                    SourceTable.parse(if (bodyStart >= 0) text.substring(bodyStart + 4) else text)
                }
            }

        /**
         * Connection test for the profile editor: connects with the real
         * credentials, sends [gga] if given, listens for [listenMs] and reports
         * exactly what happened.
         */
        suspend fun probe(
            creds: NtripCredentials,
            mountPoint: String,
            version: NtripVersion,
            gga: String?,
            listenMs: Long = 6_000,
        ): NtripProbeResult {
            val client = NtripClient(creds, mountPoint, version, ggaIntervalMs = 5_000, silenceTimeoutMs = listenMs)
            var bytes = 0L
            withTimeoutOrNull(listenMs + 1_500) {
                client.run(rtcmSink = { bytes += it.size }, ggaProvider = { gga })
            }
            val st = client.stats.value
            val failure = if (bytes > 0) null else st.failure
            return NtripProbeResult(failure, st.lastError, bytes, st.rtcmFrames, gga != null)
        }
    }
}
