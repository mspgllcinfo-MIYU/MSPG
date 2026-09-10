package com.mspg.poicat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * A separate Room database for shared-file metadata, deliberately independent
 * from [AppDatabase] and [PhotoDatabase] — a brand-new database, so adding or
 * changing this schema can never require a migration of, or risk any damage
 * to, the data already stored in either of those.
 */
@Database(entities = [StoredFile::class], version = 1, exportSchema = true)
abstract class FileDatabase : RoomDatabase() {
    abstract fun storedFileDao(): StoredFileDao

    companion object {
        @Volatile
        private var instance: FileDatabase? = null

        fun get(context: Context): FileDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FileDatabase::class.java,
                    "poicat_files.db",
                ).build().also { instance = it }
            }
    }
}
