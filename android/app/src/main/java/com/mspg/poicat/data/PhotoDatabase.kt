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
@Database(entities = [Photo::class, PhotoMemoLink::class], version = 6, exportSchema = true)
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

        // v2 -> v3: added the photo_memo_links join table (PhotoMemoLink, phase 4). A previous
        // attempt at this migration (commit c1430a6) was reverted (419af3a) after crashing on
        // real devices, and the DB fell back to a destructive recreate for this one jump ever
        // since. Root cause, found by comparing that reverted SQL against
        // app/schemas/com.mspg.poicat.data.PhotoDatabase/3.json (the schema Room's own
        // annotation processor actually compiled and exported for v3 — ground truth, not a
        // guess): the old SQL wrote `id` INTEGER PRIMARY KEY AUTOINCREMENT without a trailing
        // NOT NULL. SQLite's own `PRAGMA table_info` then reports that column as nullable,
        // while Room's compiled expectation (and the CREATE TABLE Room itself would generate)
        // has NOT NULL — a mismatch Room's migration validator rejects at runtime. The SQL
        // below is copied verbatim from that v3 schema json's own "createSql" (only the
        // `${TABLE_NAME}` placeholder replaced), so it is byte-for-byte what Room expects.
        // A brand new table only — the existing `photos` table (and every row already in it)
        // is completely untouched by this migration.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photo_memo_links` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`photoId` INTEGER NOT NULL, " +
                        "`eventId` INTEGER NOT NULL, " +
                        "`linkedAt` INTEGER NOT NULL)",
                )
            }
        }

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

        // v5 -> v6: added Photo.metadataUpdatedAt (キャプション/アルバム名/カレンダー日付
        // 編集の夫婦間同期用、last-write-wins比較タイムスタンプ)。MIGRATION_3_4の
        // driveSyncStatus列と同じ形(NOT NULL + DEFAULT)の1つのadditive ADD COLUMNのみ。
        // 既存の全ての写真はmetadataUpdatedAt=0になるだけで、caption/albumName/
        // linkedDate等の既存の値には一切触れない。
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN metadataUpdatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): PhotoDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhotoDatabase::class.java,
                    "poicat_photos.db",
                )
                    // v2->v3が正式なmigrationで埋まったため、v1〜v4のどのバージョンから
                    // 開始してもv5まで非破壊で到達できる — fallbackToDestructiveMigration()
                    // は完全に不要になったため外した(既存のPhoto/PhotoMemoLinkデータを
                    // 初期化するリスクを持つ設定を残さない)。
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}
