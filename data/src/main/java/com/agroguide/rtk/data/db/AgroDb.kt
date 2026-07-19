package com.agroguide.rtk.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ParcelEntity::class, JobEntity::class], version = 1, exportSchema = false)
abstract class AgroDb : RoomDatabase() {
    abstract fun parcels(): ParcelDao
    abstract fun jobs(): JobDao

    companion object {
        @Volatile private var instance: AgroDb? = null

        fun get(context: Context): AgroDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AgroDb::class.java, "agroguide.db")
                .build()
                .also { instance = it }
        }
    }
}
