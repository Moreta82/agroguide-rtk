package com.agroguide.rtk.ntrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconnect storm: the caster accepts, streams a burst and drops the socket,
 * three times in a row. Mirrors NtripSession's loop (fresh client per cycle);
 * every cycle must return promptly and deliver its bytes.
 */
class NtripStormTest {
    @Test fun `client recovers across repeated caster drops`() = runBlocking {
        val t0 = System.nanoTime()
        repeat(3) { cycle ->
            val server = ServerSocket(0)
            val burst = ByteArray(64) { (it + cycle).toByte() }
            val serverJob = launch(Dispatchers.IO) {
                val s = server.accept()
                s.getInputStream().bufferedReader().let { r ->
                    generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                }
                s.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
                s.getOutputStream().write(burst)
                s.getOutputStream().flush()
                s.close(); server.close()
            }
            val received = Collections.synchronizedList(mutableListOf<Byte>())
            val client = NtripClient(
                NtripCredentials("127.0.0.1", server.localPort, "", ""),
                mountPoint = "STORM", version = NtripVersion.V1, ggaIntervalMs = 60_000,
            )
            withTimeout(5000) {
                client.run(rtcmSink = { received += it.toList() }, ggaProvider = { null })
            }
            serverJob.join()
            assertEquals(64, received.size, "cycle $cycle delivered ${received.size} bytes")
            assertTrue(!client.stats.value.connected)   // reported disconnected after drop
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        assertTrue(seconds < 15.0, "storm took $seconds s")
    }
}
