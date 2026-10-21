package com.agroguide.rtk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clipToBounds
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
    style: FieldStyle = FieldStyle(),
    /** Live paint from the end of the painted swath to the implement (see CoverageRepository.tail). */
    tail: com.agroguide.rtk.coverage.WorkPose? = null,
    sections: List<Boolean> = listOf(true),
    camera: FieldCamera = rememberFieldCamera(),
    modifier: Modifier = Modifier,
) {
    var scale by camera.scaleState
    var headingUp by camera.headingUpState
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
            // Compose does not clip draw calls: rotated basemap tiles and pass
            // lines would otherwise paint over the tab bar above the canvas.
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> camera.zoomBy(zoom) }
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
        drawRect(style.background)
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

        // world-aligned 10 m grid (optional)
        if (style.showGrid) {
            val gridColor = if (basemap == com.agroguide.rtk.map.MapProvider.NONE) {
                style.grid
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
                drawPath(paths.covered, style.coverage)
                drawPath(paths.overlap, style.overlap)
            }
        }

        // Live tail: fixes (and so the painted tiles) arrive at the receiver
        // rate, the vehicle is drawn at 15 Hz. Paint the strip between the end
        // of the swath and the implement every frame so the colour stays glued
        // to the implement instead of trailing up to a second behind it.
        if (tail != null) {
            val wpNow = implement.workPoint(EnuPoint(v.x, v.y), v.headingDeg)
            val d = kotlin.math.hypot(wpNow.x - tail.p.x, wpNow.y - tail.p.y)
            if (d >= com.agroguide.rtk.coverage.Swath.MIN_SEG_M && d <= MAX_TAIL_M) {
                val ah = Math.toRadians(tail.headingDeg); val bh = Math.toRadians(v.headingDeg)
                val arx = cos(ah); val ary = -sin(ah)
                val brx = cos(bh); val bry = -sin(bh)
                val n = sections.size
                for (s in 0 until n) {
                    if (!sections[s]) continue
                    val (from, to) = com.agroguide.rtk.coverage.Swath.sectionExtent(implement.widthM, n, s)
                    val p1 = project(tail.p.x + arx * from, tail.p.y + ary * from)
                    val p2 = project(wpNow.x + brx * from, wpNow.y + bry * from)
                    val p3 = project(wpNow.x + brx * to, wpNow.y + bry * to)
                    val p4 = project(tail.p.x + arx * to, tail.p.y + ary * to)
                    val quad = Path().apply {
                        moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close()
                    }
                    drawPath(quad, style.coverage)
                }
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
            drawPath(path, style.trail, style = Stroke(width = 2.dp.toPx()))
        }

        // guidance passes
        when (line) {
            is GuidanceLine.Ab -> drawStraightPasses(
                line.a, line.b.x - line.a.x, line.b.y - line.a.y,
                v, passIndex ?: 0, implement.widthM, extent, ::project, style,
            )
            is GuidanceLine.APlus -> {
                val lh = Math.toRadians(line.headingDeg)
                drawStraightPasses(line.a, sin(lh), cos(lh), v, passIndex ?: 0, implement.widthM, extent, ::project, style)
            }
            is GuidanceLine.Curve -> drawCurvePasses(line.points, passIndex ?: 0, implement.widthM, ::project, style)
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
            drawPath(path, style.outline, style = Stroke(width = 3.dp.toPx()))
        }

        // parcel draft in progress: vertices + open polyline
        if (draftRing.isNotEmpty()) {
            for (i in 1 until draftRing.size) {
                drawLine(
                    style.draft,
                    project(draftRing[i - 1].x, draftRing[i - 1].y),
                    project(draftRing[i].x, draftRing[i].y),
                    2.dp.toPx(),
                )
            }
            for (p in draftRing) drawCircle(style.draft, 5.dp.toPx(), project(p.x, p.y))
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
            style.implement,
            project(wp.x - wrx * half, wp.y - wry * half),
            project(wp.x + wrx * half, wp.y + wry * half),
            5.dp.toPx(),
        )

        // Vehicle at the anchor. Heading-up: always points up. North-up:
        // rotate clockwise by the heading (screen y grows downwards). The old
        // hand-rolled rotation was mirrored and pointed west when driving east.
        val vehicleRotation = if (headingUp) 0f else v.headingDeg.toFloat()
        // Icon units: never smaller than 1 dp (readable when zoomed out), and
        // true to scale when zoomed in (the tractor is ~34 units ≈ 2.5 m wide),
        // so tractor, implement and swath all grow together.
        val unit = max(1.dp.toPx(), scale * TRACTOR_M_PER_UNIT)
        withTransform({
            translate(cx, cy)
            rotate(degrees = vehicleRotation, pivot = Offset.Zero)
        }) {
            when (style.vehicle) {
                com.agroguide.rtk.data.VehicleIcon.TRACTOR -> drawMiniTractor(unit)
                com.agroguide.rtk.data.VehicleIcon.ARROW -> drawArrowVehicle(unit)
            }
        }
    }
}

/** Real-world metres per tractor-icon unit (icon ≈ 34 units wide ≈ 2.5 m). */
private const val TRACTOR_M_PER_UNIT = 0.075f

/** Longest gap the live tail will bridge; beyond that it is a jump, not driving. */
private const val MAX_TAIL_M = 10.0

/**
 * Zoom and orientation of the field view. Hoisted so it survives the canvas
 * moving between layouts (full screen ⇄ data panel) instead of resetting.
 */
class FieldCamera(private val density: Float, initialScale: Float, initialHeadingUp: Boolean) {
    val scaleState = androidx.compose.runtime.mutableFloatStateOf(initialScale)
    val headingUpState = androidx.compose.runtime.mutableStateOf(initialHeadingUp)

    /** px per metre limits: from ~2 km across down to ~4 m across a phone. */
    private val minScale get() = 0.2f * density
    private val maxScale get() = 120f * density

    fun zoomBy(factor: Float) {
        scaleState.floatValue = (scaleState.floatValue * factor).coerceIn(minScale, maxScale)
    }

    companion object {
        /** Starts at 5 dp/m: a 6 m swath about as wide as the vehicle icon. */
        const val DEFAULT_DP_PER_M = 5f
    }
}

@Composable
fun rememberFieldCamera(): FieldCamera {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val saved = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { listOf(it.scaleState.floatValue, if (it.headingUpState.value) 1f else 0f) },
            restore = { FieldCamera(density, it[0], it[1] > 0.5f) },
        ),
    ) { FieldCamera(density, FieldCamera.DEFAULT_DP_PER_M * density, true) }
    return saved
}

/** Classic yellow triangle marker, tip pointing forward (up), centred on 0,0. */
private fun DrawScope.drawArrowVehicle(u: Float) {
    val tr = Path().apply { moveTo(0f, -16f * u); lineTo(-10f * u, 12f * u); lineTo(10f * u, 12f * u); close() }
    drawPath(tr, Color(0xFFFFC107))
    drawPath(tr, Color(0xFF5D4300), style = Stroke(width = 1.5f * u))
}

/**
 * Mini tractor seen from above, nose pointing up, antenna (0,0) on the cab.
 * Drawn in dp units scaled by [u] so it keeps its size at every zoom level.
 */
private fun DrawScope.drawMiniTractor(u: Float) {
    fun r(l: Float, t: Float, w: Float, h: Float, c: Color, radius: Float = 1.5f) =
        drawRoundRect(
            c,
            topLeft = Offset(l * u, t * u),
            size = androidx.compose.ui.geometry.Size(w * u, h * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * u),
        )
    val tyre = Color(0xFF1E1E1E)
    val tread = Color(0xFF3A3A3A)
    val body = Color(0xFF2E7D32)
    val bodyDark = Color(0xFF1B5E20)
    val yellow = Color(0xFFF5C518)

    // drop shadow so it reads on white and dark fields alike
    r(-15f, -27f, 30f, 47f, Color(0x33000000), 6f)
    // rear tyres (big) and front tyres (narrow)
    r(-17f, 0f, 8f, 18f, tyre, 2f); r(9f, 0f, 8f, 18f, tyre, 2f)
    for (k in 0..3) { r(-17f, 2f + k * 4f, 8f, 1.4f, tread, 0f); r(9f, 2f + k * 4f, 8f, 1.4f, tread, 0f) }
    r(-13f, -24f, 5f, 11f, tyre, 1.5f); r(8f, -24f, 5f, 11f, tyre, 1.5f)
    // front axle
    r(-9f, -19.5f, 18f, 2f, Color(0xFF424242), 0.5f)
    // hood and grille
    r(-6f, -26f, 12f, 22f, body, 3f)
    r(-4f, -27f, 8f, 3f, yellow, 1f)
    // rear fenders over the big tyres
    r(-18f, -1f, 10f, 4f, bodyDark, 1.5f); r(8f, -1f, 10f, 4f, bodyDark, 1.5f)
    // cab body and roof
    r(-9f, -6f, 18f, 20f, body, 3f)
    r(-8f, -5f, 16f, 16f, yellow, 2.5f)
    r(-6.5f, -3.5f, 13f, 13f, Color(0xFFFFE082), 2f)
    // exhaust stack beside the hood
    drawCircle(Color(0xFF424242), 1.6f * u, Offset(4.5f * u, -9f * u))
    // GNSS antenna on the roof (the guided reference point)
    drawCircle(Color(0xFF14201A), 2.4f * u, Offset.Zero)
    drawCircle(Color.White, 1.1f * u, Offset.Zero)
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
    style: FieldStyle,
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
            if (current) style.currentPass else style.pass,
            p1, p2,
            if (current) 4.dp.toPx() else 2.dp.toPx(),
        )
    }
}

private inline fun DrawScope.drawCurvePasses(
    points: List<EnuPoint>, passIndex: Int, widthM: Double,
    project: (Double, Double) -> Offset,
    style: FieldStyle,
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
            if (current) style.currentPass else style.pass,
            style = Stroke(width = if (current) 4.dp.toPx() else 2.dp.toPx()),
        )
    }
}
