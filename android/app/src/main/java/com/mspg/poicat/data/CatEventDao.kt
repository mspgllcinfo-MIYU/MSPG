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

    /** An already-remembered schedule/memo with the exact same title and time, if any — used to avoid duplicates. */
    @Query("SELECT * FROM cat_events WHERE isTask = 0 AND dateTime = :dateTime AND title = :title LIMIT 1")
    suspend fun findDuplicate(title: String, dateTime: Long): CatEvent?

    /** Looks up a row by its room-sync id — used by [com.mspg.poicat.room.RoomEventSync] to
     * find the local counterpart of a Firestore-side event. */
    @Query("SELECT * FROM cat_events WHERE roomEventId = :roomEventId LIMIT 1")
    suspend fun byRoomEventId(roomEventId: String): CatEvent?

    /** Looks up a row by its local primary key — used after a first successful Firestore push
     * to record the newly-assigned roomEventId back onto the row that triggered it. */
    @Query("SELECT * FROM cat_events WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): CatEvent?

    /** All dated schedule events (tasks excluded), soonest first. */
    @Query("SELECT * FROM cat_events WHERE isTask = 0 AND dateTime IS NOT NULL AND dateTime >= :from ORDER BY dateTime ASC")
    suspend fun upcoming(from: Long): List<CatEvent>

    /** Dated schedule events whose title contains the given keyword, soonest first. */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 0 AND dateTime IS NOT NULL AND dateTime >= :from " +
            "AND title LIKE '%' || :keyword || '%' ORDER BY dateTime ASC",
    )
    suspend fun upcomingMatching(keyword: String, from: Long): List<CatEvent>

    @Query("SELECT * FROM cat_events WHERE isTask = 0 AND dateTime BETWEEN :startOfDay AND :endOfDay ORDER BY dateTime ASC")
    suspend fun onDay(startOfDay: Long, endOfDay: Long): List<CatEvent>

    /** Dated schedule events in a date range (e.g. a whole month), soonest first — used to mark days on the calendar. */
    @Query("SELECT * FROM cat_events WHERE isTask = 0 AND dateTime BETWEEN :start AND :end ORDER BY dateTime ASC")
    suspend fun between(start: Long, end: Long): List<CatEvent>

    /** Date-less memos (tasks excluded), newest first. */
    @Query("SELECT * FROM cat_events WHERE isTask = 0 AND dateTime IS NULL ORDER BY createdAt DESC")
    suspend fun memos(): List<CatEvent>

    /** Date-less memos whose content contains the given keyword, newest first. */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 0 AND dateTime IS NULL " +
            "AND title LIKE '%' || :keyword || '%' ORDER BY createdAt DESC",
    )
    suspend fun memosMatching(keyword: String): List<CatEvent>

    /** All tasks: incomplete first, soonest due date first, undated ones after dated ones. */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 1 " +
            "ORDER BY completed ASC, (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun tasks(): List<CatEvent>

    /** Not-yet-done tasks, soonest due date first. */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 1 AND completed = 0 " +
            "ORDER BY (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun incompleteTasks(): List<CatEvent>

    /**
     * Not-yet-done tasks due within a date range, plus any with no due date at all — an
     * undated task ("牛乳買うの忘れないで" with no date) is open-ended, so it counts as
     * something to do today just as much as one due today specifically. Used to answer
     * "今日やることは？".
     */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 1 AND completed = 0 " +
            "AND (dateTime IS NULL OR dateTime BETWEEN :start AND :end) " +
            "ORDER BY (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun incompleteTasksDueOrUndated(start: Long, end: Long): List<CatEvent>

    /** Not-yet-done, dated tasks due at or before a point in time — used to answer "明日までのタスクは？". */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 1 AND completed = 0 " +
            "AND dateTime IS NOT NULL AND dateTime <= :end ORDER BY dateTime ASC",
    )
    suspend fun incompleteTasksDueBy(end: Long): List<CatEvent>

    /** Not-yet-done tasks whose title contains the given keyword — used to mark one done by name. */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 1 AND completed = 0 " +
            "AND title LIKE '%' || :keyword || '%' ORDER BY (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun incompleteTasksMatching(keyword: String): List<CatEvent>

    /** Every row (schedule, memo, or task alike) whose title contains the keyword — used by
     * cat AI photo search to find a memo/schedule a photo might be linked to. */
    @Query("SELECT * FROM cat_events WHERE title LIKE '%' || :keyword || '%'")
    suspend fun allMatching(keyword: String): List<CatEvent>

    /** Dated, unfired schedule events whose reminder window has arrived — used by the periodic worker. */
    @Query(
        "SELECT * FROM cat_events WHERE isTask = 0 AND dateTime IS NOT NULL AND reminded1Day = 0 " +
            "AND dateTime BETWEEN :windowStart AND :windowEnd",
    )
    suspend fun dueFor1DayReminder(windowStart: Long, windowEnd: Long): List<CatEvent>

    @Query(
        "SELECT * FROM cat_events WHERE isTask = 0 AND dateTime IS NOT NULL AND reminded1Hour = 0 " +
            "AND dateTime BETWEEN :windowStart AND :windowEnd",
    )
    suspend fun dueFor1HourReminder(windowStart: Long, windowEnd: Long): List<CatEvent>
}
