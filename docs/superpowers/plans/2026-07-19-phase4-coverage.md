# AgroGuide RTK — Phase 4 Implementation Plan (coverage, overlap, sections, counters)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real-time painted swath coverage with overlap in a distinct color, manual per-section control, and live counters (ha, overlap ha/%, distance) — incremental so a 4 h job does not degrade render or CPU.

**Architecture:** New pure-JVM module `:core:coverage` (JTS 1.20). Swaths are quads between consecutive work poses, one per ACTIVE section, **clipped to a 50 m tile grid** so per-tile geometries are disjoint across tiles → per-tile cached areas sum exactly (no double counting). Per tile: `covered = covered ∪ quad`, `overlap = overlap ∪ (covered_prev ∩ quad)`; only touched tiles recompute. `:service` gains `CoverageRepository` (single-threaded mutation dispatcher, tile-version StateFlow for the UI) and **the vehicle collector moves from FieldScreen into GuidanceService** so guidance+coverage keep running with the screen off. `:app` renders tiles as cached world-space Compose Paths under one canvas transform, plus a bottom section bar and a counters strip.

**Key invariants:**
- Tile key = `(floor(x/50), floor(y/50))`; every quad is intersected with each overlapping tile's square before insertion.
- Invalid quads (bowtie on sharp turns) are normalized with `buffer(0)`; segments < 0.05 m or > 30 m are skipped; painting only while any section is active and speed > 0.2 m/s.
- Counters are exact: totals adjusted by per-tile area deltas after each insertion.
- Section i of N spans laterally `[-W/2 + i·W/N, -W/2 + (i+1)·W/N]` relative to the work point (0 = leftmost).

**Environment:** unchanged.

---

### Task 1: `:core:coverage` module + swath/section geometry (TDD)

**Files:**
- Modify: `gradle/libs.versions.toml` → `[versions] jts = "1.20.0"`; `[libraries] jts-core = { module = "org.locationtech.jts:jts-core", version.ref = "jts" }`
- Modify: `settings.gradle.kts` → add `":core:coverage"`
- Create: `core/coverage/build.gradle.kts` (kotlin-jvm module like `core/geo`, plus `api(project(":core:geo"))` and `api(libs.jts.core)`)
- Modify: `service/build.gradle.kts` → `api(project(":core:coverage"))`
- Create: `core/coverage/src/main/kotlin/com/agroguide/rtk/coverage/Swath.kt`
- Test: `core/coverage/src/test/kotlin/com/agroguide/rtk/coverage/SwathTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.agroguide.rtk.coverage

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwathTest {
    @Test fun `section extents split the width evenly`() {
        val e = Swath.sectionExtent(widthM = 6.0, sections = 3, index = 0)
        assertEquals(-3.0, e.first, 1e-9); assertEquals(-1.0, e.second, 1e-9)
        val last = Swath.sectionExtent(6.0, 3, 2)
        assertEquals(1.0, last.first, 1e-9); assertEquals(3.0, last.second, 1e-9)
    }

    @Test fun `straight segment quad area equals width times length`() {
        val q = Swath.quad(
            WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(0.0, 10.0), 0.0),
            fromM = -3.0, toM = 3.0,
        )!!
        assertEquals(60.0, q.area, 1e-6)
    }

    @Test fun `quad follows per-pose headings on a turn and stays valid`() {
        val q = Swath.quad(
            WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(2.0, 2.0), 90.0),
            fromM = -3.0, toM = 3.0,
        )!!
        assertTrue(q.isValid)
        assertTrue(q.area > 0)
    }

    @Test fun `degenerate segments are rejected`() {
        assertNull(Swath.quad(WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(0.0, 0.01), 0.0), -3.0, 3.0))
        assertNull(Swath.quad(WorkPose(EnuPoint(0.0, 0.0), 0.0), WorkPose(EnuPoint(0.0, 50.0), 0.0), -3.0, 3.0))
    }
}
```

- [ ] **Step 2: Implement `Swath.kt`**

```kotlin
package com.agroguide.rtk.coverage

import com.agroguide.rtk.geo.EnuPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One work pose: implement work point + vehicle heading. */
data class WorkPose(val p: EnuPoint, val headingDeg: Double)

object Swath {
    val factory = GeometryFactory()
    const val MIN_SEG_M = 0.05
    const val MAX_SEG_M = 30.0

    /** Lateral [from, to] of section [index] out of [sections], 0 = leftmost. */
    fun sectionExtent(widthM: Double, sections: Int, index: Int): Pair<Double, Double> {
        val step = widthM / sections
        val from = -widthM / 2 + index * step
        return from to from + step
    }

    /**
     * Quad covered by the lateral band [fromM, toM] while moving pose1→pose2.
     * Edges use each pose's own right vector so turns paint correctly.
     * Bowties (sharp turns) are healed with buffer(0). Null when degenerate.
     */
    fun quad(a: WorkPose, b: WorkPose, fromM: Double, toM: Double): Polygon? {
        val d = hypot(b.p.x - a.p.x, b.p.y - a.p.y)
        if (d < MIN_SEG_M || d > MAX_SEG_M) return null
        val (arx, ary) = rightVec(a.headingDeg)
        val (brx, bry) = rightVec(b.headingDeg)
        val ring = arrayOf(
            Coordinate(a.p.x + arx * fromM, a.p.y + ary * fromM),
            Coordinate(b.p.x + brx * fromM, b.p.y + bry * fromM),
            Coordinate(b.p.x + brx * toM, b.p.y + bry * toM),
            Coordinate(a.p.x + arx * toM, a.p.y + ary * toM),
        )
        val poly = factory.createPolygon(ring + ring[0])
        return when {
            poly.isValid -> poly
            else -> poly.buffer(0.0) as? Polygon
        }
    }

    private fun rightVec(headingDeg: Double): Pair<Double, Double> {
        val h = headingDeg * PI / 180
        return cos(h) to -sin(h)
    }
}
```

- [ ] **Step 3: Run `:core:coverage:test` → green. Commit** `feat(coverage): JTS module with section extents and swath quads`

---

### Task 2: tile-indexed CoverageEngine with exact incremental counters (TDD)

**Files:**
- Create: `core/coverage/src/main/kotlin/com/agroguide/rtk/coverage/CoverageEngine.kt`
- Test: `core/coverage/src/test/kotlin/com/agroguide/rtk/coverage/CoverageEngineTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
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
        drive(e, 0.0, -100.0, 100.0, booleanArrayOf(true))   // crosses y=0 and y=±50 tiles
        assertEquals(1200.0, e.stats.treatedM2, 2.0)
        assertTrue(e.tiles().size >= 4)                       // several tiles touched
    }

    @Test fun `reset clears everything`() {
        val e = CoverageEngine(6.0, 1)
        drive(e, 0.0, 0.0, 10.0, booleanArrayOf(true))
        e.reset()
        assertEquals(0.0, e.stats.treatedM2, 1e-9)
        assertEquals(0, e.tiles().size)
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
```

- [ ] **Step 2: Implement `CoverageEngine.kt`**

```kotlin
package com.agroguide.rtk.coverage

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Polygon
import kotlin.math.floor
import kotlin.math.hypot

data class TileKey(val ix: Int, val iy: Int)

/** Immutable per-tile snapshot handed to the renderer. */
class TileSnapshot(
    val covered: Geometry,
    val overlap: Geometry,
    val version: Int,
)

data class CoverageStats(
    val treatedM2: Double = 0.0,
    val overlapM2: Double = 0.0,
    val distanceM: Double = 0.0,
) {
    val overlapPercent: Double get() = if (treatedM2 > 0) overlapM2 / treatedM2 * 100 else 0.0
}

/**
 * Incremental tile-indexed coverage. NOT thread-safe: the owner must call all
 * mutators from one thread (CoverageRepository uses a single-thread dispatcher).
 * Quads are clipped to tile squares so tile geometries are disjoint across
 * tiles and per-tile cached areas sum exactly.
 */
class CoverageEngine(
    @Volatile var widthM: Double,
    @Volatile var sections: Int,
    private val tileSizeM: Double = 50.0,
) {
    private class Tile(
        var covered: Geometry,
        var overlap: Geometry,
        var coveredArea: Double = 0.0,
        var overlapArea: Double = 0.0,
        var version: Int = 0,
    )

    private val tilesMap = HashMap<TileKey, Tile>()
    private var prevPose: WorkPose? = null

    var stats = CoverageStats(); private set
    /** Bumped on every geometry change; cheap dirty check for the renderer. */
    var globalVersion = 0; private set

    fun tiles(): Map<TileKey, TileSnapshot> =
        tilesMap.mapValues { (_, t) -> TileSnapshot(t.covered, t.overlap, t.version) }

    fun reset() {
        tilesMap.clear(); prevPose = null; stats = CoverageStats(); globalVersion++
    }

    /** Call when the implement lifts / a new pass starts: next update won't paint a segment. */
    fun breakSegment() { prevPose = null }

    fun update(pose: WorkPose, activeSections: BooleanArray) {
        val prev = prevPose
        prevPose = pose
        if (prev == null) return
        val d = hypot(pose.p.x - prev.p.x, pose.p.y - prev.p.y)
        if (d < Swath.MIN_SEG_M) { prevPose = prev; return }   // idle jitter: keep anchor
        if (d > Swath.MAX_SEG_M) return                        // jump: drop segment
        stats = stats.copy(distanceM = stats.distanceM + d)
        if (activeSections.none { it }) return
        for (s in 0 until minOf(sections, activeSections.size)) {
            if (!activeSections[s]) continue
            val (from, to) = Swath.sectionExtent(widthM, sections, s)
            val quad = Swath.quad(prev, pose, from, to) ?: continue
            insert(quad)
        }
    }

    private fun insert(quad: Polygon) {
        val env = quad.envelopeInternal
        val ix0 = floor(env.minX / tileSizeM).toInt(); val ix1 = floor(env.maxX / tileSizeM).toInt()
        val iy0 = floor(env.minY / tileSizeM).toInt(); val iy1 = floor(env.maxY / tileSizeM).toInt()
        var treated = stats.treatedM2; var overlapTotal = stats.overlapM2
        for (ix in ix0..ix1) for (iy in iy0..iy1) {
            val key = TileKey(ix, iy)
            val square = Swath.factory.toGeometry(
                org.locationtech.jts.geom.Envelope(
                    ix * tileSizeM, (ix + 1) * tileSizeM, iy * tileSizeM, (iy + 1) * tileSizeM,
                )
            )
            val part = quad.intersection(square)
            if (part.isEmpty || part.area < 1e-9) continue
            val tile = tilesMap.getOrPut(key) {
                Tile(Swath.factory.createPolygon(), Swath.factory.createPolygon())
            }
            val inter = tile.covered.intersection(part)
            if (!inter.isEmpty && inter.area > 1e-9) tile.overlap = tile.overlap.union(inter)
            tile.covered = tile.covered.union(part)
            val newCovered = tile.covered.area
            val newOverlap = tile.overlap.area
            treated += newCovered - tile.coveredArea
            overlapTotal += newOverlap - tile.overlapArea
            tile.coveredArea = newCovered
            tile.overlapArea = newOverlap
            tile.version++
        }
        stats = stats.copy(treatedM2 = treated, overlapM2 = overlapTotal)
        globalVersion++
    }
}
```
Note the idle-jitter branch restores `prevPose = prev` so slow creeping still accumulates into a paintable segment.

- [ ] **Step 3: Run → green (perf test included). Commit** `feat(coverage): tile-indexed incremental coverage engine with exact counters`

---

### Task 3: `:service` — CoverageRepository + move the vehicle collector into the service

**Files:**
- Create: `service/src/main/java/com/agroguide/rtk/service/CoverageRepository.kt`
- Modify: `service/src/main/java/com/agroguide/rtk/service/GuidanceService.kt`
- Modify: `app/src/main/java/com/agroguide/rtk/ui/FieldScreen.kt` (remove the LaunchedEffect collector)

- [ ] **Step 1: `CoverageRepository.kt`**

```kotlin
package com.agroguide.rtk.service

import com.agroguide.rtk.coverage.CoverageEngine
import com.agroguide.rtk.coverage.CoverageStats
import com.agroguide.rtk.coverage.TileKey
import com.agroguide.rtk.coverage.TileSnapshot
import com.agroguide.rtk.coverage.WorkPose
import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.VehicleState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the CoverageEngine on a single-threaded dispatcher. UI reads immutable
 * snapshots (stats + per-tile geometry). Sections are manual toggles; painting
 * happens only while moving (> 0.2 m/s) with some section active.
 */
object CoverageRepository {
    @kotlin.concurrent.Volatile
    private var engine = CoverageEngine(widthM = 6.0, sections = 1)

    private val mutationDispatcher: CoroutineDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "coverage").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + mutationDispatcher)

    private val _stats = MutableStateFlow(CoverageStats())
    val stats: StateFlow<CoverageStats> = _stats.asStateFlow()

    private val _sections = MutableStateFlow(listOf(true))
    val sections: StateFlow<List<Boolean>> = _sections.asStateFlow()

    private val _tiles = MutableStateFlow<Map<TileKey, TileSnapshot>>(emptyMap())
    val tiles: StateFlow<Map<TileKey, TileSnapshot>> = _tiles.asStateFlow()

    fun configure(widthM: Double, sections: Int) {
        scope.launch {
            engine.widthM = widthM
            engine.sections = sections
            val current = _sections.value
            _sections.value = List(sections) { current.getOrElse(it) { true } }
        }
    }

    fun toggleSection(i: Int) {
        _sections.value = _sections.value.mapIndexed { idx, v -> if (idx == i) !v else v }
    }

    fun setAllSections(on: Boolean) { _sections.value = _sections.value.map { on } }

    fun reset() {
        scope.launch { engine.reset(); publish() }
    }

    /** Called by the service's vehicle collector for every state. */
    fun update(state: VehicleState, workPoint: EnuPoint) {
        val active = _sections.value.toBooleanArray()
        scope.launch {
            if (state.speedMps > 0.2) {
                engine.update(WorkPose(workPoint, state.headingDeg), active)
            } else {
                engine.breakSegment()
            }
            publish()
        }
    }

    private fun publish() {
        _stats.value = engine.stats
        _tiles.value = engine.tiles()
    }
}
```
Add import `kotlinx.coroutines.asCoroutineDispatcher` (from `kotlinx.coroutines` for ExecutorService). Note `engine.tiles()` allocates a small map per update — at 5 Hz with tens of tiles this is negligible; the heavy geometries are shared references, not copies.

- [ ] **Step 2: Service collector.** In `GuidanceService.onStartCommand`, after `PositionRepository.attach(...)`:

```kotlin
        collectorJob?.cancel()
        collectorJob = lifecycleScope.launch {
            PositionRepository.vehicle.collect { state ->
                if (state != null) {
                    GuidanceRepository.update(state)
                    val work = GuidanceRepository.implement.value.workPoint(
                        com.agroguide.rtk.geo.EnuPoint(state.x, state.y), state.headingDeg,
                    )
                    CoverageRepository.update(state, work)
                }
            }
        }
```
with `private var collectorJob: kotlinx.coroutines.Job? = null` cancelled in `onDestroy`. Remove the `LaunchedEffect` collector from FieldScreen. Keep `CoverageRepository.configure(width, sections)` called from MainActivity's implement-settings collector (Task 5).

- [ ] **Step 3: Build all. Commit** `feat(service): coverage repository on single-thread dispatcher; guidance+coverage driven from service`

---

### Task 4: FieldView coverage rendering (cached world paths under one transform)

**Files:**
- Modify: `app/src/main/java/com/agroguide/rtk/ui/FieldView.kt`

Add parameters `tiles: Map<TileKey, TileSnapshot>` before `modifier`. Render coverage right after the grid and before the trail:

```kotlin
// JTS → world-space Path (y negated so north is up pre-transform); cached per tile+version
class TilePaths(val version: Int, val covered: Path, val overlap: Path)

fun geometryToPath(g: org.locationtech.jts.geom.Geometry): Path {
    val path = Path().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd }
    for (i in 0 until g.numGeometries) {
        val poly = g.getGeometryN(i) as? org.locationtech.jts.geom.Polygon ?: continue
        appendRing(path, poly.exteriorRing)
        for (r in 0 until poly.numInteriorRing) appendRing(path, poly.getInteriorRingN(r))
    }
    return path
}
private fun appendRing(path: Path, ring: org.locationtech.jts.geom.LineString) {
    val n = ring.numPoints
    if (n < 3) return
    val c0 = ring.getCoordinateN(0)
    path.moveTo(c0.x.toFloat(), -c0.y.toFloat())
    for (i in 1 until n) {
        val c = ring.getCoordinateN(i)
        path.lineTo(c.x.toFloat(), -c.y.toFloat())
    }
    path.close()
}
```
Cache: `val tilePathCache = remember { mutableMapOf<TileKey, TilePaths>() }`; on composition, for each entry in `tiles` rebuild when `cache[key]?.version != snapshot.version`; drop cache keys not in `tiles`.

Draw inside the Canvas (only tiles whose 50 m square intersects vehicle ± extent):

```kotlin
withTransform({
    translate(cx, cy)
    rotate(degrees = -camHeading.toFloat(), pivot = Offset.Zero)
    scale(scale, scale, pivot = Offset.Zero)
    translate(-v.x.toFloat(), v.y.toFloat())
}) {
    for ((key, paths) in visibleTilePaths) {
        drawPath(paths.covered, Color(0x5532CD32))
        drawPath(paths.overlap, Color(0x88FF6D00))
    }
}
```
(The path stores (x, −y) so combined with this transform it reproduces exactly the same mapping as `project()` — verified algebraically: translate(−vx, +vy) turns world (x,−y) into (dx, −dy); scale by s; rotate −camHeading in y-down screen convention gives (s·(cos·dx − sin·(−(−dy)))…) → matches `cx + right·scale, cy − fwd·scale`.)

- [ ] **Step 1: Implement. Manual check with demo mode: green trail of paint behind the tractor; retrace → orange.**
- [ ] **Step 2: Full build + tests. Commit** `feat(app): coverage and overlap rendering with per-tile cached paths`

---

### Task 5: sections bar, counters strip, implement sections config

**Files:**
- Modify: `app/src/main/java/com/agroguide/rtk/ui/FieldScreen.kt` — collect `CoverageRepository.stats/sections/tiles`; pass `tiles` to FieldView; add `SectionBar` overlaid at the canvas bottom; add `CountersStrip` in the right panel; add a "Reiniciar cobertura" outlined button with confirm dialog (calls `CoverageRepository.reset()` + `GuidanceRepository.clearTrail()`).
- Modify: `app/src/main/java/com/agroguide/rtk/ui/ImplementDialog.kt` — add "Secciones" numeric field (1..16, integer).
- Modify: `app/src/main/java/com/agroguide/rtk/data/ImplementSettings.kt` — add `sections: Int = 1`, map into `ImplementConfig(sections = sections)`.
- Modify: `app/src/main/java/com/agroguide/rtk/MainActivity.kt` — implement-settings collector also calls `CoverageRepository.configure(it.widthM, it.sections)`.
- Strings: sections_label="Secciones", counters_treated="Tratado", counters_overlap="Solape", counters_distance="Distancia", coverage_reset="Reiniciar cobertura", coverage_reset_confirm="¿Borrar la cobertura pintada y los contadores?", unit_ha="ha", unit_km="km".

`SectionBar` (bottom overlay, height 56dp): Row with one weighted button per section, green (`#2E7D32`) when on, dark gray when off, white section number, tap → `CoverageRepository.toggleSection(i)`; long-press master toggle omitted (YAGNI) but add a small "todas" text button at the left that calls `setAllSections(!allOn)`.

`CountersStrip`: three big rows in the right panel — `Tratado 1,23 ha`, `Solape 0,05 ha (4,1 %)`, `Distancia 2,4 km` — device-locale formatting via `"%.2f".format(...)`, 18–20 sp semibold.

- [ ] **Step 1: Implement all. Build + tests. Commit** `feat(app): section control bar, live counters, sections in implement config`

---

### Task 6: Phase-4 verification & checkpoint

- [ ] **Step 1:** `clean` (separate) then `test assembleDebug` → all green.
- [ ] **Step 2:** Demo-mode manual script: paint, retrace (lateral offset trick) → orange overlap + % grows; toggle sections while driving → stripes; reset works; screen-off 2 min → coverage continued (service collector).
- [ ] **Step 3:** Checkpoint report (Spanish) with limitations (overlap counts pass-vs-pass only after segment break; per-quad self-overlap in tight turns partially healed by buffer(0); counters exact vs render simplificado no aplica — render exacto; no persistence yet — phase 5).
- [ ] **Step 4:** `git tag phase4-checkpoint`
