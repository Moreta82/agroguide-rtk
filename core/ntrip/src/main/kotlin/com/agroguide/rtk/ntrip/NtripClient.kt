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
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

data class NtripStats(
    val connected: Boolean = false,
    val bytesReceived: Long = 0,
    val rtcmFrames: Long = 0,
    val lastDataMonotonicMs: Long = 0,
    val lastError: String? = null,
)

/**
 * One NTRIP session: connect, stream RTCM into the sink, periodically upload
 * GGA (VRS casters need it). run() suspends until cancelled or the socket
 * drops, then returns — the caller owns the reconnect policy. Traffic stats
 * feed the UI indicator.
 */
class NtripClient(
    private val creds: NtripCredentials,
    private val mountPoint: String,
    private val version: NtripVersion,
    private val ggaIntervalMs: Long = 10_000,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _stats = MutableStateFlow(NtripStats())
    val stats: StateFlow<NtripStats> = _stats.asStateFlow()

    suspend fun run(rtcmSink: (ByteArray) -> Unit, ggaProvider: () -> String?) {
        withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket().also { it.connect(InetSocketAddress(creds.host, creds.port), 10_000) }
                val out = socket.getOutputStream()
                out.write(NtripRequest.stream(creds, mountPoint, version).toByteArray())
                out.flush()
                val input = socket.getInputStream()
                val head = readHeader(input)
                if (!head.ok) {
                    _stats.value = NtripStats(lastError = head.statusLine)
                    return@withContext
                }
                _stats.value = NtripStats(connected = true)
                val decoder = ChunkedDecoder()
                coroutineScope {
                    val uploader = launch {
                        while (isActive) {
                            delay(ggaIntervalMs)
                            val gga = ggaProvider() ?: continue
                            try { out.write((gga + "\r\n").toByteArray()); out.flush() }
                            catch (_: IOException) { return@launch }
                        }
                    }
                    val buf = ByteArray(4096)
                    try {
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val data = if (head.chunked) decoder.feed(buf.copyOf(n)) else buf.copyOf(n)
                            if (data.isNotEmpty()) {
                                rtcmSink(data)
                                _stats.value = _stats.value.copy(
                                    bytesReceived = _stats.value.bytesReceived + data.size,
                                    rtcmFrames = _stats.value.rtcmFrames + data.count { it == 0xD3.toByte() },
                                    lastDataMonotonicMs = nowMs(),
                                )
                            }
                        }
                    } catch (_: IOException) { /* dropped */ }
                    uploader.cancel()
                }
            } catch (e: IOException) {
                _stats.value = _stats.value.copy(connected = false, lastError = e.message)
            } finally {
                _stats.value = _stats.value.copy(connected = false)
                try { socket?.close() } catch (_: IOException) {}
            }
        }
    }

    private class Header(val ok: Boolean, val statusLine: String, val chunked: Boolean)

    companion object {
        /** Reads the response head byte-by-byte so no stream data is swallowed. */
        private fun readHeader(input: InputStream): Header {
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                sb.append(b.toChar())
                if (sb.length == 12 && sb.startsWith("ICY 200 OK\r\n")) {
                    return Header(true, "ICY 200 OK", chunked = false)   // v1: no further headers
                }
                if (sb.length > 16_384) break
            }
            val text = sb.toString()
            val status = text.lineSequence().firstOrNull() ?: ""
            val ok = status.contains(" 200 ")
            val chunked = text.lowercase().contains("transfer-encoding: chunked")
            return Header(ok, status, chunked)
        }

        /** One-shot sourcetable fetch (empty mountpoint GET). */
        suspend fun fetchSourceTable(creds: NtripCredentials, version: NtripVersion): List<MountPoint> =
            withContext(Dispatchers.IO) {
                Socket().use { s ->
                    s.connect(InetSocketAddress(creds.host, creds.port), 10_000)
                    s.soTimeout = 10_000
                    s.getOutputStream().write(NtripRequest.sourceTable(creds, version).toByteArray())
                    s.getOutputStream().flush()
                    val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                    val bodyStart = text.indexOf("\r\n\r\n")
                    SourceTable.parse(if (bodyStart >= 0) text.substring(bodyStart + 4) else text)
                }
            }
    }
}
