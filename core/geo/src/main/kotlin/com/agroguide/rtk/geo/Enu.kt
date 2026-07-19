package com.agroguide.rtk.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class EnuPoint(val x: Double, val y: Double)  // east, north (meters)

/**
 * Local East-North tangent plane on the WGS84 ellipsoid, anchored at an origin
 * that is recomputed per job. Uses per-origin meridian (M) and prime-vertical (N)
 * radii of curvature, which is millimeter-accurate over the few-km extent of a field.
 * All guidance math operates on these coordinates — never on raw lat/lon.
 */
class TangentPlane(val originLatDeg: Double, val originLonDeg: Double) {
    private val a = 6_378_137.0
    private val e2 = 6.69437999014e-3
    private val lat0 = originLatDeg * PI / 180
    private val sinLat0 = sin(lat0)
    private val w = sqrt(1 - e2 * sinLat0 * sinLat0)
    /** Meridian radius of curvature at origin (north scale). */
    private val mRad = a * (1 - e2) / (w * w * w)
    /** Prime vertical radius (east scale, includes cos(lat)). */
    private val nRad = a / w * cos(lat0)

    fun toEnu(latDeg: Double, lonDeg: Double): EnuPoint = EnuPoint(
        x = (lonDeg - originLonDeg) * PI / 180 * nRad,
        y = (latDeg - originLatDeg) * PI / 180 * mRad,
    )

    fun toGeo(x: Double, y: Double): Pair<Double, Double> = Pair(
        originLatDeg + y / mRad * 180 / PI,
        originLonDeg + x / nRad * 180 / PI,
    )
}
