package com.agroguide.rtk.data.guidance

import com.agroguide.rtk.geo.EnuPoint
import com.agroguide.rtk.geo.guidance.GuidanceLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class LineDto(
    val type: String,
    val points: List<List<Double>>,
    val heading: Double? = null,
)

/** JSON persistence for the active guidance line (survives app restarts). */
object GuidanceLineCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(line: GuidanceLine?): String? = when (line) {
        null -> null
        is GuidanceLine.Ab -> json.encodeToString(
            LineDto.serializer(),
            LineDto("AB", listOf(listOf(line.a.x, line.a.y), listOf(line.b.x, line.b.y))),
        )
        is GuidanceLine.APlus -> json.encodeToString(
            LineDto.serializer(),
            LineDto("APLUS", listOf(listOf(line.a.x, line.a.y)), line.headingDeg),
        )
        is GuidanceLine.Curve -> json.encodeToString(
            LineDto.serializer(),
            LineDto("CURVE", line.points.map { listOf(it.x, it.y) }),
        )
    }

    fun fromJson(text: String): GuidanceLine? = runCatching {
        val dto = json.decodeFromString(LineDto.serializer(), text)
        val pts = dto.points.map { EnuPoint(it[0], it[1]) }
        when (dto.type) {
            "AB" -> if (pts.size == 2) GuidanceLine.Ab(pts[0], pts[1]) else null
            "APLUS" -> if (pts.size == 1 && dto.heading != null) GuidanceLine.APlus(pts[0], dto.heading) else null
            "CURVE" -> if (pts.size >= 2) GuidanceLine.Curve(pts) else null
            else -> null
        }
    }.getOrNull()
}
