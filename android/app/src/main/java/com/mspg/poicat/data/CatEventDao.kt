package com.mspg.poicat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface CatEventDao {
    @Insert
    suspend fun insert(event: CatEvent): Long

    @Update
    suspend fun update(event: CatEvent)

    /** All future dated events, soonest first. */
    @Query("SELECT * FROM cat_events WHERE dateTime IS NOT NULL AND dateTime >= :from ORDER BY dateTime ASC")
    suspend fun upcoming(from: Long): List<CatEvent>

    /** Dated events whose title contains the given keyword, soonest first. */
    @Query(
        "SELECT * FROM cat_events WHERE dateTime IS NOT NULL AND dateTime >= :from " +
            "AND title LIKE '%' || :keyword || '%' ORDER BY dateTime ASC",
    )
    suspend fun upcomingMatching(keyword: String, from: Long): List<CatEvent>

    @Query("SELECT * FROM cat_events WHERE dateTime BETWEEN :startOfDay AND :endOfDay ORDER BY dateTime ASC")
    suspend fun onDay(startOfDay: Long, endOfDay: Long): List<CatEvent>

    /** Dated, unfired events whose reminder window has arrived — used by the periodic worker. */
    @Query(
        "SELECT * FROM cat_events WHERE dateTime IS NOT NULL AND reminded1Day = 0 " +
            "AND dateTime BETWEEN :windowStart AND :windowEnd",
    )
    suspend fun dueFor1DayReminder(windowStart: Long, windowEnd: Long): List<CatEvent>

    @Query(
        "SELECT * FROM cat_events WHERE dateTime IS NOT NULL AND reminded1Hour = 0 " +
            "AND dateTime BETWEEN :windowStart AND :windowEnd",
    )
    suspend fun dueFor1HourReminder(windowStart: Long, windowEnd: Long): List<CatEvent>
}
