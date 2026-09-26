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

    /** Already-remembered schedules with exactly this [dateTime] — used by
     * [CatEventRepository.remember] to check for a duplicate title (compared with safe
     * whitespace normalization in Kotlin, since SQL exact-match let whitespace-only
     * differences from repeated voice/chat input create duplicate rows). */
    @Query("SELECT * FROM cat_events WHERE isTask = 0 AND dateTime = :dateTime")
    suspend fun onSameDateTime(dateTime: Long): List<CatEvent>

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

    /** Dated, unfired schedule events whose reminder window has arrived — used by the periodic worker.
     * Kept for backward-compat reference only; [com.mspg.poicat.notify.ReminderWorker] no longer uses
     * this (it reads local_notification_state instead — see [due1DayLocally]) since reminded1Day is a
     * Firestore-shared field and doesn't reflect what THIS device has shown. */
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

    /**
     * 1日前通知の候補: [local_notification_state]をLEFT JOINし、この端末で
     * まだ通知していない(行が無い、またはnotified1Day=0)予定だけを対象にする —
     * reminded1Day(Firestore共有フィールド)は一切参照しない。[lowerBoundExclusive]
     * より後、[upperBoundInclusive]以下のdateTimeを持つ予定が対象
     * (呼び出し元[com.mspg.poicat.notify.ReminderWorker]が「予定まで2時間より長く
     * 24時間以内」を渡す)。
     */
    @Query(
        "SELECT cat_events.* FROM cat_events " +
            "LEFT JOIN local_notification_state ON cat_events.id = local_notification_state.catEventId " +
            "WHERE cat_events.isTask = 0 AND cat_events.dateTime IS NOT NULL " +
            "AND (local_notification_state.notified1Day IS NULL OR local_notification_state.notified1Day = 0) " +
            "AND cat_events.dateTime > :lowerBoundExclusive AND cat_events.dateTime <= :upperBoundInclusive",
    )
    suspend fun due1DayLocally(lowerBoundExclusive: Long, upperBoundInclusive: Long): List<CatEvent>

    /** [due1DayLocally]と対称。notified1Hourで判定し、呼び出し元は「予定まで2時間以内、
     * または予定を過ぎて3時間以内」を渡す。 */
    @Query(
        "SELECT cat_events.* FROM cat_events " +
            "LEFT JOIN local_notification_state ON cat_events.id = local_notification_state.catEventId " +
            "WHERE cat_events.isTask = 0 AND cat_events.dateTime IS NOT NULL " +
            "AND (local_notification_state.notified1Hour IS NULL OR local_notification_state.notified1Hour = 0) " +
            "AND cat_events.dateTime > :lowerBoundExclusive AND cat_events.dateTime <= :upperBoundInclusive",
    )
    suspend fun due1HourLocally(lowerBoundExclusive: Long, upperBoundInclusive: Long): List<CatEvent>
}
