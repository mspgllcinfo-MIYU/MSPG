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

    /** Rows never yet pushed to a shared room (created before a room existed, or while
     * unlinked) — used by [com.mspg.poicat.room.RoomBackfill] to send pre-existing local
     * data once a room is joined. A row that already has a roomEventId is never returned
     * here, so re-running the backfill after a partial failure only retries what's left. */
    @Query("SELECT * FROM cat_events WHERE roomEventId IS NULL")
    suspend fun unsyncedRoomEvents(): List<CatEvent>

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

    /** All tasks: incomplete first, soonest due date first, undated ones after dated ones.
     * #148フェーズ1: isTask=1の通常タスクに加えて、alsoShowAsTask=1の予定
     * (正本は予定のまま、タスクビューにも表示するハイブリッド予定)も含む。
     * 予定側のonDay/between/upcoming等のisTask=0条件は変更していないため、
     * ハイブリッド予定は引き続き予定としても取得される。 */
    @Query(
        "SELECT * FROM cat_events WHERE (isTask = 1 OR alsoShowAsTask = 1) " +
            "ORDER BY completed ASC, (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun tasks(): List<CatEvent>

    /** Not-yet-done tasks, soonest due date first. #148フェーズ1: alsoShowAsTask=1も含む。 */
    @Query(
        "SELECT * FROM cat_events WHERE (isTask = 1 OR alsoShowAsTask = 1) AND completed = 0 " +
            "ORDER BY (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun incompleteTasks(): List<CatEvent>

    /**
     * Not-yet-done tasks due within a date range, plus any with no due date at all — an
     * undated task ("牛乳買うの忘れないで" with no date) is open-ended, so it counts as
     * something to do today just as much as one due today specifically. Used to answer
     * "今日やることは？". #148フェーズ1: alsoShowAsTask=1も含む。
     */
    @Query(
        "SELECT * FROM cat_events WHERE (isTask = 1 OR alsoShowAsTask = 1) AND completed = 0 " +
            "AND (dateTime IS NULL OR dateTime BETWEEN :start AND :end) " +
            "ORDER BY (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun incompleteTasksDueOrUndated(start: Long, end: Long): List<CatEvent>

    /** Not-yet-done, dated tasks due at or before a point in time — used to answer "明日までのタスクは？".
     * #148フェーズ1: alsoShowAsTask=1も含む。 */
    @Query(
        "SELECT * FROM cat_events WHERE (isTask = 1 OR alsoShowAsTask = 1) AND completed = 0 " +
            "AND dateTime IS NOT NULL AND dateTime <= :end ORDER BY dateTime ASC",
    )
    suspend fun incompleteTasksDueBy(end: Long): List<CatEvent>

    /** Not-yet-done tasks whose title contains the given keyword — used to mark one done by name.
     * #148フェーズ1: alsoShowAsTask=1も含む。 */
    @Query(
        "SELECT * FROM cat_events WHERE (isTask = 1 OR alsoShowAsTask = 1) AND completed = 0 " +
            "AND title LIKE '%' || :keyword || '%' ORDER BY (dateTime IS NULL) ASC, dateTime ASC, createdAt DESC",
    )
    suspend fun incompleteTasksMatching(keyword: String): List<CatEvent>

    /** Every row (schedule, memo, or task alike) whose title contains the keyword — used by
     * cat AI photo search to find a memo/schedule a photo might be linked to. */
    @Query("SELECT * FROM cat_events WHERE title LIKE '%' || :keyword || '%'")
    suspend fun allMatching(keyword: String): List<CatEvent>

    /** Every row (schedule, task, or memo alike) that has a saved location — used by
     * #148 Maps-2D's read-only lookup of a saved place by its exact display name. This is
     * a plain read query, not a schema change: no new column, no Migration required. */
    @Query("SELECT * FROM cat_events WHERE locationText IS NOT NULL AND locationText != ''")
    suspend fun withLocation(): List<CatEvent>

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
