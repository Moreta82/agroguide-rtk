package com.agroguide.rtk.map

import com.agroguide.rtk.geo.tiles.TileId

/**
 * Basemap sources. OSM and ESRI need no key and their tiles are cached on disk,
 * so once an area has been viewed (or pre-downloaded) it works offline.
 * GOOGLE uses the official Map Tiles API and needs the user's own API key.
 */
enum class MapProvider(val id: String, val maxZoom: Int, val needsKey: Boolean) {
    NONE("none", 0, false),
    OSM("osm", 19, false),
    ESRI_SATELLITE("esri", 19, false),
    GOOGLE_SATELLITE("google", 20, true);

    companion object {
        fun fromId(id: String?): MapProvider = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** Builds the tile URL for a provider; null when the provider cannot serve yet. */
fun MapProvider.tileUrl(tile: TileId, googleSession: String?, apiKey: String?): String? = when (this) {
    MapProvider.NONE -> null
    MapProvider.OSM ->
        "https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png"
    MapProvider.ESRI_SATELLITE ->
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
            "${tile.z}/${tile.y}/${tile.x}"
    MapProvider.GOOGLE_SATELLITE -> {
        if (googleSession.isNullOrEmpty() || apiKey.isNullOrEmpty()) null
        else "https://tile.googleapis.com/v1/2dtiles/${tile.z}/${tile.x}/${tile.y}" +
            "?session=$googleSession&key=$apiKey"
    }
}
