package com.agroguide.rtk.nmea

/**
 * Turns an arbitrary byte-chunk stream (BT SPP reads) into complete NMEA lines.
 * Bytes before a '$' are discarded (RTCM echoes, UBX ACKs, noise).
 * Lines longer than [maxLine] without a terminator reset the buffer.
 */
class NmeaLineAssembler(private val maxLine: Int = 256) {
    private val buf = StringBuilder()

    fun feed(chunk: ByteArray): List<String> {
        val out = mutableListOf<String>()
        for (b in chunk) {
            val c = b.toInt().toChar()
            when {
                c == '$' -> { buf.setLength(0); buf.append(c) }
                c == '\n' || c == '\r' -> {
                    if (buf.isNotEmpty()) { out += buf.toString(); buf.setLength(0) }
                }
                buf.isNotEmpty() -> {
                    buf.append(c)
                    if (buf.length > maxLine) buf.setLength(0)
                }
                // else: byte outside any sentence — drop
            }
        }
        return out
    }
}
