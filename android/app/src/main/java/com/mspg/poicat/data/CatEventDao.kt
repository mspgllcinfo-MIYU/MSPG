package com.mspg.poicat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface CatEventDao {
    @Insert
    suspend fun insert(event: CatEvent): Long

    @Update
    suspend fun update(event: CatEvent)

    @Delete
    suspend fun delete(event: CatEvent)

    /** An already-remembered event with the exact same title and time, if any — used to avoid duplicates. */
    @Query("SELECT * FROM cat_events WHERE dateTime = :dateTime AND title = :title LIMIT 1")
    suspend fun findDuplicate(title: String, dateTime: Long): CatEvent?

    /** All dated events, soonest first. */
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

    /** Dated events in a date range (e.g. a whole month), soonest first — used to mark days on the calendar. */
    @Query("SELECT * FROM cat_events WHERE dateTime BETWEEN :start AND :end ORDER BY dateTime ASC")
    suspend fun between(start: Long, end: Long): List<CatEvent>

    /** Date-less memos, newest first. */
    @Query("SELECT * FROM cat_events WHERE dateTime IS NULL ORDER BY createdAt DESC")
    suspend fun memos(): List<CatEvent>

    /** Date-less memos whose content contains the given keyword, newest first. */
    @Query("SELECT * FROM cat_events WHERE dateTime IS NULL AND title LIKE '%' || :keyword || '%' ORDER BY createdAt DESC")
    suspend fun memosMatching(keyword: String): List<CatEvent>

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
