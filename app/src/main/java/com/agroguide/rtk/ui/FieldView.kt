package com.agroguide.rtk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agroguide.rtk.coverage.TileKey
import com.agroguide.rtk.coverage.TileSnapshot
import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.VehicleState
import com.agroguide.rtk.geo.guidance.GuidanceLine
import com.agroguide.rtk.geo.guidance.ImplementConfig
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

private const val ANCHOR_Y = 0.65f

/**
 * Offline canvas field view. Heading-up camera (double-tap toggles north-up),
 * pinch zoom. Draws grid, trail, guidance passes, A/B markers, implement bar
 * and the tractor anchored at 65% height.
 */
@Composable
fun FieldView(
    vehicle: VehicleState?,
    line: GuidanceLine?,
    passIndex: Int?,
    pendingA: EnuPoint?,
    trail: List<EnuPoint>,
    implement: ImplementConfig,
    tiles: Map<TileKey, TileSnapshot> = emptyMap(),
    parcelRing: List<EnuPoint>? = null,
    draftRing: List<EnuPoint> = emptyList(),
    onTapWorld: ((EnuPoint) -> Unit)? = null,
    basemap: com.agroguide.rtk.map.MapProvider = com.agroguide.rtk.map.MapProvider.NONE,
    basemapApiKey: String? = null,
    plane: com.agroguide.rtk.geo.TangentPlane? = null,
    onScaleChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(4f) }        // px per meter
    var headingUp by remember { mutableStateOf(true) }
    val tapHandler by androidx.compose.runtime.rememberUpdatedState(onTapWorld)
    val vehicleNow by androidx.compose.runtime.rememberUpdatedState(vehicle)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // recomposes the canvas as tiles arrive from disk/network
    val tileVersion by com.agroguide.rtk.map.TileCache.version.collectAsStateWithLifecycle()
    // world-space Paths per tile, rebuilt only when the tile version changes
    val tilePathCache = remember { mutableMapOf<TileKey, TilePaths>() }
    tilePathCache.keys.retainAll(tiles.keys)
    for ((key, snap) in tiles) {
        if (tilePathCache[key]?.version != snap.version) {
            tilePathCache[key] = TilePaths(snap.version, geometryToPath(snap.covered), geometryToPath(snap.overlap))
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 30f)
                    onScaleChange(scale)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { headingUp = !headingUp },
                    onTap = { pos ->
                        val handler = tapHandler ?: return@detectTapGestures
                        val v = vehicleNow ?: return@detectTapGestures
                        // inverse of project(): screen offset -> world point
                        val cx = size.width / 2f; val cy = size.height * ANCHOR_Y
                        val right = (pos.x - cx) / scale
                        val fwd = (cy - pos.y) / scale
                        val h = Math.toRadians(if (headingUp) v.headingDeg else 0.0)
                        val dx = cos(h) * right + sin(h) * fwd
                        val dy = -sin(h) * right + cos(h) * fwd
                        handler(EnuPoint(v.x + dx, v.y + dy))
                    },
                )
            },
    ) {
        drawRect(Color(0xFF1B2E1B))
        val v = vehicle ?: return@Canvas
        val camHeading = if (headingUp) v.headingDeg else 0.0
        val h = Math.toRadians(camHeading)
        val sinH = sin(h); val cosH = cos(h)
        val cx = size.width / 2f; val cy = size.height * ANCHOR_Y

        fun project(wx: Double, wy: Double): Offset {
            val dx = wx - v.x; val dy = wy - v.y
            val fwd = (sinH * dx + cosH * dy).toFloat()
            val right = (cosH * dx - sinH * dy).toFloat()
            return Offset(cx + right * scale, cy - fwd * scale)
        }

        val extent = (max(size.width, size.height) / scale).toDouble() + 20.0

        // basemap tiles first, so everything else paints on top of them
        if (basemap != com.agroguide.rtk.map.MapProvider.NONE && plane != null) {
            drawBasemap(
                basemap, basemapApiKey, plane, ctx, v, extent, scale, camHeading, ::project,
                tileVersion,
            )
        }

        // world-aligned 10 m grid
        val gridColor = if (basemap == com.agroguide.rtk.map.MapProvider.NONE) {
            Color(0x2200FF00)
        } else {
            Color(0x18FFFFFF)   // subtler over imagery
        }
        var gx = floor((v.x - extent) / 10.0) * 10.0
        while (gx <= v.x + extent) {
            drawLine(gridColor, project(gx, v.y - extent), project(gx, v.y + extent), 1f)
            gx += 10.0
        }
        var gy = floor((v.y - extent) / 10.0) * 10.0
        while (gy <= v.y + extent) {
            drawLine(gridColor, project(v.x - extent, gy), project(v.x + extent, gy), 1f)
            gy += 10.0
        }

        // coverage tiles: world-space paths (y negated) under one affine
        // transform equivalent to project(); only tiles near the vehicle
        withTransform({
            translate(cx, cy)
            rotate(degrees = -camHeading.toFloat(), pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-v.x.toFloat(), v.y.toFloat())
        }) {
            val tileSize = 50.0
            for ((key, paths) in tilePathCache) {
                val tcx = (key.ix + 0.5) * tileSize; val tcy = (key.iy + 0.5) * tileSize
                if (kotlin.math.abs(tcx - v.x) > extent + tileSize || kotlin.math.abs(tcy - v.y) > extent + tileSize) continue
                drawPath(paths.covered, Color(0x5532CD32))
                drawPath(paths.overlap, Color(0x88FF6D00))
            }
        }

        // trail
        if (trail.size >= 2) {
            val path = Path()
            val first = project(trail[0].x, trail[0].y)
            path.moveTo(first.x, first.y)
            for (i in 1 until trail.size) {
                val p = project(trail[i].x, trail[i].y)
                path.lineTo(p.x, p.y)
            }
            drawPath(path, Color(0x66FFFFFF), style = Stroke(width = 2.dp.toPx()))
        }

        // guidance passes
        when (line) {
            is GuidanceLine.Ab -> drawStraightPasses(
                line.a, line.b.x - line.a.x, line.b.y - line.a.y,
                v, passIndex ?: 0, implement.widthM, extent, ::project,
            )
            is GuidanceLine.APlus -> {
                val lh = Math.toRadians(line.headingDeg)
                drawStraightPasses(line.a, sin(lh), cos(lh), v, passIndex ?: 0, implement.widthM, extent, ::project)
            }
            is GuidanceLine.Curve -> drawCurvePasses(line.points, passIndex ?: 0, implement.widthM, ::project)
            null -> {}
        }

        // parcel outline (active job's parcel)
        parcelRing?.takeIf { it.size >= 3 }?.let { ring ->
            val path = Path()
            val first = project(ring[0].x, ring[0].y)
            path.moveTo(first.x, first.y)
            for (i in 1 until ring.size) {
                val p = project(ring[i].x, ring[i].y)
                path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, Color(0xCCFFFFFF), style = Stroke(width = 3.dp.toPx()))
        }

        // parcel draft in progress: vertices + open polyline
        if (draftRing.isNotEmpty()) {
            for (i in 1 until draftRing.size) {
                drawLine(
                    Color(0xFF00E5FF),
                    project(draftRing[i - 1].x, draftRing[i - 1].y),
                    project(draftRing[i].x, draftRing[i].y),
                    2.dp.toPx(),
                )
            }
            for (p in draftRing) drawCircle(Color(0xFF00E5FF), 5.dp.toPx(), project(p.x, p.y))
        }

        // AB markers + pending A
        (line as? GuidanceLine.Ab)?.let {
            drawCircle(Color(0xFF66BB6A), 6.dp.toPx(), project(it.a.x, it.a.y))
            drawCircle(Color(0xFFEF5350), 6.dp.toPx(), project(it.b.x, it.b.y))
        }
        pendingA?.let { drawCircle(Color(0xFF66BB6A), 8.dp.toPx(), project(it.x, it.y)) }

        // implement bar at the work point, perpendicular to vehicle heading
        val vh = Math.toRadians(v.headingDeg)
        val wrx = cos(vh); val wry = -sin(vh)
        val wp = implement.workPoint(EnuPoint(v.x, v.y), v.headingDeg)
        val half = implement.widthM / 2
        drawLine(
            Color(0xFF00E5FF),
            project(wp.x - wrx * half, wp.y - wry * half),
            project(wp.x + wrx * half, wp.y + wry * half),
            5.dp.toPx(),
        )

        // tractor triangle at anchor; in heading-up it always points up, in
        // north-up rotate by vehicle heading
        val tr = Path()
        if (headingUp) {
            tr.moveTo(cx, cy - 16f); tr.lineTo(cx - 10f, cy + 12f); tr.lineTo(cx + 10f, cy + 12f)
        } else {
            val a = Math.toRadians(v.headingDeg)
            fun rot(px: Float, py: Float): Offset {
                val s = sin(a).toFloat(); val c = cos(a).toFloat()
                return Offset(cx + px * c + py * s, cy + -px * s + py * c)
            }
            val p1 = rot(0f, -16f); val p2 = rot(-10f, 12f); val p3 = rot(10f, 12f)
            tr.moveTo(p1.x, p1.y); tr.lineTo(p2.x, p2.y); tr.lineTo(p3.x, p3.y)
        }
        tr.close()
        drawPath(tr, Color(0xFFFFC107))
    }
}

/**
 * Draws basemap tiles under the guidance overlay.
 *
 * Each Web Mercator tile is axis-aligned in the local ENU frame at field
 * scale, so it is drawn as an image placed at its north-west corner, rotated
 * by the camera heading and scaled to its ground size — the same mapping
 * `project()` applies to every other layer.
 */
private fun DrawScope.drawBasemap(
    provider: com.agroguide.rtk.map.MapProvider,
    apiKey: String?,
    plane: com.agroguide.rtk.geo.TangentPlane,
    context: android.content.Context,
    v: VehicleState,
    extentM: Double,
    scale: Float,
    camHeadingDeg: Double,
    project: (Double, Double) -> Offset,
    @Suppress("UNUSED_PARAMETER") tileVersion: Int,
) {
    val corners = listOf(
        plane.toGeo(v.x - extentM, v.y - extentM), plane.toGeo(v.x + extentM, v.y - extentM),
        plane.toGeo(v.x - extentM, v.y + extentM), plane.toGeo(v.x + extentM, v.y + extentM),
    )
    val minLat = corners.minOf { it.first }; val maxLat = corners.maxOf { it.first }
    val minLon = corners.minOf { it.second }; val maxLon = corners.maxOf { it.second }
    val z = com.agroguide.rtk.geo.tiles.WebMercator
        .zoomForScale(scale.toDouble(), v.latDeg)
        .coerceAtMost(provider.maxZoom)
    val tiles = com.agroguide.rtk.geo.tiles.WebMercator
        .tilesForBounds(minLat, maxLat, minLon, maxLon, z, maxTiles = 48)

    for (t in tiles) {
        val image = com.agroguide.rtk.map.TileCache.get(context, provider, t, apiKey) ?: continue
        val (nwLat, nwLon) = com.agroguide.rtk.geo.tiles.WebMercator.tileNorthWest(t.x, t.y, z)
        val (seLat, seLon) = com.agroguide.rtk.geo.tiles.WebMercator.tileNorthWest(t.x + 1, t.y + 1, z)
        val nw = plane.toEnu(nwLat, nwLon)
        val se = plane.toEnu(seLat, seLon)
        val wM = (se.x - nw.x).toFloat()
        val hM = (nw.y - se.y).toFloat()
        if (wM <= 0f || hM <= 0f) continue
        val origin = project(nw.x, nw.y)
        withTransform({
            translate(origin.x, origin.y)
            rotate(degrees = -camHeadingDeg.toFloat(), pivot = Offset.Zero)
            scale(
                wM * scale / image.width,
                hM * scale / image.height,
                pivot = Offset.Zero,
            )
        }) {
            drawImage(image, Offset.Zero)
        }
    }
}

/** World-space paths for one tile (y negated so north is up pre-transform). */
private class TilePaths(val version: Int, val covered: Path, val overlap: Path)

private fun geometryToPath(g: org.locationtech.jts.geom.Geometry): Path {
    val path = Path().apply { fillType = PathFillType.EvenOdd }
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

private inline fun DrawScope.drawStraightPasses(
    a: EnuPoint, dirX: Double, dirY: Double,
    v: VehicleState, passIndex: Int, widthM: Double, extent: Double,
    project: (Double, Double) -> Offset,
) {
    val len = kotlin.math.hypot(dirX, dirY)
    if (len < 1e-9) return
    val ux = dirX / len; val uy = dirY / len
    val rx = uy; val ry = -ux
    val along = (v.x - a.x) * ux + (v.y - a.y) * uy
    for (k in passIndex - 5..passIndex + 5) {
        val ox = a.x + rx * k * widthM; val oy = a.y + ry * k * widthM
        val p1 = project(ox + ux * (along - extent), oy + uy * (along - extent))
        val p2 = project(ox + ux * (along + extent), oy + uy * (along + extent))
        val current = k == passIndex
        drawLine(
            if (current) Color(0xFFFFEB3B) else Color(0x88FFFFFF),
            p1, p2,
            if (current) 4.dp.toPx() else 2.dp.toPx(),
        )
    }
}

private inline fun DrawScope.drawCurvePasses(
    points: List<EnuPoint>, passIndex: Int, widthM: Double,
    project: (Double, Double) -> Offset,
) {
    if (points.size < 2) return
    // per-point normals averaged from adjacent segments
    val nx = DoubleArray(points.size); val ny = DoubleArray(points.size)
    for (i in points.indices) {
        val p = points[(i - 1).coerceAtLeast(0)]
        val q = points[(i + 1).coerceAtMost(points.size - 1)]
        val dx = q.x - p.x; val dy = q.y - p.y
        val len = kotlin.math.hypot(dx, dy)
        if (len > 1e-9) { nx[i] = dy / len; ny[i] = -dx / len }
    }
    for (k in passIndex - 2..passIndex + 2) {
        val path = Path()
        val o = k * widthM
        val first = project(points[0].x + nx[0] * o, points[0].y + ny[0] * o)
        path.moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            val p = project(points[i].x + nx[i] * o, points[i].y + ny[i] * o)
            path.lineTo(p.x, p.y)
        }
        val current = k == passIndex
        drawPath(
            path,
            if (current) Color(0xFFFFEB3B) else Color(0x88FFFFFF),
            style = Stroke(width = if (current) 4.dp.toPx() else 2.dp.toPx()),
        )
    }
}
