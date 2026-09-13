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

    /**
     * #143: 呼び出し元(Compose/UI側)が渡す[event]は、直前に読み込んだ古い
     * スナップショットの可能性がある。特に[CatEvent.roomEventId]は、この行が
     * 作成された直後、非同期のFirestore初回プッシュがまだ完了していない間は
     * nullのままUI側に渡っていることがあり、そのままdao.update()すると、
     * その後に初回プッシュが完了して既にDB側へ書き込まれていたroomEventIdを
     * nullへ巻き戻してしまう恐れがある。巻き戻ると、このupdateAndSync自身が
     * 起動する後続のpushUpsertがroomEventId==nullと誤認し、同じ論理タスクに
     * 対して新しいUUIDのFirestoreドキュメントを別途作成してしまう(実機で
     * 確認された重複タスクの一因)。
     *
     * そこで書き込み直前に同じidの現在のDB行を再取得し、DB側に既に
     * roomEventIdがあればそちらを優先する。呼び出し元が変更したかった
     * フィールド(title/completed/assignee等)自体は[event]の値をそのまま使う
     * — ここで上書きするのはroomEventIdだけ。担当変更・完了変更・その他の
     * 編集など、updateAndSyncを経由する全ての操作に共通で効く。
     */
    private suspend fun updateAndSync(event: CatEvent) {
        val currentRoomEventId = dao.byId(event.id)?.roomEventId ?: event.roomEventId
        val toUpdate = event.copy(updatedAt = System.currentTimeMillis(), roomEventId = currentRoomEventId)
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

    /** 仕事タスクの担当ラベルだけを変更する。表示・同期先を絞るものではない —
     * どの値(またはnull)でも、このタスクはみゆたん・かっちゃん双方の端末に
     * 同じ1件として表示・同期され続ける。 */
    suspend fun setAssignee(task: CatEvent, assignee: String?) {
        updateAndSync(task.copy(assignee = assignee))
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

    /**
     * ルーム参加時の既存データバックフィル専用。まだ一度もFirestoreへ送っていない
     * (roomEventIdがnullの)行だけを対象に、通常の新規保存と全く同じpushUpsert経路
     * (syncScope上のfire-and-forget、失敗してもローカルには一切影響しない)で送る。
     * 送信が成功した行だけmarkRoomEventIdでroomEventIdが埋まるため、通信が途中で
     * 失敗しても、次にこの関数が呼ばれたときは「まだnullのまま残っている行」だけが
     * 自然に再試行される(グローバルな完了フラグは持たない設計)。
     *
     * このバックフィルはローカル→リモートへの追加送信のみで、ここでdao.delete等を
     * 呼ぶことは無い — 既存のローカル行を消したり上書きしたりしない。
     *
     * 呼び出し元は[com.mspg.poicat.room.RoomBackfill]。
     */
    suspend fun pushUnsyncedToRoom() {
        dao.unsyncedRoomEvents().forEach { event ->
            syncScope.launch {
                RoomEventSync.pushUpsert(appContext, event) { roomEventId -> markRoomEventId(event.id, roomEventId) }
            }
        }
    }
}
