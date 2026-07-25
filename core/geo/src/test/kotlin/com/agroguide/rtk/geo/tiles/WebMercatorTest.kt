package com.agroguide.rtk.geo.tiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebMercatorTest {
    @Test fun `known tile numbers at zoom 0 and 1`() {
        // Whole world is one tile at z0
        assertEquals(0, WebMercator.tileX(0.0, 0))
        assertEquals(0, WebMercator.tileY(0.0, 0))
        // Greenwich equator sits on the NW corner of tile (1,1) at z1
        assertEquals(1, WebMercator.tileX(0.0001, 1))
        assertEquals(1, WebMercator.tileY(-0.0001, 1))
    }

    @Test fun `tile corner roundtrip`() {
        val z = 15
        val lat = 42.598; val lon = -5.567
        val x = WebMercator.tileX(lon, z)
        val y = WebMercator.tileY(lat, z)
        val (nwLat, nwLon) = WebMercator.tileNorthWest(x, y, z)
        val (seLat, seLon) = WebMercator.tileNorthWest(x + 1, y + 1, z)
        assertTrue(lon in nwLon..seLon, "lon $lon not in $nwLon..$seLon")
        assertTrue(lat in seLat..nwLat, "lat $lat not in $seLat..$nwLat")
    }

    @Test fun `ground resolution halves per zoom level`() {
        val r14 = WebMercator.groundResolution(42.0, 14)
        val r15 = WebMercator.groundResolution(42.0, 15)
        assertEquals(r14 / 2, r15, 1e-9)
        // 156543.034 * cos(42) / 2^17 = 0.888 m/px
        assertEquals(0.888, WebMercator.groundResolution(42.0, 17), 0.002)
    }

    @Test fun `zoom for scale matches screen pixels to ground pixels`() {
        // 4 px per meter -> 0.25 m/px -> z ~ 19 at lat 42
        assertEquals(19, WebMercator.zoomForScale(4.0, 42.0))
        // 0.5 px per meter -> 2 m/px -> z ~ 16
        assertEquals(16, WebMercator.zoomForScale(0.5, 42.0))
    }

    @Test fun `zoom is clamped to the usable range`() {
        assertTrue(WebMercator.zoomForScale(1000.0, 42.0) <= WebMercator.MAX_ZOOM)
        assertTrue(WebMercator.zoomForScale(0.0001, 42.0) >= WebMercator.MIN_ZOOM)
    }

    @Test fun `tilesForBounds covers the box and respects the cap`() {
        val tiles = WebMercator.tilesForBounds(42.59, 42.61, -5.58, -5.55, 16, maxTiles = 200)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.z == 16 })
        val xs = tiles.map { it.x }.toSet()
        val ys = tiles.map { it.y }.toSet()
        assertEquals(xs.size * ys.size, tiles.size)   // full rectangle
        val capped = WebMercator.tilesForBounds(40.0, 44.0, -8.0, -2.0, 16, maxTiles = 50)
        assertTrue(capped.size <= 50)
    }
}
