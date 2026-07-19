package com.agroguide.rtk.nmea.ubx

import kotlin.test.Test
import kotlin.test.assertEquals

class UbxAckScannerTest {
    // ACK-ACK for CFG-VALSET: B5 62 05 01 02 00 06 8A + checksum (irrelevant to scanner)
    private val ackAck = byteArrayOf(
        0xB5.toByte(), 0x62, 0x05, 0x01, 0x02, 0x00, 0x06, 0x8A.toByte(), 0x98.toByte(), 0xC1.toByte(),
    )
    private val ackNak = byteArrayOf(
        0xB5.toByte(), 0x62, 0x05, 0x00, 0x02, 0x00, 0x06, 0x8A.toByte(), 0x97.toByte(), 0xBA.toByte(),
    )

    @Test fun `detects ACK and NAK in one chunk`() {
        val s = UbxAckScanner()
        assertEquals(listOf(UbxAck(true, 0x06, 0x8A)), s.feed(ackAck))
        assertEquals(listOf(UbxAck(false, 0x06, 0x8A)), s.feed(ackNak))
    }

    @Test fun `detects ACK split across chunks and interleaved with NMEA text`() {
        val s = UbxAckScanner()
        val part1 = "\$GNGGA,x*00\r\n".toByteArray() + ackAck.copyOfRange(0, 4)
        val part2 = ackAck.copyOfRange(4, ackAck.size) + "\$GNRMC".toByteArray()
        assertEquals(emptyList(), s.feed(part1))
        assertEquals(listOf(UbxAck(true, 0x06, 0x8A)), s.feed(part2))
    }
}
