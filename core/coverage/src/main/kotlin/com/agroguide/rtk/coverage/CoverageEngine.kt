package com.agroguide.rtk.coverage

import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.operation.overlayng.OverlayNG
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
 * tiles and per-tile cached areas sum exactly — no double counting.
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

    /** Where the painted swath currently ends (the next segment starts here), or null after a break. */
    val anchor: WorkPose? get() = prevPose

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
            val quad = try {
                Swath.quad(prev, pose, from, to)
            } catch (_: RuntimeException) {
                null            // JTS refused to build/heal this quad
            } ?: continue
            // JTS overlay is not numerically bulletproof: a near-degenerate
            // swath (heading noise at crawl speed) can throw TopologyException.
            // Losing one segment of paint is acceptable; crashing is not.
            try {
                insert(quad)
            } catch (e: RuntimeException) {
                skippedSegments++
                lastError = e
            }
        }
    }

    /** Segments dropped because JTS could not process them (diagnostics only). */
    var skippedSegments = 0; private set
    var lastError: RuntimeException? = null; private set

    private fun insert(quad: Polygon) {
        val env = quad.envelopeInternal
        val ix0 = floor(env.minX / tileSizeM).toInt(); val ix1 = floor(env.maxX / tileSizeM).toInt()
        val iy0 = floor(env.minY / tileSizeM).toInt(); val iy1 = floor(env.maxY / tileSizeM).toInt()
        var treated = stats.treatedM2
        var overlapTotal = stats.overlapM2
        for (ix in ix0..ix1) for (iy in iy0..iy1) {
            val key = TileKey(ix, iy)
            val square = Swath.factory.toGeometry(
                Envelope(ix * tileSizeM, (ix + 1) * tileSizeM, iy * tileSizeM, (iy + 1) * tileSizeM)
            )
            val part = op(quad, square, OverlayNG.INTERSECTION)
            if (part.isEmpty || part.area < 1e-9) continue
            val tile = tilesMap.getOrPut(key) {
                Tile(Swath.factory.createPolygon(), Swath.factory.createPolygon())
            }
            val inter = op(tile.covered, part, OverlayNG.INTERSECTION)
            if (!inter.isEmpty && inter.area > MIN_OVERLAP_M2) {
                tile.overlap = op(tile.overlap, inter, OverlayNG.UNION)
            }
            tile.covered = op(tile.covered, part, OverlayNG.UNION)
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

    /**
     * Polygon overlay on a fixed 1 mm grid (OverlayNG snap-rounding).
     *
     * The legacy `Geometry.union/intersection` path uses floating-point
     * noding and throws TopologyException on near-coincident edges — which is
     * exactly what consecutive swaths from a real, slightly noisy receiver
     * produce. In a field replay it rejected ~45 % of segments, leaving
     * unpainted gaps and almost no overlap. Snap-rounding is provably robust;
     * millimetre precision is far below anything that matters for area.
     */
    private fun op(a: Geometry, b: Geometry, opCode: Int): Geometry =
        toPolygonal(OverlayNG.overlay(a, b, opCode, PRECISION))

    private companion object {
        val PRECISION = PrecisionModel(1000.0)   // 1 mm grid
        /** Ignore overlap specks smaller than a hand span (rounding at joints). */
        const val MIN_OVERLAP_M2 = 0.01
    }

    /**
     * Overlay ops on touching polygons can yield mixed GeometryCollections
     * (polygon + edge lines); keep only the areal part.
     */
    private fun toPolygonal(g: Geometry): Geometry {
        if (g.isEmpty) return Swath.factory.createPolygon()
        if (g is Polygon || g is MultiPolygon) return g
        val polys = PolygonExtracter.getPolygons(g).filterIsInstance<Polygon>()
        return when {
            polys.isEmpty() -> Swath.factory.createPolygon()
            polys.size == 1 -> polys[0]
            else -> Swath.factory.createMultiPolygon(polys.toTypedArray())
        }
    }
}
