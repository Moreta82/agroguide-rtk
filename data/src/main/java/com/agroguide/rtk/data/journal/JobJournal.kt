package com.agroguide.rtk.data.journal

import com.agroguide.rtk.coverage.CoverageEngine
import com.agroguide.rtk.coverage.WorkPose
import com.agroguide.rtk.geo.EnuPoint
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream

data class JournalHeader(
    val originLat: Double, val originLon: Double,
    val widthM: Double, val sections: Int,
)

sealed interface JournalRecord {
    val tMs: Long

    data class Fix(
        override val tMs: Long, val x: Double, val y: Double, val headingDeg: Double,
        val speedMps: Float, val quality: Int, val sectionsMask: Int,
    ) : JournalRecord

    data class Pause(override val tMs: Long) : JournalRecord
    data class Resume(override val tMs: Long) : JournalRecord
    data class Break(override val tMs: Long) : JournalRecord
}

private const val MAGIC = 0x41474A31        // "AGJ1"
private const val T_FIX = 0x01
private const val T_PAUSE = 0x02
private const val T_RESUME = 0x03
private const val T_BREAK = 0x04

/**
 * Append-only crash-safe writer: every record is flushed to the OS so a
 * process kill loses at most the record being written; the reader drops a
 * truncated tail cleanly. Reopening an existing file appends (job resume).
 */
class JournalWriter(file: File, header: JournalHeader) : Closeable {
    private val out = DataOutputStream(BufferedOutputStream(FileOutputStream(file, true)))

    init {
        if (file.length() == 0L) {
            out.writeInt(MAGIC); out.writeByte(1)
            out.writeDouble(header.originLat); out.writeDouble(header.originLon)
            out.writeDouble(header.widthM); out.writeByte(header.sections)
            out.flush()
        }
    }

    fun fix(tMs: Long, x: Double, y: Double, headingDeg: Double, speedMps: Double, quality: Int, mask: Int) {
        out.writeByte(T_FIX); out.writeInt(tMs.toInt())
        out.writeFloat(x.toFloat()); out.writeFloat(y.toFloat()); out.writeFloat(headingDeg.toFloat())
        out.writeFloat(speedMps.toFloat()); out.writeByte(quality); out.writeShort(mask)
        out.flush()
    }

    fun pause(tMs: Long) = event(T_PAUSE, tMs)
    fun resume(tMs: Long) = event(T_RESUME, tMs)
    fun breakSegment(tMs: Long) = event(T_BREAK, tMs)

    private fun event(type: Int, tMs: Long) {
        out.writeByte(type); out.writeInt(tMs.toInt()); out.flush()
    }

    override fun close() = out.close()
}

class JournalData(val header: JournalHeader, val records: List<JournalRecord>)

object JournalReader {
    /** Null if the file is not a journal; truncated tails are dropped silently. */
    fun read(file: File): JournalData? {
        if (!file.exists() || file.length() < 30) return null
        DataInputStream(file.inputStream().buffered()).use { st ->
            if (st.readInt() != MAGIC || st.readByte().toInt() != 1) return null
            val header = JournalHeader(st.readDouble(), st.readDouble(), st.readDouble(), st.readUnsignedByte())
            val records = ArrayList<JournalRecord>()
            try {
                while (true) {
                    val type = st.read()
                    if (type < 0) break
                    val t = st.readInt().toLong()
                    when (type) {
                        T_FIX -> records += JournalRecord.Fix(
                            t, st.readFloat().toDouble(), st.readFloat().toDouble(),
                            st.readFloat().toDouble(), st.readFloat(),
                            st.readUnsignedByte(), st.readUnsignedShort(),
                        )
                        T_PAUSE -> records += JournalRecord.Pause(t)
                        T_RESUME -> records += JournalRecord.Resume(t)
                        T_BREAK -> records += JournalRecord.Break(t)
                        else -> break                    // unknown type: stop
                    }
                }
            } catch (_: EOFException) { /* truncated tail */ }
            return JournalData(header, records)
        }
    }
}

class ReplaySummary(
    val fixCount: Int,
    val trail: List<EnuPoint>,
    val activeSeconds: Long,
    val lastTMs: Long,
    val avgSpeedMps: Double,
)

object JournalReplay {
    /** Feeds all fixes into [engine]; returns trail (>=0.5 m spacing) and timing. */
    fun into(data: JournalData, engine: CoverageEngine): ReplaySummary {
        engine.widthM = data.header.widthM
        engine.sections = data.header.sections
        val trail = ArrayList<EnuPoint>()
        var fixCount = 0
        var paused = false
        var activeMs = 0L
        var spanStart = -1L
        var lastT = 0L
        var speedSum = 0.0
        for (rec in data.records) {
            lastT = rec.tMs
            when (rec) {
                is JournalRecord.Fix -> {
                    if (spanStart < 0 && !paused) spanStart = rec.tMs
                    fixCount++
                    speedSum += rec.speedMps
                    if (!paused) {
                        val mask = rec.sectionsMask
                        val active = BooleanArray(engine.sections) { (mask shr it) and 1 == 1 }
                        engine.update(WorkPose(EnuPoint(rec.x, rec.y), rec.headingDeg), active)
                    }
                    val last = trail.lastOrNull()
                    if (last == null || kotlin.math.hypot(rec.x - last.x, rec.y - last.y) >= 0.5) {
                        trail += EnuPoint(rec.x, rec.y)
                    }
                }
                is JournalRecord.Pause -> {
                    if (spanStart >= 0) { activeMs += rec.tMs - spanStart; spanStart = -1 }
                    paused = true; engine.breakSegment()
                }
                is JournalRecord.Resume -> { paused = false; spanStart = rec.tMs }
                is JournalRecord.Break -> engine.breakSegment()
            }
        }
        if (spanStart >= 0) activeMs += lastT - spanStart
        return ReplaySummary(
            fixCount, trail, activeMs / 1000,
            lastT, if (fixCount > 0) speedSum / fixCount else 0.0,
        )
    }
}
