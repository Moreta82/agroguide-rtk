package com.agroguide.rtk.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ParcelDao {
    @Query("SELECT * FROM parcels ORDER BY name")
    fun all(): Flow<List<ParcelEntity>>

    @Query("SELECT * FROM parcels WHERE id = :id")
    suspend fun byId(id: String): ParcelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(parcel: ParcelEntity)

    @Query("UPDATE parcels SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM parcels WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY startedAtMs DESC")
    fun all(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun byId(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE state != 'FINISHED' LIMIT 1")
    suspend fun findUnfinished(): JobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: JobEntity)

    @Update
    suspend fun update(job: JobEntity)
}
