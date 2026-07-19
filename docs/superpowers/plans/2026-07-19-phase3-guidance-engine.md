# AgroGuide RTK — Phase 3 Implementation Plan (guidance engine + canvas field view)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AB / A+ / recorded-curve / free guidance with signed cross-track error, pass auto-snap, implement offsets, a glove-readable lightbar, and the offline canvas field view as the primary Guidance display.

**Architecture:** All geometry is pure Kotlin in `:core:geo` package `guidance` (TDD): work-point computation from implement config, AB/A+ line math, curve nearest-segment math, pass snapping, driver-frame XTE. `:service` gains `GuidanceRepository` (singleton like PositionRepository): holds active line, mode, implement config and trail; combines vehicle states into a `StateFlow<GuidanceResult?>`. `:app` gains the field view (Compose Canvas, heading-up camera), the lightbar, line-creation buttons, an implement-config dialog (DataStore-persisted), and moves source selection into a dialog so the canvas owns the Guidance tab.

**Conventions (fixed, used by tests, engine and UI):**
- Heading: compass degrees, 0 = north, 90 = east. Forward vector f = (sin h, cos h); right vector r = (cos h, −sin h).
- XTE sign: positive = work point is RIGHT of the line (in the direction of travel, driver frame). Lightbar is **drive-to**: LEDs light on the side you must steer toward; XTE > 0 → steer left → left LEDs lit.
- Work point = antenna − f·longOffset + r·latOffset (longOffset > 0 = implement behind antenna; latOffset > 0 = implement shifted right).
- Pass k center sits at signed offset k·width from the base line; k = round(offset/width); XTE is relative to the nearest pass.
- Anti-parallel driving (returning pass): target heading flips 180° and field-frame XTE negates to stay in driver frame.

**Environment:** unchanged (JAVA_HOME → Android Studio JBR; `.\gradlew.bat` from repo root).

---

### Task 1: `:core:geo` — implement config + work point (TDD)

**Files:**
- Create: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/guidance/ImplementConfig.kt`
- Test: `core/geo/src/test/kotlin/com/agroguide/rtk/geo/guidance/WorkPointTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkPointTest {
    @Test fun `no offsets means work point equals antenna`() {
        val c = ImplementConfig(widthM = 6.0)
        val w = c.workPoint(EnuPoint(10.0, 20.0), headingDeg = 45.0)
        assertEquals(10.0, w.x, 1e-9); assertEquals(20.0, w.y, 1e-9)
    }

    @Test fun `longitudinal offset shifts work point backwards along heading`() {
        val c = ImplementConfig(widthM = 6.0, longitudinalOffsetM = 3.0)
        val w = c.workPoint(EnuPoint(0.0, 0.0), headingDeg = 0.0)   // facing north
        assertEquals(0.0, w.x, 1e-9); assertEquals(-3.0, w.y, 1e-9) // 3 m south
    }

    @Test fun `lateral offset shifts work point to the right of heading`() {
        val c = ImplementConfig(widthM = 6.0, lateralOffsetM = 1.5)
        val w = c.workPoint(EnuPoint(0.0, 0.0), headingDeg = 0.0)   // facing north
        assertEquals(1.5, w.x, 1e-9); assertEquals(0.0, w.y, 1e-9)  // 1.5 m east
    }

    @Test fun `offsets rotate with heading`() {
        val c = ImplementConfig(widthM = 6.0, lateralOffsetM = 2.0, longitudinalOffsetM = 4.0)
        val w = c.workPoint(EnuPoint(0.0, 0.0), headingDeg = 90.0)  // facing east
        // behind = west (-4 east), right of east = south (-2 north)
        assertEquals(-4.0, w.x, 1e-9); assertEquals(-2.0, w.y, 1e-9)
    }
}
```

- [ ] **Step 2: Run `:core:geo:test` → compile failure. Implement:**

```kotlin
package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Implement geometry relative to the GNSS antenna.
 * [lateralOffsetM] > 0: implement center is right of the antenna.
 * [longitudinalOffsetM] > 0: implement works behind the antenna.
 */
data class ImplementConfig(
    val widthM: Double,
    val lateralOffsetM: Double = 0.0,
    val longitudinalOffsetM: Double = 0.0,
    val sections: Int = 1,               // used from phase 4
) {
    /** Point on the ground the implement actually works at, given antenna pose. */
    fun workPoint(antenna: EnuPoint, headingDeg: Double): EnuPoint {
        val h = headingDeg * PI / 180
        val fx = sin(h); val fy = cos(h)          // forward
        val rx = cos(h); val ry = -sin(h)         // right
        return EnuPoint(
            antenna.x - fx * longitudinalOffsetM + rx * lateralOffsetM,
            antenna.y - fy * longitudinalOffsetM + ry * lateralOffsetM,
        )
    }
}
```

- [ ] **Step 3: Green. Commit** `feat(geo): implement config and work-point offsets`

---

### Task 2: `:core:geo` — AB/A+ lines, pass snap, driver-frame XTE (TDD)

**Files:**
- Create: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/guidance/GuidanceLine.kt`
- Test: `core/geo/src/test/kotlin/com/agroguide/rtk/geo/guidance/AbGuidanceTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AbGuidanceTest {
    // Base line: A=(0,0) B=(0,100) → pointing north. Width 6 m.
    private val line = GuidanceLine.Ab(EnuPoint(0.0, 0.0), EnuPoint(0.0, 100.0))
    private val width = 6.0

    @Test fun `on the line, xte is zero, pass 0`() {
        val g = line.guide(EnuPoint(0.0, 50.0), headingDeg = 0.0, widthM = width)!!
        assertEquals(0.0, g.xteM, 1e-9)
        assertEquals(0, g.passIndex)
        assertEquals(0.0, g.targetHeadingDeg, 1e-9)
    }

    @Test fun `2 m east of line driving north gives positive xte (steer left)`() {
        val g = line.guide(EnuPoint(2.0, 50.0), 0.0, width)!!
        assertEquals(2.0, g.xteM, 1e-9)
        assertEquals(0, g.passIndex)
    }

    @Test fun `snaps to nearest pass`() {
        // 13 m east = pass 2 (12 m) plus 1 m
        val g = line.guide(EnuPoint(13.0, 50.0), 0.0, width)!!
        assertEquals(2, g.passIndex)
        assertEquals(1.0, g.xteM, 1e-9)
        // 14.9 m east → pass 2 center 12, xte 2.9? round(14.9/6)=2 → 2.9
        assertEquals(2.9, line.guide(EnuPoint(14.9, 50.0), 0.0, width)!!.xteM, 1e-9)
        // 15.1 m east → pass 3 (18 m), xte -2.9
        val g3 = line.guide(EnuPoint(15.1, 50.0), 0.0, width)!!
        assertEquals(3, g3.passIndex)
        assertEquals(-2.9, g3.xteM, 1e-9)
    }

    @Test fun `driving south flips xte sign and target heading`() {
        val g = line.guide(EnuPoint(2.0, 50.0), headingDeg = 180.0, widthM = width)!!
        assertEquals(-2.0, g.xteM, 1e-9)          // east of line but on driver's LEFT now
        assertEquals(180.0, g.targetHeadingDeg, 1e-9)
    }

    @Test fun `west of line is negative pass indices`() {
        val g = line.guide(EnuPoint(-6.0, 10.0), 0.0, width)!!
        assertEquals(-1, g.passIndex)
        assertEquals(0.0, g.xteM, 1e-9)
    }

    @Test fun `A plus builds the same geometry from point and heading`() {
        val ap = GuidanceLine.APlus(EnuPoint(0.0, 0.0), headingDeg = 0.0)
        val g = ap.guide(EnuPoint(2.0, 50.0), 0.0, width)!!
        assertEquals(2.0, g.xteM, 1e-9)
    }

    @Test fun `degenerate AB (too short) guides null`() {
        val bad = GuidanceLine.Ab(EnuPoint(0.0, 0.0), EnuPoint(0.0, 0.5))
        assertNull(bad.guide(EnuPoint(1.0, 0.0), 0.0, width))
    }

    @Test fun `diagonal line target heading follows line azimuth`() {
        val diag = GuidanceLine.Ab(EnuPoint(0.0, 0.0), EnuPoint(100.0, 100.0)) // NE, 45 deg
        // wide implement (30 m) so pass stays 0 and xte is the raw perpendicular offset
        val g = diag.guide(EnuPoint(0.0, 10.0), headingDeg = 45.0, widthM = 30.0)!!
        assertEquals(45.0, g.targetHeadingDeg, 1e-9)
        assertEquals(0, g.passIndex)
        // (0,10) relative to NE line: left side → negative xte = -10*sin(45)
        assertEquals(-10.0 * kotlin.math.sin(kotlin.math.PI / 4), g.xteM, 1e-6)
    }
}
```

- [ ] **Step 2: Run → failure. Implement `GuidanceLine.kt`:**

```kotlin
package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Result of one guidance evaluation, already in the driver's frame. */
data class GuidanceResult(
    val xteM: Double,            // + = right of pass center → steer left
    val passIndex: Int,          // 0 = base line, + to the base line's right
    val targetHeadingDeg: Double,
)

sealed interface GuidanceLine {
    /**
     * Evaluate guidance for [workPoint] with the vehicle on [headingDeg].
     * Returns null when the line is degenerate. XTE is driver-frame.
     */
    fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult?

    data class Ab(val a: EnuPoint, val b: EnuPoint) : GuidanceLine {
        override fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult? {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < MIN_AB_M) return null
            val ux = dx / len; val uy = dy / len
            return lineGuide(workPoint, headingDeg, widthM, a, ux, uy)
        }
    }

    data class APlus(val a: EnuPoint, val headingDeg: Double) : GuidanceLine {
        override fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult? {
            val h = this.headingDeg * PI / 180
            return lineGuide(workPoint, headingDeg, widthM, a, sin(h), cos(h))
        }
    }

    // data class Curve arrives in Task 3 — Task 2 ships only Ab and APlus.

    companion object {
        const val MIN_AB_M = 2.0

        /** Shared straight-line math for Ab and APlus. */
        internal fun lineGuide(
            p: EnuPoint, vehicleHeadingDeg: Double, widthM: Double,
            a: EnuPoint, ux: Double, uy: Double,
        ): GuidanceResult {
            val rx = uy; val ry = -ux                       // right normal of line direction
            val off = (p.x - a.x) * rx + (p.y - a.y) * ry   // signed field-frame offset
            val pass = (off / widthM).roundToInt()
            var xte = off - pass * widthM
            var target = (atan2(ux, uy) * 180 / PI + 360) % 360
            // driver frame: flip when driving anti-parallel to the line
            val h = vehicleHeadingDeg * PI / 180
            val alongHeading = sin(h) * ux + cos(h) * uy
            if (alongHeading < 0) {
                xte = -xte
                target = (target + 180) % 360
            }
            return GuidanceResult(xte, pass, target)
        }
    }
}
```

- [ ] **Step 3: Green (AB tests). Commit** `feat(geo): AB and A+ guidance with pass snapping and driver-frame XTE`

---

### Task 3: `:core:geo` — curve guidance + recorder (TDD)

**Files:**
- Modify: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/guidance/GuidanceLine.kt` (add `Curve` + `curveGuide`)
- Create: `core/geo/src/main/kotlin/com/agroguide/rtk/geo/guidance/CurveRecorder.kt`
- Test: `core/geo/src/test/kotlin/com/agroguide/rtk/geo/guidance/CurveGuidanceTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurveGuidanceTest {
    // L-shaped path north then east, corner at (0,100)
    private val curve = GuidanceLine.Curve(
        (0..100 step 10).map { EnuPoint(0.0, it.toDouble()) } +
            (10..100 step 10).map { EnuPoint(it.toDouble(), 100.0) }
    )

    @Test fun `near first leg behaves like the north line`() {
        val g = curve.guide(EnuPoint(2.0, 50.0), 0.0, 6.0)!!
        assertEquals(2.0, g.xteM, 1e-6)
        assertEquals(0, g.passIndex)
        assertEquals(0.0, g.targetHeadingDeg, 1e-6)
    }

    @Test fun `near second leg target heading is east`() {
        val g = curve.guide(EnuPoint(50.0, 99.0), 90.0, 6.0)!!
        assertEquals(90.0, g.targetHeadingDeg, 1e-6)
        // 1 m south of east-bound leg = right side → positive xte
        assertEquals(1.0, g.xteM, 1e-6)
    }

    @Test fun `snaps to offset passes of the curve`() {
        val g = curve.guide(EnuPoint(7.0, 50.0), 0.0, 6.0)!!   // 7 m east of north leg
        assertEquals(1, g.passIndex)
        assertEquals(1.0, g.xteM, 1e-6)
    }

    @Test fun `driving back along the curve flips the frame`() {
        val g = curve.guide(EnuPoint(2.0, 50.0), 180.0, 6.0)!!
        assertEquals(-2.0, g.xteM, 1e-6)
        assertEquals(180.0, g.targetHeadingDeg, 1e-6)
    }

    @Test fun `fewer than 2 points guides null`() {
        assertNull(GuidanceLine.Curve(listOf(EnuPoint(0.0, 0.0))).guide(EnuPoint(0.0, 0.0), 0.0, 6.0))
    }

    @Test fun `recorder enforces minimum spacing`() {
        val r = CurveRecorder(minSpacingM = 0.5)
        r.add(EnuPoint(0.0, 0.0))
        r.add(EnuPoint(0.1, 0.0))     // too close, dropped
        r.add(EnuPoint(0.6, 0.0))
        r.add(EnuPoint(1.2, 0.0))
        assertEquals(3, r.points.size)
        assertTrue(r.points.zipWithNext().all { (p, q) -> kotlin.math.hypot(q.x - p.x, q.y - p.y) >= 0.5 })
    }
}
```

- [ ] **Step 2: Run → failure. Implement.** Add inside `GuidanceLine`:

```kotlin
    data class Curve(val points: List<EnuPoint>) : GuidanceLine {
        override fun guide(workPoint: EnuPoint, headingDeg: Double, widthM: Double): GuidanceResult? {
            if (points.size < 2) return null
            // nearest segment by perpendicular projection (clamped)
            var best = Double.MAX_VALUE
            var bestUx = 0.0; var bestUy = 0.0; var bestOff = 0.0
            for (i in 0 until points.size - 1) {
                val p1 = points[i]; val p2 = points[i + 1]
                val dx = p2.x - p1.x; val dy = p2.y - p1.y
                val len2 = dx * dx + dy * dy
                if (len2 < 1e-12) continue
                val t = (((workPoint.x - p1.x) * dx + (workPoint.y - p1.y) * dy) / len2).coerceIn(0.0, 1.0)
                val cx = p1.x + t * dx; val cy = p1.y + t * dy
                val d2 = (workPoint.x - cx).let { it * it } + (workPoint.y - cy).let { it * it }
                if (d2 < best) {
                    best = d2
                    val len = kotlin.math.sqrt(len2)
                    bestUx = dx / len; bestUy = dy / len
                    // signed offset via right normal of this segment
                    bestOff = (workPoint.x - cx) * bestUy + (workPoint.y - cy) * -bestUx
                }
            }
            // reuse straight-line snapping in the local segment frame: anchor so that
            // field offset == bestOff (anchor = workPoint shifted back by bestOff along right normal)
            val anchor = EnuPoint(workPoint.x - bestOff * bestUy, workPoint.y + bestOff * bestUx)
            return lineGuide(workPoint, headingDeg, widthM, anchor, bestUx, bestUy)
        }
    }
```
`CurveRecorder.kt`:
```kotlin
package com.agroguide.rtk.geo.guidance

import com.agroguide.rtk.geo.EnuPoint
import kotlin.math.hypot

/** Collects a driven curve, dropping points closer than [minSpacingM]. */
class CurveRecorder(private val minSpacingM: Double = 0.5) {
    private val _points = mutableListOf<EnuPoint>()
    val points: List<EnuPoint> get() = _points

    fun add(p: EnuPoint) {
        val last = _points.lastOrNull()
        if (last == null || hypot(p.x - last.x, p.y - last.y) >= minSpacingM) _points += p
    }

    fun clear() = _points.clear()
}
```

- [ ] **Step 3: Green. Commit** `feat(geo): recorded-curve guidance with pass snapping and curve recorder`

*Known approximation (documented in checkpoint): curve passes are measured as perpendicular distance to the ORIGINAL curve — exact for straights and gentle bends, degrades on tight corners (offset self-intersection is not handled in v1).*

---

### Task 4: `:service` — GuidanceRepository

**Files:**
- Create: `service/src/main/java/com/agroguide/rtk/service/GuidanceRepository.kt`

- [ ] **Step 1: Implement** (thin state holder over tested math — no unit test; exercised via core tests + manual)

```kotlin
package com.agroguide.rtk.service

import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.VehicleState
import com.agroguide.rtk.geo.guidance.CurveRecorder
import com.agroguide.rtk.geo.guidance.GuidanceLine
import com.agroguide.rtk.geo.guidance.GuidanceResult
import com.agroguide.rtk.geo.guidance.ImplementConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuidanceMode { FREE, AB, A_PLUS, CURVE }

/**
 * Guidance state shared between UI and (later) job recording: active mode,
 * line, implement config, live result and the on-screen trail. update() is
 * called by the field view collector for every VehicleState — math is O(1)
 * for lines and O(n segments) for curves, trivial at 15 Hz.
 */
object GuidanceRepository {
    private val _mode = MutableStateFlow(GuidanceMode.FREE)
    val mode: StateFlow<GuidanceMode> = _mode.asStateFlow()

    private val _line = MutableStateFlow<GuidanceLine?>(null)
    val line: StateFlow<GuidanceLine?> = _line.asStateFlow()

    private val _implement = MutableStateFlow(ImplementConfig(widthM = 6.0))
    val implement: StateFlow<ImplementConfig> = _implement.asStateFlow()

    private val _result = MutableStateFlow<GuidanceResult?>(null)
    val result: StateFlow<GuidanceResult?> = _result.asStateFlow()

    private val _pendingA = MutableStateFlow<EnuPoint?>(null)
    val pendingA: StateFlow<EnuPoint?> = _pendingA.asStateFlow()

    val recorder = CurveRecorder()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _trail = MutableStateFlow<List<EnuPoint>>(emptyList())
    val trail: StateFlow<List<EnuPoint>> = _trail.asStateFlow()
    private var lastTrail: EnuPoint? = null

    fun setImplement(c: ImplementConfig) { _implement.value = c }
    fun setMode(m: GuidanceMode) { _mode.value = m; if (m == GuidanceMode.FREE) _result.value = null }

    fun markA(state: VehicleState) {
        _pendingA.value = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
    }

    fun markB(state: VehicleState): Boolean {
        val a = _pendingA.value ?: return false
        val b = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
        val line = GuidanceLine.Ab(a, b)
        if (line.guide(b, state.headingDeg, _implement.value.widthM) == null) return false
        _line.value = line; _pendingA.value = null; _mode.value = GuidanceMode.AB
        return true
    }

    fun markAPlus(state: VehicleState) {
        val a = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
        _line.value = GuidanceLine.APlus(a, state.headingDeg)
        _pendingA.value = null; _mode.value = GuidanceMode.A_PLUS
    }

    fun startCurve() { recorder.clear(); _recording.value = true }
    fun stopCurve(): Boolean {
        _recording.value = false
        if (recorder.points.size < 2) return false
        _line.value = GuidanceLine.Curve(recorder.points.toList())
        _mode.value = GuidanceMode.CURVE
        return true
    }

    fun clearLine() { _line.value = null; _result.value = null; _pendingA.value = null }

    /** Called for every VehicleState by the field-view collector. */
    fun update(state: VehicleState) {
        val work = _implement.value.workPoint(EnuPoint(state.x, state.y), state.headingDeg)
        if (_recording.value) recorder.add(work)
        val last = lastTrail
        if (last == null || kotlin.math.hypot(work.x - last.x, work.y - last.y) > 0.5) {
            lastTrail = work
            _trail.value = (_trail.value + work).let { if (it.size > 4000) it.drop(1000) else it }
        }
        _result.value = if (_mode.value == GuidanceMode.FREE) null
        else _line.value?.guide(work, state.headingDeg, _implement.value.widthM)
    }

    fun clearTrail() { _trail.value = emptyList(); lastTrail = null }
}
```

- [ ] **Step 2: Build. Commit** `feat(service): guidance repository (mode, line, implement, trail, live result)`

---

### Task 5: Implement settings persistence + dialog

**Files:**
- Create: `app/src/main/java/com/agroguide/rtk/data/ImplementSettings.kt` (same DataStore, key `implement_settings`, JSON of `{widthM, lateralOffsetM, longitudinalOffsetM}`, class `ImplementSettingsStore` mirroring `NtripProfileStore`; on app start MainActivity collects it and calls `GuidanceRepository.setImplement`)
- Create: `app/src/main/java/com/agroguide/rtk/ui/ImplementDialog.kt` (AlertDialog: three numeric OutlinedTextFields — ancho de trabajo (m), desfase lateral (m, + derecha), desfase longitudinal (m, + detrás) — Guardar persists to store AND GuidanceRepository)
- Strings: implement_title="Apero", implement_width="Ancho de trabajo (m)", implement_lateral="Desfase lateral (m, + derecha)", implement_longitudinal="Antena→apero (m, + detrás)"

Note: numeric fields accept decimal comma AND dot (`it.replace(',', '.')` before `toDoubleOrNull()`).

- [ ] **Step 1: Implement both files + strings. Build. Commit** `feat(app): implement configuration dialog persisted in DataStore`

---

### Task 6: Lightbar composable

**Files:**
- Create: `app/src/main/java/com/agroguide/rtk/ui/Lightbar.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.agroguide.rtk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drive-to lightbar: XTE > 0 (right of line) lights LEDs on the LEFT — steer
 * toward the light. Center number is |XTE| in cm, sized to read from 2 m.
 * [ledStepM]: ground meters per LED (default 0.10 m).
 */
@Composable
fun Lightbar(xteM: Double?, ledStepM: Double = 0.10, ledsPerSide: Int = 7) {
    val onTarget = xteM != null && abs(xteM) < ledStepM / 2
    Row(
        Modifier.fillMaxWidth().height(72.dp).background(Color(0xFF101010)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val lit = if (xteM == null) 0 else (abs(xteM) / ledStepM).roundToInt().coerceAtMost(ledsPerSide)
        // left side LEDs light when xte > 0 (steer left)
        for (i in ledsPerSide downTo 1) {
            Led(active = xteM != null && xteM > 0 && i <= lit, side = -1, index = i, perSide = ledsPerSide)
        }
        Box(
            Modifier.padding(horizontal = 12.dp)
                .size(width = 128.dp, height = 56.dp)
                .background(if (onTarget) Color(0xFF2E7D32) else Color(0xFF263238), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (xteM == null) "—" else "${(abs(xteM) * 100).roundToInt()}",
                color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
            )
        }
        for (i in 1..ledsPerSide) {
            Led(active = xteM != null && xteM < 0 && i <= lit, side = 1, index = i, perSide = ledsPerSide)
        }
    }
}

@Composable
private fun Led(active: Boolean, side: Int, index: Int, perSide: Int) {
    val severity = index.toFloat() / perSide
    val color = when {
        !active -> Color(0xFF2A2A2A)
        severity < 0.4f -> Color(0xFF43A047)
        severity < 0.75f -> Color(0xFFF9A825)
        else -> Color(0xFFE53935)
    }
    Box(Modifier.padding(3.dp).size(22.dp).background(color, CircleShape))
}
```
Wait — with drive-to convention, xte>0 must light LEFT LEDs; the left loop uses `xteM > 0` ✓ and right loop `xteM < 0` ✓. The number shows centimeters absolute; "cm" label omitted (number alone, larger).

- [ ] **Step 2: Build. Commit** `feat(app): drive-to lightbar with severity-colored LEDs`

---

### Task 7: Field view canvas + Guidance tab restructure

**Files:**
- Create: `app/src/main/java/com/agroguide/rtk/ui/FieldView.kt` (the Canvas)
- Create: `app/src/main/java/com/agroguide/rtk/ui/FieldScreen.kt` (lightbar + canvas + controls; replaces HomeScreen content in tab 0)
- Modify: `app/src/main/java/com/agroguide/rtk/ui/HomeScreen.kt` → repurposed as `SourceDialog` (source radio list + NTRIP switch + Iniciar/Detener), opened from FieldScreen's "Fuente" button. Keep all existing behavior.
- Modify: `MainActivity.kt` tab 0 → `FieldScreen(...)`; pass paired devices + active profile through.
- Strings: mode_free="Libre", mode_ab="AB", mode_aplus="A+", mode_curve="Curva", mark_a="Marcar A", mark_b="Marcar B", curve_start="Grabar curva", curve_stop="Fin curva", clear_line="Borrar línea", source_button="Fuente", implement_button="Apero", field_no_position="Sin posición — inicia una fuente"

**FieldView core (write exactly this transform):**

```kotlin
/** World→screen: heading-up camera anchored at 65% height. */
private fun project(
    world: EnuPoint, vehicle: VehicleState, scalePxPerM: Float,
    canvasW: Float, canvasH: Float,
): Offset {
    val dx = world.x - vehicle.x; val dy = world.y - vehicle.y
    val h = Math.toRadians(vehicle.headingDeg)
    val fwd = (sin(h) * dx + cos(h) * dy).toFloat()     // meters ahead
    val right = (cos(h) * dx - sin(h) * dy).toFloat()   // meters to the right
    return Offset(canvasW / 2 + right * scalePxPerM, canvasH * 0.65f - fwd * scalePxPerM)
}
```

Canvas draws, in order: background `#1B2E1B`; 10 m grid (world-aligned: compute visible world bounds by inverse-projecting canvas corners — or simpler: draw grid lines every 10 m in world space covering vehicle ±(canvas diagonal / scale); acceptable); trail (polyline, `#66FFFFFF`, 2dp); guidance passes for k in passIndex−5..+5 (AB/A+: infinite line rendered as segment vehicle±1000 m along direction, offset k·width·rightNormal; Curve: polyline offset per-segment-normal, only k−2..k+2): current pass `#FFEB3B` 4dp, others `#88FFFFFF` 2dp; A marker (green circle 8dp) and pending-A; implement bar: line perpendicular at work point, width·scale wide, 6dp, cyan; tractor: filled triangle 16×22dp at anchor pointing up. Pinch zoom via `detectTransformGestures` adjusting `scalePxPerM` (clamp 0.5..30, default 4); double-tap toggles north-up/heading-up (north-up: same projection with h=0).

**FieldScreen layout (landscape):** Column: `Lightbar(result?.xteM)` top; Row(weight 1): FieldView(weight 1) + right column 260dp: compact quality chip (reuse qualityColor/Label, 40dp), speed + pass number (`Pasada ${result?.passIndex ?? "—"}`), mode segmented buttons (4× SegmentedButton or Row of FilterChips), contextual action buttons (AB mode: Marcar A / Marcar B enabled by pendingA; A+: Marcar A+; Curva: Grabar/Fin toggle), Borrar línea, Apero (opens ImplementDialog), Fuente (opens SourceDialog). All buttons ≥56dp height.

FieldScreen collects `PositionRepository.vehicle` and on each emission calls `GuidanceRepository.update(state)` via `LaunchedEffect` — single collector driving guidance.

- [ ] **Step 1: Implement FieldView.kt.**
- [ ] **Step 2: Implement FieldScreen.kt + SourceDialog conversion + MainActivity wiring + strings.**
- [ ] **Step 3: `.\gradlew.bat test assembleDebug` → green. Commit** `feat(app): canvas field view with heading-up camera, guidance passes and controls`

---

### Task 8: Phase-3 verification & checkpoint

- [ ] **Step 1:** `clean` (separate) then `test assembleDebug` → all green.
- [ ] **Step 2:** Manual smoke with demo source: start demo → field view shows moving grid; Marcar A, drive, Marcar B → passes appear, lightbar centers; demo heading changes → XTE responds; curve record → curved passes.
- [ ] **Step 3:** Checkpoint report (Spanish) + limitations (curve offset approximation, no headland turns assist, line not persisted yet, lightbar step fixed 10 cm).
- [ ] **Step 4:** `git tag phase3-checkpoint`
