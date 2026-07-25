package com.agroguide.rtk.coverage

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageEngineTest {
    private fun drive(e: CoverageEngine, xOffset: Double, from: Double, to: Double, sections: BooleanArray) {
        var y = from
        val step = if (to > from) 1.0 else -1.0
        val heading = if (to > from) 0.0 else 180.0
        while ((step > 0 && y <= to) || (step < 0 && y >= to)) {
            e.update(WorkPose(EnuPoint(xOffset, y), heading), sections)
            y += step
        }
        e.breakSegment()   // lifting the implement / new pass
    }

    @Test fun `single straight pass area is width times length`() {
        val e = CoverageEngine(widthM = 6.0, sections = 1)
        drive(e, 0.0, 0.0, 100.0, booleanArrayOf(true))
        assertEquals(600.0, e.stats.treatedM2, 1.0)
        assertEquals(0.0, e.stats.overlapM2, 0.5)
        assertEquals(100.0, e.stats.distanceM, 0.01)
    }

    @Test fun `retracing the same pass overlaps fully`() {
        val e = CoverageEngine(6.0, 1)
        drive(e, 0.0, 0.0, 100.0, booleanArrayOf(true))
        drive(e, 0.0, 100.0, 0.0, booleanArrayOf(true))
        assertEquals(600.0, e.stats.treatedM2, 2.0)      // no new area
        assertEquals(600.0, e.stats.overlapM2, 2.0)      // all of it overlapped
    }

    @Test fun `adjacent pass at exact width has no overlap, at width-1 overlaps a strip`() {
        val e = CoverageEngine(6.0, 1)
        drive(e, 0.0, 0.0, 100.0, booleanArrayOf(true))
        drive(e, 6.0, 100.0, 0.0, booleanArrayOf(true))
        assertEquals(1200.0, e.stats.treatedM2, 2.0)
        assertTrue(e.stats.overlapM2 < 1.0)

        val e2 = CoverageEngine(6.0, 1)
        drive(e2, 0.0, 0.0, 100.0, booleanArrayOf(true))
        drive(e2, 5.0, 100.0, 0.0, booleanArrayOf(true))
        assertEquals(100.0, e2.stats.overlapM2, 2.0)     // 1 m x 100 m strip
    }

    @Test fun `inactive sections do not paint`() {
        val e = CoverageEngine(6.0, 3)
        drive(e, 0.0, 0.0, 100.0, booleanArrayOf(true, false, true))
        assertEquals(400.0, e.stats.treatedM2, 1.0)      // 2 of 3 sections x 2 m x 100 m
    }

    @Test fun `crossing tile boundaries does not double count`() {
        val e = CoverageEngine(6.0, 1)
        drive(e, 0.0, -100.0, 100.0, booleanArrayOf(true))   // crosses several 50 m tiles
        assertEquals(1200.0, e.stats.treatedM2, 2.0)
        assertTrue(e.tiles().size >= 4)
    }

    @Test fun `reset clears everything`() {
        val e = CoverageEngine(6.0, 1)
        drive(e, 0.0, 0.0, 10.0, booleanArrayOf(true))
        e.reset()
        assertEquals(0.0, e.stats.treatedM2, 1e-9)
        assertEquals(0, e.tiles().size)
    }

    @Test fun `pathological geometry never throws`() {
        // A real receiver at crawl speed reports wildly unstable headings, which
        // produces bowtie and near-degenerate swaths. These used to reach JTS
        // unguarded and kill the process a few seconds after starting to move.
        val e = CoverageEngine(6.0, 3)
        val active = booleanArrayOf(true, true, true)
        val rnd = java.util.Random(42)
        var x = 0.0; var y = 0.0
        repeat(3000) { i ->
            x += rnd.nextDouble() * 0.4 - 0.2
            y += 0.2
            val heading = if (i % 7 == 0) rnd.nextDouble() * 360 else 0.0
            e.update(WorkPose(EnuPoint(x, y), heading), active)
            if (i % 50 == 0) e.update(WorkPose(EnuPoint(x, y), heading + 180), active)
        }
        assertTrue(e.stats.treatedM2 > 0.0)
        // whatever JTS could not digest was skipped, not thrown
        assertTrue(e.skippedSegments >= 0)
    }

    @Test fun `long job stays fast`() {
        val e = CoverageEngine(6.0, 1)
        val t0 = System.nanoTime()
        for (pass in 0 until 20) {           // 20 passes x 500 m = 10 km, 10k segments
            drive(e, pass * 6.0, 0.0, 500.0, booleanArrayOf(true))
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        assertTrue(seconds < 20.0, "took $seconds s")
        assertEquals(20 * 6.0 * 500.0, e.stats.treatedM2, 20.0)
    }
}
