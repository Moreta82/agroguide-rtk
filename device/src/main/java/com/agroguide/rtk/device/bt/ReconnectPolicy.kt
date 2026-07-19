package com.agroguide.rtk.device.bt

/** Exponential backoff: 1s, 2s, 4s ... capped at 60s. reset() after a healthy connection. */
class ReconnectPolicy(private val baseMs: Long = 1000, private val capMs: Long = 60_000) {
    private var attempt = 0
    fun nextDelayMs(): Long {
        val d = (baseMs shl attempt.coerceAtMost(6)).coerceAtMost(capMs)
        attempt++
        return d
    }
    fun reset() { attempt = 0 }
}
