package com.mspg.poicat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single thing the cat remembers: a schedule with a date+time, a plain
 * memo with no date, or (when [isTask] is set) a to-do item from the Poi
 * screen whose [dateTime] — if any — is its due date rather than a
 * reminder-worthy event time. Everything is stored only on-device
 * (Room/SQLite) — there is no server and no network call involved.
 */
@Entity(tableName = "cat_events")
data class CatEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    /** Epoch millis, or null for a date-less memo/task. */
    val dateTime: Long?,
    val createdAt: Long = System.currentTimeMillis(),
    val reminded1Day: Boolean = false,
    val reminded1Hour: Boolean = false,
    val isTask: Boolean = false,
    val completed: Boolean = false,
    /**
     * Poi task category — [CATEGORY_WORK] or [CATEGORY_PRIVATE], or null when
     * not yet classified (every pre-v3 row, and every non-task schedule/memo
     * row, for which this concept doesn't apply). Null is a real, permanent
     * state, not just a migration artifact — it's how an unclassified task
     * stays re-classifiable later rather than being forced into a guess.
     */
    val category: String? = null,
) {
    companion object {
        const val CATEGORY_WORK = "work"
        const val CATEGORY_PRIVATE = "private"
    }
}
