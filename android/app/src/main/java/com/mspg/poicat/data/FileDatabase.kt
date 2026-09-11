package com.mspg.poicat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A separate Room database for shared-file metadata, deliberately independent
 * from [AppDatabase] and [PhotoDatabase] — a brand-new database, so adding or
 * changing this schema can never require a migration of, or risk any damage
 * to, the data already stored in either of those.
 */
@Database(entities = [StoredFile::class], version = 2, exportSchema = true)
abstract class FileDatabase : RoomDatabase() {
    abstract fun storedFileDao(): StoredFileDao

    companion object {
        @Volatile
        private var instance: FileDatabase? = null

        // v1 -> v2: added StoredFile.driveSyncStatus/driveFileId (Google Drive upload
        // tracking, mirroring PhotoDatabase's MIGRATION_3_4). Two additive ADD COLUMNs,
        // so every existing file from v1 is kept exactly as-is — driveSyncStatus simply
        // starts out "PENDING" (never auto-retried) and driveFileId NULL.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stored_files ADD COLUMN driveSyncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE stored_files ADD COLUMN driveFileId TEXT")
            }
        }

        fun get(context: Context): FileDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FileDatabase::class.java,
                    "poicat_files.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
