package com.agroguide.rtk.ntrip

import kotlin.test.Test
import kotlin.test.assertEquals

class NtripRequestTest {
    private val creds = NtripCredentials("caster.example.com", 2101, "user", "pass")

    @Test fun `v1 stream request`() {
        val r = NtripRequest.stream(creds, "MOUNT", NtripVersion.V1)
        assertEquals(
            "GET /MOUNT HTTP/1.0\r\n" +
                "User-Agent: NTRIP AgroGuideRTK/1.0\r\n" +
                "Accept: */*\r\n" +
                "Authorization: Basic dXNlcjpwYXNz\r\n" +
                "\r\n",
            r,
        )
    }

    @Test fun `v2 stream request has Host and Ntrip-Version`() {
        val r = NtripRequest.stream(creds, "MOUNT", NtripVersion.V2)
        assertEquals(
            "GET /MOUNT HTTP/1.1\r\n" +
                "Host: caster.example.com:2101\r\n" +
                "Ntrip-Version: Ntrip/2.0\r\n" +
                "User-Agent: NTRIP AgroGuideRTK/1.0\r\n" +
                "Accept: */*\r\n" +
                "Authorization: Basic dXNlcjpwYXNz\r\n" +
                "\r\n",
            r,
        )
    }

    @Test fun `sourcetable request uses empty mountpoint and no auth when anonymous`() {
        val anon = NtripCredentials("h", 2101, "", "")
        val r = NtripRequest.sourceTable(anon, NtripVersion.V1)
        assertEquals(
            "GET / HTTP/1.0\r\nUser-Agent: NTRIP AgroGuideRTK/1.0\r\nAccept: */*\r\n\r\n",
            r,
        )
    }
}
