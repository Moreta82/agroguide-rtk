package com.agroguide.rtk.coverage

import com.agroguide.rtk.geo.CvProjector
import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.GnssQuality
import com.agroguide.rtk.geo.VehicleState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the field report "painting has gaps and overlap is never
 * coloured". Replays what the engine receives from a real receiver: 5 Hz fixes
 * with ~1.5 cm noise, 15 Hz CV-projected states in between, a headland stop,
 * and a return pass overlapping the first by 1 m.
 *
 * With the legacy JTS overlay this replay lost ~45 % of segments to
 * TopologyException (648 m² painted, 9 m² overlap). Snap-rounded OverlayNG
 * must paint all of it.
 */
class FieldReplayInvestigationTest {

    private fun state(x: Double, y: Double, h: Double, v: Double, t: Long) =
        VehicleState(x, y, 0.0, 0.0, h, v, GnssQuality.RTK_FIXED, 12, 0.6, 1.0, t, false)

    @Test fun `noisy real-world pass paints continuously and detects the overlap`() {
        val rnd = java.util.Random(7)
        val engine = CoverageEngine(widthM = 6.0, sections = 1)
        val projector = CvProjector()
        var t = 0L
        val feed = mutableListOf<VehicleState>()

        fun drive(x0: Double, y0: Double, headingDeg: Double, meters: Double, speed: Double) {
            val steps = (meters / (speed * 0.2)).toInt()
            for (i in 0..steps) {
                val d = i * speed * 0.2
                val h = headingDeg * PI / 180
                val fix = state(
                    x0 + d * sin(h) + rnd.nextGaussian() * 0.015,
                    y0 + d * cos(h) + rnd.nextGaussian() * 0.015,
                    headingDeg + rnd.nextGaussian() * 0.5, speed, t,
                )
                projector.onFix(fix); feed += fix
                feed += projector.projectAt(t + 67)!!; feed += projector.projectAt(t + 133)!!
                t += 200
            }
        }
        fun stop(x: Double, y: Double, seconds: Int) {
            repeat(seconds * 5) {
                feed += state(x + rnd.nextGaussian() * 0.015, y + rnd.nextGaussian() * 0.015, 0.0, 0.02, t)
                t += 200
            }
        }

        drive(0.0, 0.0, 90.0, 100.0, 2.0)        // pass 1: east, 100 m x 6 m
        stop(100.0, 0.0, 5)                       // headland stop
        drive(100.0, -5.0, 270.0, 100.0, 2.0)    // pass 2: west, 5 m apart -> 1 m overlap

        for (s in feed) {
            if (s.speedMps > 0.2) engine.update(WorkPose(EnuPoint(s.x, s.y), s.headingDeg), booleanArrayOf(true))
            else engine.breakSegment()
        }

        assertEquals(0, engine.skippedSegments, "segments rejected by JTS: ${engine.lastError}")
        assertEquals(1100.0, engine.stats.treatedM2, 15.0)     // 2 x 600 m² minus 100 m² shared
        assertEquals(100.0, engine.stats.overlapM2, 8.0)       // the 1 m x 100 m strip
    }
}
