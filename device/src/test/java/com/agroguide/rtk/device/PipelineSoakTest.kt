package com.agroguide.rtk.device

import com.agroguide.rtk.nmea.replay.SyntheticNmeaGenerator
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 4 h of NMEA at 5 Hz (72 000 epochs, 216 000 sentences) through the pipeline. */
class PipelineSoakTest {
    @Test fun `four hours of nmea parse into exactly one state per epoch`() = runBlocking {
        val gen = SyntheticNmeaGenerator(42.0, -5.0, speedMps = 3.0, headingDeg = 0.0, epochIntervalS = 0.2)
        val epochs = 72_000
        val t0 = System.nanoTime()
        val lines = ArrayList<SourceEvent>(epochs * 3)
        for (e in 0 until epochs) {
            val tMs = e * 200L
            for (line in gen.next()) lines.add(SourceEvent.Nmea(line, tMs))
        }
        val source = object : PositionSource {
            override val name = "soak"
            override val events = lines.asFlow()
        }
        val states = PositionPipeline(source).states.count()
        val seconds = (System.nanoTime() - t0) / 1e9
        println("PIPELINE SOAK: ${lines.size} sentences -> $states states in ${"%.1f".format(seconds)} s")
        assertEquals(epochs, states)
        assertTrue(seconds < 60.0, "took $seconds s")
    }
}
