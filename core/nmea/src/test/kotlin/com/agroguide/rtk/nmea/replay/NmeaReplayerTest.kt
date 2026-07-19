package com.agroguide.rtk.nmea.replay

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NmeaReplayerTest {
    @Test fun `emits lines paced at requested rate`() = runTest {
        val lines = listOf("\$A*00", "\$B*00", "\$C*00", "\$D*00")
        val replayer = NmeaReplayer(lines, rateHz = 2.0)   // 500 ms per line
        val stamped = mutableListOf<Pair<Long, String>>()
        val t0 = currentTime
        replayer.stream().collect { stamped += (currentTime - t0) to it }
        assertEquals(4, stamped.size)
        assertEquals(listOf(0L, 500L, 1000L, 1500L), stamped.map { it.first })
    }

    @Test fun `loops forever when loop=true`() = runTest {
        val replayer = NmeaReplayer(listOf("\$A*00"), rateHz = 10.0, loop = true)
        val collected = mutableListOf<String>()
        val job = launch { replayer.stream().collect { collected += it } }
        advanceTimeBy(1000); job.cancel()
        assertTrue(collected.size >= 9)
    }
}
