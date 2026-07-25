package com.agroguide.rtk.geo.tiles

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** One raster tile in the standard slippy-map (Web Mercator) scheme. */
data class TileId(val z: Int, val x: Int, val y: Int)

/**
 * Web Mercator (EPSG:3857) tile math, shared by every basemap provider.
 * Pure Kotlin so tile selection stays unit-testable.
 */
object WebMercator {
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 19
    const val TILE_PX = 256

    /** Meters per pixel of a 256 px tile at the given latitude and zoom. */
    fun groundResolution(latDeg: Double, z: Int): Double =
        156_543.03392804097 * cos(latDeg * PI / 180) / 2.0.pow(z)

    fun tileX(lonDeg: Double, z: Int): Int =
        floor((lonDeg + 180.0) / 360.0 * 2.0.pow(z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun tileY(latDeg: Double, z: Int): Int {
        val lat = latDeg.coerceIn(-85.05112878, 85.05112878) * PI / 180
        val y = (1.0 - asinh(tan(lat)) / PI) / 2.0 * 2.0.pow(z)
        return floor(y).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /** North-west corner of tile (x, y) as (latDeg, lonDeg). */
    fun tileNorthWest(x: Int, y: Int, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z)
        val lon = x / n * 360.0 - 180.0
        val lat = atan(sinh(PI * (1 - 2 * y / n))) * 180.0 / PI
        return lat to lon
    }

    /**
     * Zoom whose tile pixels are closest to screen pixels for a view drawn at
     * [pxPerM] screen pixels per ground meter.
     */
    fun zoomForScale(pxPerM: Double, latDeg: Double): Int {
        if (pxPerM <= 0) return MIN_ZOOM
        val z = ln(156_543.03392804097 * cos(latDeg * PI / 180) * pxPerM) / ln(2.0)
        return Math.round(z).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    /**
     * All tiles covering the lat/lon box at [z], centre-first, capped at
     * [maxTiles] so a zoomed-out request can never explode.
     */
    fun tilesForBounds(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        z: Int, maxTiles: Int = 256,
    ): List<TileId> {
        val x0 = tileX(minLon, z); val x1 = tileX(maxLon, z)
        val y0 = tileY(maxLat, z); val y1 = tileY(minLat, z)   // y grows southwards
        val out = ArrayList<TileId>()
        val cx = (x0 + x1) / 2.0
        val cy = (y0 + y1) / 2.0
        for (x in x0..x1) for (y in y0..y1) out += TileId(z, x, y)
        if (out.size <= maxTiles) return out
        return out.sortedBy { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) }.take(maxTiles)
    }
}
