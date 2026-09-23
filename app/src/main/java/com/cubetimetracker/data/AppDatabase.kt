package com.cubetimetracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Database interamente locale: nessuna sincronizzazione, nessun
// dato che lascia il dispositivo. File fisico in /data/data/<pkg>/databases/
@Database(
    entities = [Project::class, TagMapping::class, TimeSession::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun tagMappingDao(): TagMappingDao
    abstract fun timeSessionDao(): TimeSessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cube_time_tracker.db"
                ).build().also { INSTANCE = it }
            }
    }
}
