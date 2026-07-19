package com.agroguide.rtk.ntrip

/** Incremental HTTP/1.1 chunked-transfer decoder for NTRIP v2 streams. */
class ChunkedDecoder {
    private enum class S { SIZE, DATA, DATA_CRLF, DONE }
    private var state = S.SIZE
    private val sizeBuf = StringBuilder()
    private var remaining = 0
    var finished = false; private set

    fun feed(chunk: ByteArray): ByteArray {
        val out = ArrayList<Byte>(chunk.size)
        for (b in chunk) {
            when (state) {
                S.SIZE -> {
                    val c = b.toInt().toChar()
                    if (c == '\n') {
                        remaining = sizeBuf.toString().trim().toIntOrNull(16) ?: 0
                        sizeBuf.setLength(0)
                        if (remaining == 0) { state = S.DONE; finished = true } else state = S.DATA
                    } else if (c != '\r') sizeBuf.append(c)
                }
                S.DATA -> {
                    out += b
                    if (--remaining == 0) state = S.DATA_CRLF
                }
                S.DATA_CRLF -> if (b.toInt().toChar() == '\n') state = S.SIZE
                S.DONE -> {}
            }
        }
        return out.toByteArray()
    }
}
