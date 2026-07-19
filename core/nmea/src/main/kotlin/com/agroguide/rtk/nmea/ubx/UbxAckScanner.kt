package com.agroguide.rtk.nmea.ubx

data class UbxAck(val ok: Boolean, val ackedCls: Int, val ackedId: Int)

/**
 * Scans a raw byte stream for UBX-ACK-ACK (05 01) / ACK-NAK (05 00) frames,
 * tolerant of NMEA/RTCM bytes around them and of chunk splits. Checksums are
 * not verified — a spoofed ACK on a point-to-point serial link is not a threat.
 */
class UbxAckScanner {
    private val window = ArrayDeque<Byte>()

    fun feed(chunk: ByteArray): List<UbxAck> {
        val out = mutableListOf<UbxAck>()
        for (b in chunk) {
            window.addLast(b)
            if (window.size > 10) window.removeFirst()
            if (window.size == 10) {
                val w = window.toList()
                val isAck = w[0] == 0xB5.toByte() && w[1] == 0x62.toByte() &&
                    w[2] == 0x05.toByte() && (w[3] == 0x01.toByte() || w[3] == 0x00.toByte()) &&
                    w[4] == 0x02.toByte() && w[5] == 0x00.toByte()
                if (isAck) {
                    out += UbxAck(w[3] == 0x01.toByte(), w[6].toInt() and 0xFF, w[7].toInt() and 0xFF)
                    window.clear()
                }
            }
        }
        return out
    }
}
