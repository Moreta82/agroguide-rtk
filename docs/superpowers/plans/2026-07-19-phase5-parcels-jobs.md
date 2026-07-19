# AgroGuide RTK — Phase 5 Implementation Plan (parcels, jobs, reports, import/export)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parcels (drive or tap the perimeter, area/perimeter, GeoJSON/KML import/export), jobs with a crash-safe append-only journal (pause/resume/finish, resume after app restart with coverage rebuilt), and per-job HTML+PDF reports with a track thumbnail.

**Architecture:** New Android-library module `:data` (Room 2.7 + KSP for entities/DAOs; JVM-unit-testable pure logic for the journal binary format, parcel geometry, GeoJSON/KML codecs and the HTML report builder). `:service` gains `JobManager` (single job active; journal writer fed from the existing service vehicle collector; resume-on-service-start replays the journal into CoverageEngine via a new `CoverageRepository.replay`). Tangent-plane pinning: the journal header stores the ENU origin; on resume `PositionRepository` pins the pipeline plane to it so old and new coverage align. UI: two new tabs (Parcelas, Trabajos) and a small active-job strip on FieldScreen; parcel outline drawn on the canvas; tap-mode for parcel points via inverse projection.

**Journal binary format v1** (big-endian, `DataOutputStream`):
```
Header: magic "AGJ1", version u8=1, originLat f64, originLon f64, widthM f64, sections u8
Records (type u8 first):
  0x01 FIX:    tMs u32 (relative), x f32, y f32 (WORK POINT, ENU), heading f32,
               speedMps f32, quality u8 (GnssQuality ordinal), sectionsMask u16
  0x02 PAUSE:  tMs u32
  0x03 RESUME: tMs u32
  0x04 BREAK:  tMs u32
```
Reader tolerates a truncated tail (crash mid-record → stop cleanly). FIX stores the work point so replay is independent of later implement changes.

**Environment:** unchanged. KSP note: use `ksp = "2.1.20-1.0.32"`; if Gradle cannot resolve it, list available versions with a quick fetch of `https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml` (or Google Maven) and pick the newest `2.1.20-*`.

---

### Task 1: `:data` module + Room schema

**Files:**
- `gradle/libs.versions.toml`: `room = "2.7.1"`, `ksp = "2.1.20-1.0.32"`; libraries `androidx-room-runtime`, `androidx-room-ktx` (both `androidx.room:room-*`, version.ref room), `androidx-room-compiler`; plugin `ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }`.
- Root `build.gradle.kts`: `alias(libs.plugins.ksp) apply false`.
- `settings.gradle.kts`: add `":data"`.
- Create `data/build.gradle.kts`: android-library (namespace `com.agroguide.rtk.data`, same SDK config as `:device`) + `alias(libs.plugins.ksp)` + `alias(libs.plugins.kotlin.serialization)`; deps: `api(project(":core:coverage"))`, room-runtime/ktx api, `ksp(libs.androidx.room.compiler)`, kotlinx-serialization-json, coroutines; test deps junit/kotlin-test.
- `service/build.gradle.kts`: `api(project(":data"))`.
- Create `data/src/main/AndroidManifest.xml` (empty manifest tag).
- Create `data/src/main/java/com/agroguide/rtk/data/db/Entities.kt`:

```kotlin
package com.agroguide.rtk.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "parcels")
data class ParcelEntity(
    @PrimaryKey val id: String,              // UUID
    val name: String,
    val createdAtMs: Long,
    /** Closed ring as GeoJSON-style "lon,lat lon,lat ..." pairs, WGS84. */
    val ringLonLat: String,
    val areaM2: Double,
    val perimeterM: Double,
)

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,              // UUID
    val name: String,
    val parcelId: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val state: String,                       // ACTIVE | PAUSED | FINISHED
    val implementWidthM: Double,
    val sections: Int,
    val originLat: Double,
    val originLon: Double,
    val treatedM2: Double = 0.0,
    val overlapM2: Double = 0.0,
    val distanceM: Double = 0.0,
    val activeSeconds: Long = 0,
)
```
- Create `data/src/main/java/com/agroguide/rtk/data/db/Daos.kt` (suspend CRUD: parcels list Flow, insert, rename, delete, getById; jobs: list Flow ordered by startedAt desc, insert, update, getById, `findUnfinished(): JobEntity?` where state != 'FINISHED' limit 1).
- Create `data/src/main/java/com/agroguide/rtk/data/db/AgroDb.kt` (RoomDatabase v1, singleton `AgroDb.get(context)` with `Room.databaseBuilder(..., "agroguide.db")`).

- [ ] **Step 1: Implement all; `.\gradlew.bat :data:assembleDebug` → green. Commit** `feat(data): Room module with parcel and job entities`

---

### Task 2: job journal — writer/reader/replay (TDD)

**Files:**
- Create: `data/src/main/java/com/agroguide/rtk/data/journal/JobJournal.kt`
- Test: `data/src/test/java/com/agroguide/rtk/data/journal/JobJournalTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
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
```

- [ ] **Step 2: Implement `JobJournal.kt`**

```kotlin
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
private const val T_FIX = 0x01; private const val T_PAUSE = 0x02
private const val T_RESUME = 0x03; private const val T_BREAK = 0x04

/** Append-only crash-safe writer: every record is flushed to the OS. */
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
                    if (spanStart < 0) spanStart = rec.tMs
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
```

- [ ] **Step 3: `:data:testDebugUnitTest` → green. Commit** `feat(data): crash-safe append-only job journal with replay`

---

### Task 3: parcel geometry + GeoJSON/KML codecs (TDD)

**Files:**
- Create: `data/src/main/java/com/agroguide/rtk/data/parcel/ParcelGeometry.kt`
- Create: `data/src/main/java/com/agroguide/rtk/data/parcel/ParcelCodec.kt`
- Test: `data/src/test/java/com/agroguide/rtk/data/parcel/ParcelTest.kt`

- [ ] **Step 1: Failing tests**

```kotlin
package com.agroguide.rtk.data.parcel

import com.agroguide.rtk.geo.EnuPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParcelTest {
    // 100 x 50 m rectangle in ENU
    private val ring = listOf(
        EnuPoint(0.0, 0.0), EnuPoint(100.0, 0.0), EnuPoint(100.0, 50.0), EnuPoint(0.0, 50.0),
    )

    @Test fun `area and perimeter of a rectangle`() {
        val g = ParcelGeometry.measure(ring)!!
        assertEquals(5000.0, g.areaM2, 1e-6)
        assertEquals(300.0, g.perimeterM, 1e-6)
    }

    @Test fun `fewer than 3 points measures null`() {
        assertNull(ParcelGeometry.measure(ring.take(2)))
    }

    @Test fun `geojson roundtrip preserves ring`() {
        val lonLat = listOf(-5.0 to 42.0, -4.999 to 42.0, -4.999 to 42.001, -5.0 to 42.001)
        val json = ParcelCodec.toGeoJson("Mi parcela", lonLat)
        assertTrue(json.contains("\"FeatureCollection\""))
        val parsed = ParcelCodec.fromGeoJson(json)
        assertEquals(1, parsed.size)
        assertEquals("Mi parcela", parsed[0].name)
        assertEquals(lonLat.size, parsed[0].ringLonLat.size)
        assertEquals(-4.999, parsed[0].ringLonLat[1].first, 1e-9)
        assertEquals(42.0, parsed[0].ringLonLat[1].second, 1e-9)
    }

    @Test fun `geojson import accepts closed rings and strips the duplicate`() {
        val json = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"P"},
            "geometry":{"type":"Polygon","coordinates":[[[-5,42],[-4.9,42],[-4.9,42.1],[-5,42]]]}}]}"""
        val parsed = ParcelCodec.fromGeoJson(json)
        assertEquals(3, parsed[0].ringLonLat.size)   // closing vertex removed
    }

    @Test fun `kml roundtrip`() {
        val lonLat = listOf(-5.0 to 42.0, -4.999 to 42.0, -4.999 to 42.001)
        val kml = ParcelCodec.toKml("Finca", lonLat)
        assertTrue(kml.contains("<coordinates>"))
        val parsed = ParcelCodec.fromKml(kml)
        assertEquals(1, parsed.size)
        assertEquals("Finca", parsed[0].name)
        assertEquals(3, parsed[0].ringLonLat.size)
    }

    @Test fun `malformed inputs return empty`() {
        assertTrue(ParcelCodec.fromGeoJson("not json").isEmpty())
        assertTrue(ParcelCodec.fromKml("<kml></kml>").isEmpty())
    }
}
```

- [ ] **Step 2: Implement.**

`ParcelGeometry.kt`: build a JTS polygon from the ENU ring (auto-close), return `Measured(areaM2, perimeterM)` via `poly.area` / `poly.exteriorRing.length`; null for <3 points or invalid after `buffer(0)` normalization.

`ParcelCodec.kt` (kotlinx-serialization `JsonElement` DOM building/parsing; KML via string template and a `<coordinates>...</coordinates>` + `<name>` regex extractor):
- `toGeoJson(name, ring: List<Pair<Double,Double>>): String` — closes the ring in output (GeoJSON requires first==last).
- `fromGeoJson(text): List<ImportedParcel>` — accepts FeatureCollection/Feature/Polygon/MultiPolygon (first ring), name from `properties.name` else "Parcela"; strips duplicate closing vertex; `runCatching` → empty on garbage.
- `toKml(name, ring)`: minimal Document/Placemark/Polygon/outerBoundaryIs/LinearRing with `lon,lat,0` triplets, ring closed.
- `fromKml(text)`: for each `<Placemark>` block, extract optional `<name>` and first `<coordinates>`; parse triplets; strip closing vertex; empty on none.
- `data class ImportedParcel(val name: String, val ringLonLat: List<Pair<Double, Double>>)`.

- [ ] **Step 3: Green. Commit** `feat(data): parcel geometry and GeoJSON/KML codecs`

---

### Task 4: report builder — HTML (TDD) + PDF + SVG thumbnail

**Files:**
- Create: `data/src/main/java/com/agroguide/rtk/data/report/JobReport.kt` (pure: data + HTML + SVG)
- Create: `data/src/main/java/com/agroguide/rtk/data/report/PdfWriter.kt` (android.graphics.pdf; manual test only)
- Test: `data/src/test/java/com/agroguide/rtk/data/report/JobReportTest.kt`

`JobReportData`: jobName, parcelName?, dateStr, activeSeconds, treatedM2, overlapM2, parcelAreaM2?, widthM, avgSpeedMps, distanceM, trail (List<EnuPoint>).

`JobReport.html(data)`: self-contained Spanish HTML — title AgroGuide RTK, table with Parcela/Fecha/Duración (h:mm)/Área tratada (ha)/Solape (ha, %)/Área parcela (ha, "—" if null)/Ancho apero/Velocidad media (km/h)/Distancia (km), and `JobReport.trackSvg(trail, 480, 360)` inline: polyline scaled+centered into the viewBox with 20 px margin (empty trail → "Sin track"). Tests assert: contains job name, formatted duration "1:05", "12,00 ha"-style values are NOT asserted numerically locale-dependent — use `Locale.US` formatting inside html() and assert "12.00".

`PdfWriter.write(context-free: (data, outFile))`: PdfDocument A4 page (595x842), draw title + the same table lines with `android.graphics.Paint`, then the trail polyline scaled into a 400x300 box. No test (Android graphics), exercised manually.

- [ ] **Steps: tests (name/duration/area/SVG polyline presence + empty-trail fallback) → implement → green. Commit** `feat(data): job report HTML with SVG track thumbnail, PDF writer`

---

### Task 5: `:service` — JobManager + resume + plane pinning + CoverageRepository.replay

**Files:**
- Create: `service/src/main/java/com/agroguide/rtk/service/JobManager.kt`
- Modify: `service/src/main/java/com/agroguide/rtk/service/CoverageRepository.kt` (add `replay`)
- Modify: `service/src/main/java/com/agroguide/rtk/service/PositionRepository.kt` (plane pinning)
- Modify: `service/src/main/java/com/agroguide/rtk/service/GuidanceService.kt` (feed JobManager from the collector; attempt resume on start)

Key points (implement exactly):
1. `PositionRepository`: add `@Volatile var pinnedPlane: TangentPlane?`; in `attach`, construct `PositionPipeline(sharedSource, pinnedPlane)`; add `fun pinPlane(p: TangentPlane?)` that stores it AND sets it on the live pipeline if attached (keep a reference to the current pipeline).
2. `CoverageRepository.replay(data: JournalData, onDone: (ReplaySummary) -> Unit)`: on the mutation dispatcher: `engine.reset()`, summary = `JournalReplay.into(data, engine)`, publish(), invoke onDone. Also add `fun breakSegmentNow()` no-op wrapper if needed by JobManager pause.
3. `JobManager` (object): states via `StateFlow<JobEntity?>` (`activeJob`); functions `start(context, name, parcelId?)`, `pause()`, `resume()`, `finish(context)`, `tryResumeOnStart(context)`; journal files at `File(context.filesDir, "journals/<id>.journal")`.
   - start: db insert (state ACTIVE, origin from `PositionRepository` current plane origin — if none yet, refuse (UI disables button until a fix exists)); create writer; `CoverageRepository.reset()`; `GuidanceRepository.clearTrail()`; pin plane to that origin.
   - onState(state, sectionsMask, working: Boolean): called from GuidanceService collector for every state when a job is ACTIVE (not paused): write `fix(tMs = now-start, workPoint...)`; when speed <= 0.2 write `breakSegment` transitions only on edge (moving→stopped).
   - pause: write pause record, set state PAUSED in db + flow; **the service collector stops feeding coverage while paused** (gate: `JobManager.isPaused`).
   - resume: record + ACTIVE.
   - finish: final stats from `CoverageRepository.stats` + activeSeconds from journal replay summary… simpler: track activeMs incrementally in JobManager (span accounting mirroring JournalReplay); write db row FINISHED; close writer; unpin plane; keep coverage on screen.
   - tryResumeOnStart: `findUnfinished()` → read+replay journal (CoverageRepository.replay), restore trail into GuidanceRepository (add `fun restoreTrail(points)`), pin plane from header, reopen writer in append mode, set flow (state as stored; if PAUSED stay paused).
4. `GuidanceService.startVehicleCollector`: after CoverageRepository.update also `JobManager.onState(...)` **and** gate `CoverageRepository.update` behind `!JobManager.isPaused` (free driving without a job still paints — decision: coverage paints whenever sections are on, job or not; the journal only records during a job. Paused job = no painting and no recording).
5. `GuidanceService.onStartCommand`: `lifecycleScope.launch { JobManager.tryResumeOnStart(applicationContext) }` once (guard with a flag).

- [ ] **Steps: implement, build all, commit** `feat(service): job lifecycle with journal recording, resume-on-start, plane pinning`

---

### Task 6: UI — Parcelas + Trabajos tabs, field-view integration

**Files:**
- Create: `app/src/main/java/com/agroguide/rtk/ui/ParcelsScreen.kt`
- Create: `app/src/main/java/com/agroguide/rtk/ui/JobsScreen.kt`
- Modify: `MainActivity.kt` (5 tabs: Guiado | Parcelas | Trabajos | NTRIP | Receptor; SAF import launcher `ActivityResultContracts.OpenDocument` for GeoJSON/KML)
- Modify: `FieldScreen.kt` / `FieldView.kt` (active-job strip; parcel outline; tap-to-add-point mode)
- Strings (Spanish) for everything below.

Content requirements:
1. **ParcelsScreen**: list from Room (name, area ha, perimeter m) with Renombrar (dialog) / Eliminar (confirm) / Exportar GeoJSON / Exportar KML per parcel (writes to `getExternalFilesDir("export")`, then fires `ACTION_SEND` share intent with `FileProvider`); "Importar" button → SAF picker (mime `*/*`), parse both codecs (try GeoJSON then KML), measure via ENU plane anchored at ring centroid, insert; "Nueva parcela" → two options dialog: **Conducir perímetro** (starts `ParcelRecorderState` — reuses CurveRecorder against work points, min spacing 1 m, banner on FieldScreen with "Cerrar parcela"/"Cancelar") and **Marcar puntos** (switches FieldScreen into tap mode). Both finish with a name dialog → measure (JTS) → insert. Add `FileProvider` to app manifest with `${applicationId}.files` authority and a `file_paths` XML for external-files-path.
2. **JobsScreen**: active-job card (name, state chip, elapsed, treated ha live from CoverageRepository) with Pausar/Reanudar/Finalizar (confirm); "Nuevo trabajo" dialog (name prefilled "Trabajo <fecha>", optional parcel dropdown) disabled without position fix; history list (finished jobs: name, date, ha, duration) each with "Informe HTML" and "Informe PDF" buttons → generate via JobReport/PdfWriter from journal replay (background coroutine), save to export dir, `ACTION_VIEW`/share via FileProvider.
3. **FieldScreen**: top-left overlay chip on the canvas with active job name + state (green ACTIVE, amber PAUSED); parcel outline of the job's parcel drawn in FieldView (`parcelRing: List<EnuPoint>?` param, white 3dp stroke); tap mode: `FieldView(onTapWorld: ((EnuPoint) -> Unit)?)` — when non-null, single taps inverse-project (`dx = cosH*right + sinH*fwd; dy = -sinH*right + cosH*fwd` with `right=(tap.x-cx)/scale`, `fwd=(cy-tap.y)/scale`) and report world points; FieldScreen shows point count + "Cerrar parcela (N)" button.
4. Parcel ring conversion for display: ring stored lon/lat → ENU through the CURRENT pipeline plane (`PositionRepository` exposes the active plane; if none, skip drawing).

- [ ] **Steps: implement, `test assembleDebug` green, commit** `feat(app): parcels and jobs UI, report generation, field-view integration`

---

### Task 7: Phase-5 verification & checkpoint

- [ ] Clean + full `test assembleDebug` green.
- [ ] Manual script (demo): create parcel by taps → area shown; new job → paint → pause (stops painting) → resume → kill app from recents → reopen + Iniciar fuente → job resumed with coverage rebuilt → finish → HTML and PDF reports open with correct numbers and thumbnail; export parcel GeoJSON, re-import it.
- [ ] Checkpoint report (Spanish) + limitations.
- [ ] `git tag phase5-checkpoint`
