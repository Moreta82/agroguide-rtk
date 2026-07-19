package com.agroguide.rtk.ntrip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ChunkedDecoderTest {
    @Test fun `decodes chunks across feed boundaries`() {
        val d = ChunkedDecoder()
        val out = mutableListOf<Byte>()
        out += d.feed("4\r\nAB".toByteArray()).toList()
        out += d.feed("CD\r\n3\r\nEF".toByteArray()).toList()
        out += d.feed("G\r\n0\r\n\r\n".toByteArray()).toList()
        assertContentEquals("ABCDEFG".toByteArray().toList(), out)
        assertTrue(d.finished)
    }

    @Test fun `handles chunk size split across feeds`() {
        val d = ChunkedDecoder()
        val out = mutableListOf<Byte>()
        out += d.feed("1".toByteArray()).toList()
        out += d.feed("0\r\n".toByteArray()).toList()          // size 0x10 = 16
        out += d.feed(ByteArray(16) { 0x55 }).toList()
        out += d.feed("\r\n".toByteArray()).toList()
        assertContentEquals(List(16) { 0x55.toByte() }, out)
    }
}
