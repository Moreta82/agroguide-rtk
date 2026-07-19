package com.agroguide.rtk.nmea.ubx

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class UbxTest {
    @Test fun `frame wraps payload with sync and fletcher checksum`() {
        // CFG-VALSET (06 8A), RAM layer, CFG-RATE-MEAS(0x30210001)=200ms.
        // Hand-computed: B5 62 06 8A 0A 00 00 01 00 00 01 00 21 30 C8 00 B5 81
        val payload = byteArrayOf(
            0x00, 0x01, 0x00, 0x00,                          // version, layers=RAM, reserved
            0x01, 0x00, 0x21, 0x30,                          // key 0x30210001 LE
            0xC8.toByte(), 0x00,                             // value 200 (U2 LE)
        )
        val frame = Ubx.frame(0x06, 0x8A, payload)
        assertContentEquals(
            byteArrayOf(
                0xB5.toByte(), 0x62, 0x06, 0x8A.toByte(), 0x0A, 0x00,
                0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x21, 0x30, 0xC8.toByte(), 0x00,
                0xB5.toByte(), 0x81.toByte(),
            ),
            frame,
        )
    }

    @Test fun `valset optimize sets 5Hz and disables GSV on both UARTs, RAM plus flash`() {
        val frame = UbxValSet.optimize(rateHz = 5)
        assertEquals(0xB5.toByte(), frame[0]); assertEquals(0x62.toByte(), frame[1])
        assertEquals(0x06.toByte(), frame[2]); assertEquals(0x8A.toByte(), frame[3])
        val payload = frame.copyOfRange(6, frame.size - 2)
        assertEquals(0x00, payload[0].toInt())          // version
        assertEquals(0x05, payload[1].toInt())          // layers RAM|FLASH
        // cfgData: RATE_MEAS(U2)=200, GSV_UART1(U1)=0, GSV_UART2(U1)=0 -> 4+2 + 4+1 + 4+1 = 16 bytes
        assertEquals(4 + 16, payload.size)
        val body = payload.copyOfRange(4, payload.size)
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x21, 0x30, 0xC8.toByte(), 0x00), body.copyOfRange(0, 6))
        assertContentEquals(byteArrayOf(0xC5.toByte(), 0x00, 0x91.toByte(), 0x20, 0x00), body.copyOfRange(6, 11))
        assertContentEquals(byteArrayOf(0xC6.toByte(), 0x00, 0x91.toByte(), 0x20, 0x00), body.copyOfRange(11, 16))
    }

    @Test fun `valset restoreDefaults sets 1Hz and reenables GSV`() {
        val payload = UbxValSet.restoreDefaults().let { it.copyOfRange(6, it.size - 2) }
        assertEquals(0x05, payload[1].toInt())
        val body = payload.copyOfRange(4, payload.size)
        // RATE_MEAS=1000 (0x03E8)
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x21, 0x30, 0xE8.toByte(), 0x03), body.copyOfRange(0, 6))
        assertEquals(0x01, body[10].toInt())   // GSV_UART1 = 1
        assertEquals(0x01, body[15].toInt())   // GSV_UART2 = 1
    }

    @Test fun `checksum survives high bytes`() {
        val f = Ubx.frame(0x05, 0x01, byteArrayOf(0x06, 0x8A.toByte()))
        var a = 0; var b = 0
        for (x in byteArrayOf(0x05, 0x01, 0x02, 0x00, 0x06, 0x8A.toByte())) {
            a = (a + (x.toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF
        }
        assertEquals(a.toByte(), f[f.size - 2]); assertEquals(b.toByte(), f[f.size - 1])
    }
}
