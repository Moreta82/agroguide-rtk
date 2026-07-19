package com.agroguide.rtk.device.bt

import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectPolicyTest {
    @Test fun `backoff doubles from 1s and caps at 60s`() {
        val p = ReconnectPolicy()
        assertEquals(1000L, p.nextDelayMs())
        assertEquals(2000L, p.nextDelayMs())
        assertEquals(4000L, p.nextDelayMs())
        repeat(10) { p.nextDelayMs() }
        assertEquals(60_000L, p.nextDelayMs())
    }

    @Test fun `success resets backoff`() {
        val p = ReconnectPolicy()
        p.nextDelayMs(); p.nextDelayMs()
        p.reset()
        assertEquals(1000L, p.nextDelayMs())
    }
}
