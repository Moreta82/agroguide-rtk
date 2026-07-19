package com.agroguide.rtk.data.parcel

import com.agroguide.rtk.coverage.Swath
import com.agroguide.rtk.geo.EnuPoint
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Polygon

object ParcelGeometry {
    data class Measured(val areaM2: Double, val perimeterM: Double)

    /** Area/perimeter of an ENU ring (open — closing vertex added here). Null when degenerate. */
    fun measure(ring: List<EnuPoint>): Measured? {
        if (ring.size < 3) return null
        val coords = ring.map { Coordinate(it.x, it.y) } + Coordinate(ring[0].x, ring[0].y)
        val poly = runCatching { Swath.factory.createPolygon(coords.toTypedArray()) }.getOrNull() ?: return null
        val healed = if (poly.isValid) poly else (poly.buffer(0.0) as? Polygon ?: return null)
        if (healed.area < 1e-9) return null
        return Measured(healed.area, healed.exteriorRing.length)
    }
}
