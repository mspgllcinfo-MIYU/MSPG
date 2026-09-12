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
@Database(entities = [Photo::class, PhotoMemoLink::class], version = 5, exportSchema = true)
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

        // v2 -> v3 (added the photo_memo_links join table) has no hand-written migration:
        // a hand-rolled CREATE TABLE has to match Room's own compiled-in expectation of the
        // schema byte-for-byte (column types/nullability/etc.), which isn't practical to
        // verify without an Android toolchain, and got this wrong once already. Falling
        // back to a destructive recreate for this one jump only resets the *photo*
        // database (photos/albums/calendar-links/memo-links) — cat_events (schedules,
        // memos, tasks) is a completely separate database untouched by this.

        // v3 -> v4: added Photo.driveSyncStatus/driveFileId (Google Drive upload tracking).
        // Two additive ADD COLUMNs, so every existing photo/album from v3 is kept exactly
        // as-is — driveSyncStatus simply starts out "PENDING" (never auto-retried; only
        // newly-added photos going forward trigger an upload attempt) and driveFileId NULL.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN driveSyncStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE photos ADD COLUMN driveFileId TEXT")
            }
        }

        // v4 -> v5: added Photo.deletedAt (論理削除/tombstone用タイムスタンプ)。1つの
        // additive ADD COLUMNのみ、NOT NULL制約もDEFAULTも無いため既存の全ての写真は
        // deletedAt=NULL(=未削除)としてそのまま残る — 物理削除は一切行わない。
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN deletedAt INTEGER")
            }
        }

        fun get(context: Context): PhotoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoDatabase::class.java,
                    "poicat_photos.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
