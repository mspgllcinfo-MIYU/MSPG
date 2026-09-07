package com.mspg.poicat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CatEvent::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catEventDao(): CatEventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "poicat.db",
                )
                    // Pre-release app with no exported schema history yet — an on-device
                    // schema change simply starts fresh rather than needing a migration.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
