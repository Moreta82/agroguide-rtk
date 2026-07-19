package com.agroguide.rtk.data.journal

import com.agroguide.rtk.coverage.CoverageEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobJournalTest {
    private fun tmp() = File.createTempFile("job", ".journal").apply { deleteOnExit() }

    private fun header() = JournalHeader(
        originLat = 42.0, originLon = -5.0, widthM = 6.0, sections = 1,
    )

    @Test fun `write and read roundtrip`() {
        val f = tmp()
        JournalWriter(f, header()).use { w ->
            w.fix(1000, 0.0, 0.0, 0.0, 3.0, 4, 0b1)
            w.pause(2000)
            w.resume(5000)
            w.fix(6000, 0.0, 5.0, 0.0, 3.0, 4, 0b1)
            w.breakSegment(7000)
        }
        val r = JournalReader.read(f)!!
        assertEquals(42.0, r.header.originLat, 1e-9)
        assertEquals(5, r.records.size)
        val fix = r.records[0] as JournalRecord.Fix
        assertEquals(1000, fix.tMs); assertEquals(3.0f, fix.speedMps)
        assertTrue(r.records[1] is JournalRecord.Pause)
        assertTrue(r.records[4] is JournalRecord.Break)
    }

    @Test fun `truncated tail is tolerated`() {
        val f = tmp()
        JournalWriter(f, header()).use { w ->
            w.fix(1000, 0.0, 0.0, 0.0, 3.0, 4, 0b1)
            w.fix(2000, 0.0, 1.0, 0.0, 3.0, 4, 0b1)
        }
        // chop 5 bytes off the end (mid-record)
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size - 5))
        val r = JournalReader.read(f)!!
        assertEquals(1, r.records.size)
    }

    @Test fun `garbage file reads null`() {
        val f = tmp(); f.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(null, JournalReader.read(f))
    }

    @Test fun `replay rebuilds coverage identical to live engine`() {
        val f = tmp()
        val live = CoverageEngine(6.0, 1)
        JournalWriter(f, header()).use { w ->
            var t = 0L
            for (y in 0..100) {
                w.fix(t, 0.0, y.toDouble(), 0.0, 3.0, 4, 0b1)
                live.update(
                    com.agroguide.rtk.coverage.WorkPose(com.agroguide.rtk.geo.EnuPoint(0.0, y.toDouble()), 0.0),
                    booleanArrayOf(true),
                )
                t += 200
            }
        }
        val replayed = CoverageEngine(6.0, 1)
        val summary = JournalReplay.into(JournalReader.read(f)!!, replayed)
        assertEquals(live.stats.treatedM2, replayed.stats.treatedM2, 0.01)
        assertEquals(live.stats.distanceM, replayed.stats.distanceM, 0.01)
        assertEquals(101, summary.fixCount)
        assertTrue(summary.trail.size in 2..101)
    }

    @Test fun `active seconds exclude paused spans`() {
        val f = tmp()
        JournalWriter(f, header()).use { w ->
            w.fix(0, 0.0, 0.0, 0.0, 3.0, 4, 1)
            w.pause(10_000)
            w.resume(70_000)
            w.fix(80_000, 0.0, 1.0, 0.0, 3.0, 4, 1)
        }
        val summary = JournalReplay.into(JournalReader.read(f)!!, CoverageEngine(6.0, 1))
        assertEquals(20, summary.activeSeconds)   // 0-10s + 70-80s
    }
}
