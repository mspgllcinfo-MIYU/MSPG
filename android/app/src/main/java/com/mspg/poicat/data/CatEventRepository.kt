package com.mspg.poicat.data

import android.content.Context
import com.mspg.poicat.room.RoomEventSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CatEventRepository(context: Context) {
    private val dao = AppDatabase.get(context).catEventDao()
    private val appContext = context.applicationContext

    // ルーム共有(4桁PIN)用のfire-and-forgetなFirestoreプッシュだけに使う — ここで何が
    // 起きても(ルーム未参加/オフライン/書き込み失敗)、呼び出し元の戻り値やローカル
    // 保存には一切影響しない。PhotoDriveSync/FileDriveSyncと同じ「ローカルが主、
    // 共有は後追い」という設計をCatEventにも適用したもの。
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun insertAndSync(event: CatEvent): CatEvent {
        val toInsert = event.copy(updatedAt = System.currentTimeMillis())
        val id = dao.insert(toInsert)
        val saved = toInsert.copy(id = id)
        syncScope.launch { RoomEventSync.pushUpsert(appContext, saved) { roomEventId -> markRoomEventId(id, roomEventId) } }
        return saved
    }

    private suspend fun updateAndSync(event: CatEvent) {
        val toUpdate = event.copy(updatedAt = System.currentTimeMillis())
        dao.update(toUpdate)
        syncScope.launch { RoomEventSync.pushUpsert(appContext, toUpdate) { roomEventId -> markRoomEventId(toUpdate.id, roomEventId) } }
    }

    private suspend fun deleteAndSync(event: CatEvent) {
        dao.delete(event)
        syncScope.launch { RoomEventSync.pushDelete(appContext, event) }
    }

    /** Firestoreへの初回プッシュ成功後に割り当てられたroomEventIdを記録するだけの、
     * 同期を再度トリガーしない更新（[RoomEventSync.startListening]からの受信と対称）。 */
    private suspend fun markRoomEventId(eventId: Long, roomEventId: String) {
        val current = dao.byId(eventId) ?: return
        if (current.roomEventId == roomEventId) return
        dao.update(current.copy(roomEventId = roomEventId))
    }

    /** Inserts a new dated/date-less item, unless a dated one with the same title+time already exists. */
    suspend fun remember(title: String, dateTime: Long?): CatEvent {
        if (dateTime != null) {
            dao.findDuplicate(title, dateTime)?.let { return it }
        }
        return insertAndSync(CatEvent(title = title, dateTime = dateTime))
    }

    /** Full edit of an existing event; resets both reminder flags so a changed time can notify again. */
    suspend fun edit(event: CatEvent, title: String, dateTime: Long?) {
        updateAndSync(event.copy(title = title, dateTime = dateTime, reminded1Day = false, reminded1Hour = false))
    }

    suspend fun delete(event: CatEvent) = deleteAndSync(event)

    suspend fun upcoming(from: Long = System.currentTimeMillis()) = dao.upcoming(from)

    suspend fun upcomingMatching(keyword: String, from: Long = System.currentTimeMillis()) =
        dao.upcomingMatching(keyword, from)

    suspend fun onDay(startOfDay: Long, endOfDay: Long) = dao.onDay(startOfDay, endOfDay)

    suspend fun between(start: Long, end: Long) = dao.between(start, end)

    suspend fun memos() = dao.memos()

    suspend fun memosMatching(keyword: String) = dao.memosMatching(keyword)

    suspend fun allMatching(keyword: String) = dao.allMatching(keyword)

    suspend fun addTask(title: String, dueDateTime: Long?, category: String? = null): CatEvent =
        insertAndSync(CatEvent(title = title, dateTime = dueDateTime, isTask = true, category = category))

    suspend fun tasks() = dao.tasks()

    suspend fun incompleteTasks() = dao.incompleteTasks()

    suspend fun incompleteTasksDueOrUndated(start: Long, end: Long) = dao.incompleteTasksDueOrUndated(start, end)

    suspend fun incompleteTasksDueBy(end: Long) = dao.incompleteTasksDueBy(end)

    suspend fun incompleteTasksMatching(keyword: String) = dao.incompleteTasksMatching(keyword)

    suspend fun setTaskCompleted(task: CatEvent, completed: Boolean) {
        updateAndSync(task.copy(completed = completed))
    }

    suspend fun dueFor1DayReminder(windowStart: Long, windowEnd: Long) =
        dao.dueFor1DayReminder(windowStart, windowEnd)

    suspend fun dueFor1HourReminder(windowStart: Long, windowEnd: Long) =
        dao.dueFor1HourReminder(windowStart, windowEnd)

    suspend fun markReminded1Day(event: CatEvent) {
        updateAndSync(event.copy(reminded1Day = true))
    }

    suspend fun markReminded1Hour(event: CatEvent) {
        updateAndSync(event.copy(reminded1Hour = true))
    }
}
