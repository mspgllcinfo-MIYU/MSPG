package com.mspg.poicat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single thing the cat remembers: a schedule/task with a date+time, or a
 * plain memo with no date. Everything is stored only on-device (Room/SQLite)
 * — there is no server and no network call involved.
 */
@Entity(tableName = "cat_events")
data class CatEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    /** Epoch millis, or null for a date-less memo. */
    val dateTime: Long?,
    val createdAt: Long = System.currentTimeMillis(),
    val reminded1Day: Boolean = false,
    val reminded1Hour: Boolean = false,
)
