package com.agroguide.rtk.data.guidance

import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.TangentPlane
import com.agroguide.rtk.geo.guidance.GuidanceLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class LineDto(
    val type: String,
    val points: List<List<Double>>,
    val heading: Double? = null,
    /** Origin of the ENU frame the points are in; absent in files from <= 1.0.7. */
    val originLat: Double? = null,
    val originLon: Double? = null,
)

/** A persisted line plus the frame it was drawn in (null origin = unknown, legacy). */
data class StoredLine(val line: GuidanceLine, val originLatDeg: Double?, val originLonDeg: Double?) {
    val plane: TangentPlane?
        get() = if (originLatDeg != null && originLonDeg != null) TangentPlane(originLatDeg, originLonDeg) else null
}

/** JSON persistence for the active guidance line (survives app restarts). */
object GuidanceLineCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * ENU coordinates mean nothing without their frame, so pass the [plane]
     * the line is expressed in; it is stored alongside so the line can be put
     * back on the same ground in a later session.
     */
    fun toJson(line: GuidanceLine?, plane: TangentPlane? = null): String? {
        val dto = when (line) {
            null -> return null
            is GuidanceLine.Ab -> LineDto("AB", listOf(listOf(line.a.x, line.a.y), listOf(line.b.x, line.b.y)))
            is GuidanceLine.APlus -> LineDto("APLUS", listOf(listOf(line.a.x, line.a.y)), line.headingDeg)
            is GuidanceLine.Curve -> LineDto("CURVE", line.points.map { listOf(it.x, it.y) })
        }
        return json.encodeToString(
            LineDto.serializer(),
            dto.copy(originLat = plane?.originLatDeg, originLon = plane?.originLonDeg),
        )
    }

    fun fromJson(text: String): GuidanceLine? = decode(text)?.line

    /** The line and the frame it was saved in (origin null for legacy files). */
    fun decode(text: String): StoredLine? = runCatching {
        val dto = json.decodeFromString(LineDto.serializer(), text)
        val pts = dto.points.map { EnuPoint(it[0], it[1]) }
        val line = when (dto.type) {
            "AB" -> if (pts.size == 2) GuidanceLine.Ab(pts[0], pts[1]) else null
            "APLUS" -> if (pts.size == 1 && dto.heading != null) GuidanceLine.APlus(pts[0], dto.heading) else null
            "CURVE" -> if (pts.size >= 2) GuidanceLine.Curve(pts) else null
            else -> null
        }
        line?.let { StoredLine(it, dto.originLat, dto.originLon) }
    }.getOrNull()
}
