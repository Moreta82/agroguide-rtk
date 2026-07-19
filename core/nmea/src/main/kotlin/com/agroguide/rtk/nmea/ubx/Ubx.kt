package com.agroguide.rtk.nmea.ubx

/** UBX binary protocol framing (u-blox ZED-F9P). */
object Ubx {
    /** Wrap class/id/payload with sync chars and 8-bit Fletcher checksum. */
    fun frame(cls: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0xB5.toByte(); out[1] = 0x62
        out[2] = cls.toByte(); out[3] = id.toByte()
        out[4] = (payload.size and 0xFF).toByte()
        out[5] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, 6)
        var a = 0; var b = 0
        for (i in 2 until 6 + payload.size) {
            a = (a + (out[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        out[6 + payload.size] = a.toByte()
        out[7 + payload.size] = b.toByte()
        return out
    }
}

/**
 * CFG-VALSET builder for the receiver settings AgroGuide manages.
 * Layers: RAM(1) | FLASH(4) so the change survives power cycles.
 */
object UbxValSet {
    private const val LAYER_RAM_FLASH = 0x05
    const val KEY_RATE_MEAS = 0x30210001          // U2, ms per measurement
    const val KEY_MSGOUT_GSV_UART1 = 0x209100C5   // U1, msgs per epoch
    const val KEY_MSGOUT_GSV_UART2 = 0x209100C6   // U1

    /** 5 Hz (or given rate), GSV off on both UARTs; GGA/RMC/VTG stay at default 1. */
    fun optimize(rateHz: Int = 5): ByteArray = valset(
        listOf(
            KeyValue(KEY_RATE_MEAS, 1000 / rateHz, 2),
            KeyValue(KEY_MSGOUT_GSV_UART1, 0, 1),
            KeyValue(KEY_MSGOUT_GSV_UART2, 0, 1),
        )
    )

    /** Back to receiver defaults for the keys we touch: 1 Hz, GSV enabled. */
    fun restoreDefaults(): ByteArray = valset(
        listOf(
            KeyValue(KEY_RATE_MEAS, 1000, 2),
            KeyValue(KEY_MSGOUT_GSV_UART1, 1, 1),
            KeyValue(KEY_MSGOUT_GSV_UART2, 1, 1),
        )
    )

    data class KeyValue(val key: Int, val value: Int, val valueBytes: Int)

    private fun valset(kvs: List<KeyValue>): ByteArray {
        val body = ArrayList<Byte>(4 + kvs.sumOf { 4 + it.valueBytes })
        body += 0x00; body += LAYER_RAM_FLASH.toByte(); body += 0x00; body += 0x00
        for (kv in kvs) {
            for (i in 0 until 4) body += ((kv.key shr (8 * i)) and 0xFF).toByte()
            for (i in 0 until kv.valueBytes) body += ((kv.value shr (8 * i)) and 0xFF).toByte()
        }
        return Ubx.frame(0x06, 0x8A, body.toByteArray())
    }
}
