package com.agroguide.rtk.nmea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NmeaLineAssemblerTest {
    @Test fun `reassembles lines across chunk boundaries`() {
        val asm = NmeaLineAssembler()
        val out = mutableListOf<String>()
        out += asm.feed("\$GNGGA,1".toByteArray())
        out += asm.feed("23519.00,A*7F\r\n\$GNRMC".toByteArray())
        assertEquals(listOf("\$GNGGA,123519.00,A*7F"), out)
        out.clear()
        out += asm.feed(",x*11\r\n".toByteArray())
        assertEquals(listOf("\$GNRMC,x*11"), out)
    }

    @Test fun `drops oversized garbage without newline`() {
        val asm = NmeaLineAssembler()
        assertTrue(asm.feed(ByteArray(2000) { 'A'.code.toByte() }).isEmpty())
        // buffer must have been reset: a normal line still comes through
        assertEquals(listOf("\$X*00"), asm.feed("\$X*00\r\n".toByteArray()))
    }

    @Test fun `binary RTCM noise between sentences is ignored`() {
        val asm = NmeaLineAssembler()
        val lines = asm.feed(byteArrayOf(0xD3.toByte(), 0x00, 0x13) + "\$X*00\r\n".toByteArray())
        assertEquals(listOf("\$X*00"), lines)
    }
}
