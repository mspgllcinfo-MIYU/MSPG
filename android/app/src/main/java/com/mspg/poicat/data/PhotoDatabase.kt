package com.mspg.poicat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * A separate Room database for photo metadata, deliberately independent from
 * [AppDatabase] (which holds schedules/memos/tasks). Keeping it separate
 * means adding or changing the photo schema can never require a migration
 * of — or risk any damage to — the data already stored in `cat_events`.
 */
@Database(entities = [Photo::class], version = 1, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: PhotoDatabase? = null

        fun get(context: Context): PhotoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoDatabase::class.java,
                    "poicat_photos.db",
                ).build().also { instance = it }
            }
    }
}
