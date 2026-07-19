package com.agroguide.rtk.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "parcels")
data class ParcelEntity(
    @PrimaryKey val id: String,              // UUID
    val name: String,
    val createdAtMs: Long,
    /** Open ring (no duplicate closing vertex) as "lon,lat lon,lat ..." pairs, WGS84. */
    val ringLonLat: String,
    val areaM2: Double,
    val perimeterM: Double,
)

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,              // UUID
    val name: String,
    val parcelId: String?,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val state: String,                       // ACTIVE | PAUSED | FINISHED
    val implementWidthM: Double,
    val sections: Int,
    val originLat: Double,
    val originLon: Double,
    val treatedM2: Double = 0.0,
    val overlapM2: Double = 0.0,
    val distanceM: Double = 0.0,
    val activeSeconds: Long = 0,
)
