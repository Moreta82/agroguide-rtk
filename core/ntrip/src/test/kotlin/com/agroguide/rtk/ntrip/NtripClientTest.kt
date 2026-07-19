package com.agroguide.rtk.ntrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NtripClientTest {

    @Test fun `v1 handshake then rtcm bytes flow to sink and gga is uploaded`() = runBlocking {
        val server = ServerSocket(0)
        val rtcm = byteArrayOf(0xD3.toByte(), 0x00, 0x03, 1, 2, 3, 9, 9, 9)
        val serverJob = async(Dispatchers.IO) {
            val s = server.accept()
            val reader = s.getInputStream().bufferedReader()
            val request = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            s.getOutputStream().write("ICY 200 OK\r\n".toByteArray())
            s.getOutputStream().write(rtcm)
            s.getOutputStream().flush()
            // wait for one uploaded GGA line
            val gga = reader.readLine()
            s.close(); server.close()
            request to gga
        }
        val received = Collections.synchronizedList(mutableListOf<Byte>())
        val client = NtripClient(
            NtripCredentials("127.0.0.1", server.localPort, "u", "p"),
            mountPoint = "TEST", version = NtripVersion.V1,
            ggaIntervalMs = 50,
        )
        val session = launch {
            client.run(
                rtcmSink = { received += it.toList() },
                ggaProvider = { "\$GNGGA,demo*00" },
            )
        }
        val (request, gga) = withTimeout(5000) { serverJob.await() }
        assertTrue(request.first().startsWith("GET /TEST HTTP/1.0"))
        assertEquals("\$GNGGA,demo*00", gga)
        withTimeout(2000) { while (received.size < rtcm.size) delay(10) }
        session.cancel()
        assertContentEquals(rtcm.toList(), received.toList())
        assertTrue(client.stats.value.bytesReceived >= rtcm.size.toLong())
    }

    @Test fun `sourcetable fetch parses mountpoints`() = runBlocking {
        val server = ServerSocket(0)
        val body = "STR;AAA;Id;RTCM 3.2;;2;GPS;N;ESP;40.0;-3.0;1;1;g;none;B;N;9600;\r\nENDSOURCETABLE\r\n"
        val serverJob = launch(Dispatchers.IO) {
            val s = server.accept()
            s.getInputStream().bufferedReader().let { r ->
                generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            }
            s.getOutputStream().write(
                ("SOURCETABLE 200 OK\r\nContent-Length: ${body.length}\r\n\r\n" + body).toByteArray()
            )
            s.getOutputStream().flush()
            s.close(); server.close()
        }
        val list = withTimeout(5000) {
            NtripClient.fetchSourceTable(NtripCredentials("127.0.0.1", server.localPort, "", ""), NtripVersion.V1)
        }
        serverJob.join()
        assertEquals(1, list.size)
        assertEquals("AAA", list[0].mountPoint)
    }
}
