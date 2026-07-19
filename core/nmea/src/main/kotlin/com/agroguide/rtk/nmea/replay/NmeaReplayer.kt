package com.agroguide.rtk.nmea.replay

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a list of NMEA lines at [rateHz] lines/second.
 * Lines are NOT reordered: file replay assumes the file is already in
 * receiver emission order. Uses coroutine delay -> test-friendly virtual time.
 */
class NmeaReplayer(
    private val lines: List<String>,
    private val rateHz: Double = 1.0,
    private val loop: Boolean = false,
) {
    // First line emits immediately (t=0); every later line is delayed one
    // period, including across loop passes.
    fun stream(): Flow<String> = flow {
        val periodMs = (1000.0 / rateHz).toLong()
        var first = true
        do {
            for (line in lines) {
                if (!first) delay(periodMs)
                first = false
                emit(line)
            }
        } while (loop)
    }
}
