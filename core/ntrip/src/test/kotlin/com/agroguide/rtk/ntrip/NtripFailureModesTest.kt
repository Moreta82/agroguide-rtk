package com.agroguide.rtk.ntrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** End-to-end against a local fake caster replaying real-world caster behaviour. */
class NtripFailureModesTest {

    /** Fake caster: reads the request head, then runs [script] on the socket. */
    private fun fakeCaster(script: (java.net.Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            try {
                server.accept().use { s ->
                    val r = s.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = r.read(); if (b < 0) break; head.append(b.toChar())
                    }
                    script(s)
                }
            } catch (_: Exception) {
            } finally {
                server.close()
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    private fun client(port: Int, gga: Long = 10_000, silence: Long = 30_000) =
        NtripClient(NtripCredentials("127.0.0.1", port, "u", "p"), "MP", NtripVersion.V1, gga,
            silenceTimeoutMs = silence)

    @Test fun `401 is reported as an authentication failure and never as connected`() = runBlocking {
        val server = fakeCaster { s ->
            s.getOutputStream().write(
                ("HTTP/1.1 401 Unauthorized\r\nServer: GN_Caster/1.0\r\n" +
                    "WWW-Authenticate: Basic realm=\"/VRS3M\"\r\nConnection: close\r\n\r\n").toByteArray()
            )
        }
        val c = client(server.localPort)
        var everConnected = false
        val watch = async(Dispatchers.Default) { c.stats.collect { if (it.connected) everConnected = true } }
        withTimeout(5000) { c.run(rtcmSink = {}, ggaProvider = { null }) }
        watch.cancel()
        assertEquals(NtripFailure.UNAUTHORIZED, c.stats.value.failure)
        assertFalse(everConnected)
    }

    @Test fun `sourcetable reply is a missing mountpoint and nothing reaches the receiver`() = runBlocking {
        val server = fakeCaster { s ->
            s.getOutputStream().write(
                "SOURCETABLE 200 OK\r\nContent-Type: text/plain\r\n\r\nSTR;LEON0;Leon;RTCM 3.2;;2;GPS;IGN;ESP;42.59;354.35;0;0;x;none;B;N;0;\r\nENDSOURCETABLE\r\n"
                    .toByteArray()
            )
        }
        val c = client(server.localPort)
        val sunk = Collections.synchronizedList(mutableListOf<Byte>())
        withTimeout(5000) { c.run(rtcmSink = { sunk += it.toList() }, ggaProvider = { null }) }
        assertEquals(NtripFailure.MOUNTPOINT_NOT_FOUND, c.stats.value.failure)
        assertTrue(sunk.isEmpty(), "sourcetable text must never be written to the receiver")
    }

    @Test fun `first GGA is sent immediately so VRS casters start streaming without delay`() = runBlocking {
        // Like IGN's VRS3M: no RTCM until a GGA arrives.
        val rtcm = byteArrayOf(0xD3.toByte(), 0, 2, 7, 7, 1, 2, 3)
        val server = fakeCaster { s ->
            s.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
            s.getOutputStream().flush()
            val line = s.getInputStream().bufferedReader().readLine()   // blocks until GGA
            if (line != null && line.contains("GGA")) {
                s.getOutputStream().write(rtcm); s.getOutputStream().flush()
            }
            Thread.sleep(300)
        }
        val c = client(server.localPort, gga = 10_000)               // 10 s periodic interval
        val sunk = Collections.synchronizedList(mutableListOf<Byte>())
        val t0 = System.nanoTime()
        withTimeout(5000) { c.run(rtcmSink = { sunk += it.toList() }, ggaProvider = { "\$GNGGA,fix*00" }) }
        val seconds = (System.nanoTime() - t0) / 1e9
        assertEquals(rtcm.toList(), sunk.toList())
        assertTrue(seconds < 3.0, "RTCM took $seconds s: first GGA was delayed")
    }

    @Test fun `silent stream is detected and the session ends so it can reconnect`() = runBlocking {
        val server = fakeCaster { s ->
            s.getOutputStream().write("ICY 200 OK\r\n".toByteArray()); s.getOutputStream().flush()
            Thread.sleep(6000)                                       // connected, but mute
        }
        val c = client(server.localPort, silence = 1500)
        val t0 = System.nanoTime()
        withTimeout(5000) { c.run(rtcmSink = {}, ggaProvider = { null }) }
        val seconds = (System.nanoTime() - t0) / 1e9
        assertEquals(NtripFailure.NO_DATA, c.stats.value.failure)
        assertTrue(seconds < 4.0, "took $seconds s")
    }

    @Test fun `unknown host is reported as such`() = runBlocking {
        val c = NtripClient(NtripCredentials("no-such-caster.invalid", 2101, "", ""), "MP", NtripVersion.V1)
        withTimeout(20_000) { c.run(rtcmSink = {}, ggaProvider = { null }) }
        assertEquals(NtripFailure.HOST_NOT_FOUND, c.stats.value.failure)
    }

    @Test fun `closed port is reported as unreachable`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }              // bound then freed: nobody listens
        val c = NtripClient(NtripCredentials("127.0.0.1", port, "", ""), "MP", NtripVersion.V1)
        withTimeout(15_000) { c.run(rtcmSink = {}, ggaProvider = { null }) }
        assertEquals(NtripFailure.UNREACHABLE, c.stats.value.failure)
    }
}
