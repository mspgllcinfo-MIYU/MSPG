package com.mspg.poicat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history:
 *  - v1: title, dateTime, createdAt, reminded1Day, reminded1Hour
 *  - v2: added isTask, completed (Poi tasks) — see MIGRATION_1_2
 *  - v3: added category (Poi work/private classification) — see MIGRATION_2_3
 *
 * Real schedules/memos/tasks now live in this database on-device, so any
 * future version bump must ship its own explicit Migration here (following
 * MIGRATION_1_2's pattern, same as PhotoDatabase.MIGRATION_1_2) instead of
 * falling back to a destructive recreate, which would silently wipe them.
 */
@Database(entities = [CatEvent::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catEventDao(): CatEventDao

    companion object {
        // v1 -> v2: added isTask/completed for the Poi tab. Both are NOT NULL
        // with a default, so every pre-existing schedule/memo row keeps its
        // data as-is and simply gains isTask=0 (not a task), completed=0.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cat_events ADD COLUMN isTask INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cat_events ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 -> v3: added category (Poi work/private classification). No NOT
        // NULL constraint and no DEFAULT, so every existing row (and every
        // non-task row) simply gains category=NULL — a real "unclassified"
        // state, not a guessed-at "private".
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cat_events ADD COLUMN category TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "poicat.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
    }
}
