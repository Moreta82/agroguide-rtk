package com.agroguide.rtk.ntrip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Response heads below are verbatim captures from real casters (IGN ERGNSS
 * GNSMART and BKG casters, rtk2go SNIP), 2026-10-16.
 */
class NtripResponseTest {

    @Test fun `ICY 200 OK is success`() {
        val r = NtripResponse.classify("ICY 200 OK\r\n")
        assertTrue(r.ok); assertNull(r.failure)
    }

    @Test fun `HTTP 200 is success and detects chunked`() {
        val r = NtripResponse.classify(
            "HTTP/1.1 200 OK\r\nNtrip-Version: Ntrip/2.0\r\nTransfer-Encoding: chunked\r\n\r\n"
        )
        assertTrue(r.ok); assertTrue(r.chunked)
    }

    @Test fun `IGN GNSMART 401 is an authentication failure`() {
        val r = NtripResponse.classify(
            "HTTP/1.1 401 Unauthorized\r\nServer: GN_Caster/1.0\r\n" +
                "WWW-Authenticate: Basic realm=\"/VRS3M\"\r\nConnection: close\r\nContent-Length:      0\r\n\r\n"
        )
        assertFalse(r.ok)
        assertEquals(NtripFailure.UNAUTHORIZED, r.failure)
    }

    @Test fun `v1 style ICY 401 is an authentication failure`() {
        assertEquals(NtripFailure.UNAUTHORIZED, NtripResponse.classify("ICY 401 Unauthorized\r\n\r\n").failure)
    }

    @Test fun `SOURCETABLE answer to a stream request means the mountpoint does not exist`() {
        // IGN BKG caster reply to GET /NOEXISTE — it contains "200" but is NOT a stream.
        val r = NtripResponse.classify(
            "SOURCETABLE 200 OK\r\nServer: NTRIP BKG Caster 2.0.45/2.0\r\n" +
                "Connection: close\r\nContent-Type: text/plain\r\nContent-Length: 27569\r\n\r\n"
        )
        assertFalse(r.ok)
        assertEquals(NtripFailure.MOUNTPOINT_NOT_FOUND, r.failure)
    }

    @Test fun `404 means the mountpoint does not exist`() {
        assertEquals(NtripFailure.MOUNTPOINT_NOT_FOUND, NtripResponse.classify("HTTP/1.1 404 Not Found\r\n\r\n").failure)
    }

    @Test fun `403 is forbidden`() {
        assertEquals(NtripFailure.FORBIDDEN, NtripResponse.classify("HTTP/1.1 403 Forbidden\r\n\r\n").failure)
    }

    @Test fun `rtk2go start-up 406 is a rejection that keeps the server text`() {
        val r = NtripResponse.classify("HTTP/1.1 406\r\nContent-Type: text/html\r\nConnection: Closed\r\n\r\n")
        assertFalse(r.ok)
        assertEquals(NtripFailure.REJECTED, r.failure)
        assertEquals("HTTP/1.1 406", r.statusLine)
    }

    @Test fun `empty answer is a rejection`() {
        assertEquals(NtripFailure.REJECTED, NtripResponse.classify("").failure)
    }

    @Test fun `endpoint input is normalized`() {
        assertEquals(NtripEndpoint("ergnss-tr.ign.es", 2101), NtripEndpoint.parse(" http://ergnss-tr.ign.es/ ", 2101))
        assertEquals(NtripEndpoint("ergnss-tr.ign.es", 2102), NtripEndpoint.parse("ergnss-tr.ign.es:2102", 2101))
        assertEquals(NtripEndpoint("rtk2go.com", 2101), NtripEndpoint.parse("RTK2GO.COM", 2101))
    }

    @Test fun `mountpoint input is normalized`() {
        assertEquals("VRS3M", NtripEndpoint.normalizeMount(" /VRS3M "))
        assertEquals("LEON0", NtripEndpoint.normalizeMount("LEON0"))
    }
}
