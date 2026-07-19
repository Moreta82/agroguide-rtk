package com.agroguide.rtk.device

import com.agroguide.rtk.geo.GnssQuality
import com.agroguide.rtk.nmea.replay.SyntheticNmeaGenerator
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositionPipelineTest {
    @Test fun `NMEA lines become VehicleStates on a tangent plane`() = runTest {
        val gen = SyntheticNmeaGenerator(42.0, -5.0, speedMps = 4.0, headingDeg = 90.0)
        val lines = (0 until 5).flatMap { gen.next() }
        val source = object : PositionSource {
            override val name = "test"
            override val events = lines.mapIndexed { i, l ->
                SourceEvent.Nmea(l, monotonicMs = (i / 3) * 1000L)
            }.asFlow()
        }
        val pipeline = PositionPipeline(source)
        val states = pipeline.states.toList()
        assertEquals(5, states.size)
        assertEquals(GnssQuality.RTK_FIXED, states.first().quality)
        // heading east at 4 m/s -> x advances ~4 m per epoch, y stays ~0
        assertEquals(4.0, states[1].x - states[0].x, 0.1)
        assertEquals(0.0, states[1].y - states[0].y, 0.1)
        assertEquals(4.0, states[1].speedMps, 0.1)
    }

    @Test fun `outlier fix is dropped`() = runTest {
        val gen = SyntheticNmeaGenerator(42.0, -5.0, speedMps = 2.0, headingDeg = 0.0)
        val good = (0 until 3).flatMap { gen.next() }
        val jumper = SyntheticNmeaGenerator(42.1, -5.0, speedMps = 2.0)  // ~11 km away
        val bad = jumper.next()
        val lines = good.take(6) + bad + good.drop(6)
        val source = object : PositionSource {
            override val name = "test"
            override val events = lines.mapIndexed { i, l -> SourceEvent.Nmea(l, i * 300L) }.asFlow()
        }
        val states = PositionPipeline(source).states.toList()
        assertEquals(3, states.size)  // 3 good epochs; outlier epoch dropped
        assertTrue(states.all { kotlin.math.abs(it.latDeg - 42.0) < 0.01 })
    }
}
