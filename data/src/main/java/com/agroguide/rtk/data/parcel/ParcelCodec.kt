package com.agroguide.rtk.data.parcel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

data class ImportedParcel(val name: String, val ringLonLat: List<Pair<Double, Double>>)

/**
 * Minimal GeoJSON / KML codecs for parcel polygons. Rings are stored OPEN in
 * the app (no duplicate closing vertex); both exporters close them on output
 * as the formats require, and both importers strip a closing duplicate.
 */
object ParcelCodec {

    // ---------- GeoJSON ----------

    fun toGeoJson(name: String, ring: List<Pair<Double, Double>>): String {
        val closed = if (ring.isNotEmpty()) ring + ring.first() else ring
        val obj = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", buildJsonArray {
                add(buildJsonObject {
                    put("type", "Feature")
                    put("properties", buildJsonObject { put("name", name) })
                    put("geometry", buildJsonObject {
                        put("type", "Polygon")
                        put("coordinates", buildJsonArray {
                            add(buildJsonArray {
                                for ((lon, lat) in closed) {
                                    add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(lon)); add(kotlinx.serialization.json.JsonPrimitive(lat)) })
                                }
                            })
                        })
                    })
                })
            })
        }
        return obj.toString()
    }

    fun fromGeoJson(text: String): List<ImportedParcel> = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        when (root["type"]?.jsonPrimitive?.content) {
            "FeatureCollection" -> root["features"]!!.jsonArray.mapNotNull { featureToParcel(it.jsonObject) }
            "Feature" -> listOfNotNull(featureToParcel(root))
            "Polygon", "MultiPolygon" -> listOfNotNull(geometryToParcel("Parcela", root))
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun featureToParcel(feature: JsonObject): ImportedParcel? {
        val name = feature["properties"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "Parcela"
        return geometryToParcel(name, feature["geometry"]?.jsonObject ?: return null)
    }

    private fun geometryToParcel(name: String, geometry: JsonObject): ImportedParcel? {
        val ringArray: JsonArray = when (geometry["type"]?.jsonPrimitive?.content) {
            "Polygon" -> geometry["coordinates"]!!.jsonArray[0].jsonArray
            "MultiPolygon" -> geometry["coordinates"]!!.jsonArray[0].jsonArray[0].jsonArray
            else -> return null
        }
        val ring = ringArray.map { pt ->
            val a = pt.jsonArray
            a[0].jsonPrimitive.double to a[1].jsonPrimitive.double
        }
        return ImportedParcel(name, stripClosing(ring)).takeIf { it.ringLonLat.size >= 3 }
    }

    // ---------- KML ----------

    fun toKml(name: String, ring: List<Pair<Double, Double>>): String {
        val closed = if (ring.isNotEmpty()) ring + ring.first() else ring
        val coords = closed.joinToString(" ") { (lon, lat) ->
            String.format(Locale.US, "%.8f,%.8f,0", lon, lat)
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>${name.replace("<", "&lt;")}</name>
      <Polygon><outerBoundaryIs><LinearRing>
        <coordinates>$coords</coordinates>
      </LinearRing></outerBoundaryIs></Polygon>
    </Placemark>
  </Document>
</kml>
"""
    }

    private val placemarkRe = Regex("<Placemark[\\s\\S]*?</Placemark>", RegexOption.IGNORE_CASE)
    private val nameRe = Regex("<name>([\\s\\S]*?)</name>", RegexOption.IGNORE_CASE)
    private val coordsRe = Regex("<coordinates>([\\s\\S]*?)</coordinates>", RegexOption.IGNORE_CASE)

    fun fromKml(text: String): List<ImportedParcel> = runCatching {
        placemarkRe.findAll(text).mapNotNull { pm ->
            val block = pm.value
            val name = nameRe.find(block)?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: "Parcela"
            val coordText = coordsRe.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val ring = coordText.trim().split(Regex("\\s+")).mapNotNull { triplet ->
                val parts = triplet.split(',')
                if (parts.size < 2) return@mapNotNull null
                val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                lon to lat
            }
            ImportedParcel(name, stripClosing(ring)).takeIf { it.ringLonLat.size >= 3 }
        }.toList()
    }.getOrDefault(emptyList())

    private fun stripClosing(ring: List<Pair<Double, Double>>): List<Pair<Double, Double>> =
        if (ring.size >= 2 && ring.first() == ring.last()) ring.dropLast(1) else ring
}
