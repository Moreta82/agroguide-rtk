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
