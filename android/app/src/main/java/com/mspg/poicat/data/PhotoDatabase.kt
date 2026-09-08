package com.mspg.poicat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A separate Room database for photo metadata, deliberately independent from
 * [AppDatabase] (which holds schedules/memos/tasks). Keeping it separate
 * means adding or changing the photo schema can never require a migration
 * of — or risk any damage to — the data already stored in `cat_events`.
 */
@Database(entities = [Photo::class, PhotoMemoLink::class], version = 3, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun photoMemoLinkDao(): PhotoMemoLinkDao

    companion object {
        @Volatile
        private var instance: PhotoDatabase? = null

        // v1 -> v2: added Photo.linkedDate (calendar-day link). A single additive
        // ADD COLUMN, so existing photos/albums from v1 are kept as-is with
        // linkedDate simply starting out unset (NULL).
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN linkedDate INTEGER")
            }
        }

        // v2 -> v3: added the photo_memo_links join table (memo <-> photo linking).
        // A brand new table only — existing photos/albums/calendar links are untouched.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photo_memo_links` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "`photoId` INTEGER NOT NULL, " +
                        "`eventId` INTEGER NOT NULL, " +
                        "`linkedAt` INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): PhotoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoDatabase::class.java,
                    "poicat_photos.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}
