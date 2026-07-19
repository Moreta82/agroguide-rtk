package com.agroguide.rtk.coverage

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accelerated 4-hour job: 4 h at 5 Hz = 72 000 segments. Serpentine of 36
 * passes x 1200 m at 0.6 m/segment (2000 segments per pass), adjacent passes
 * at exactly the implement width. Budgets: wall < 120 s, heap delta < 300 MB.
 */
class SoakTest {
    @Test fun `four hour job stays within time and memory budget`() {
        val rt = Runtime.getRuntime()
        System.gc()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        val engine = CoverageEngine(widthM = 6.0, sections = 1)
        val t0 = System.nanoTime()

        val passes = 36
        val segmentsPerPass = 2000
        val stepM = 0.6
        for (pass in 0 until passes) {
            val x = pass * 6.0
            val south = pass % 2 == 1
            val heading = if (south) 180.0 else 0.0
            for (i in 0..segmentsPerPass) {
                val y = if (south) (segmentsPerPass - i) * stepM else i * stepM
                engine.update(WorkPose(EnuPoint(x, y), heading), booleanArrayOf(true))
            }
            engine.breakSegment()
        }

        val seconds = (System.nanoTime() - t0) / 1e9
        System.gc()
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        val heapDeltaMb = (heapAfter - heapBefore) / 1024.0 / 1024.0

        val expected = passes * 6.0 * (segmentsPerPass * stepM)
        println("SOAK: ${passes * segmentsPerPass} segments in ${"%.1f".format(seconds)} s, " +
            "heap delta ${"%.0f".format(heapDeltaMb)} MB, tiles ${engine.tiles().size}, " +
            "treated ${"%.0f".format(engine.stats.treatedM2)} m2, " +
            "overlap ${"%.1f".format(engine.stats.overlapM2)} m2")

        assertEquals(expected, engine.stats.treatedM2, 50.0)
        assertTrue(engine.stats.overlapM2 < expected * 0.01, "overlap ${engine.stats.overlapM2}")
        assertTrue(seconds < 120.0, "took $seconds s")
        assertTrue(heapDeltaMb < 300.0, "heap delta $heapDeltaMb MB")
    }
}
