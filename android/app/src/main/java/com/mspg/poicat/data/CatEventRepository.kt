package com.mspg.poicat.data

import android.content.Context
import com.mspg.poicat.room.RoomEventSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CatEventRepository(context: Context) {
    private val dao = AppDatabase.get(context).catEventDao()
    private val localNotificationDao = AppDatabase.get(context).localNotificationStateDao()
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
            findDuplicateByNormalizedTitle(title, dateTime)?.let { return it }
        }
        return insertAndSync(CatEvent(title = title, dateTime = dateTime))
    }

    /**
     * #POI-148: BBへ同じ予定を続けて話しかけると、音声認識/入力のわずかな表記ゆれ
     * (前後の空白・連続する空白など)のせいで、以前の完全一致比較(SQLの`title = :title`)
     * をすり抜け、同じ予定がもう1件作成されてしまう実機不具合が確認された。まず
     * [dateTime]が完全一致する既存行を全件取得し、タイトルは前後の空白を取り除き
     * 連続する空白を1つに詰めてから比較する — 既存行のtitle自体は一切書き換えない、
     * 比較のためだけの正規化。ここで一致が見つかった場合、[remember]はその既存行を
     * そのまま返す(新規insertしない)ため、呼び出し元の
     * [com.mspg.poicat.brain.CatBrain.rememberScheduleWithWorkJudgment]が続けて行う
     * setAlsoShowAsTaskも、この同じ既存行(canonicalな1件)に対してだけ作用する。
     */
    private suspend fun findDuplicateByNormalizedTitle(title: String, dateTime: Long): CatEvent? {
        val normalized = normalizeTitleForDuplicateCheck(title)
        return dao.onSameDateTime(dateTime).firstOrNull { normalizeTitleForDuplicateCheck(it.title) == normalized }
    }

    private fun normalizeTitleForDuplicateCheck(title: String): String =
        title.trim().replace(Regex("\\s+"), " ")

    /**
     * Full edit of an existing event; resets both shared reminder flags so a changed time can
     * notify again on other devices too (kept for backward compat with the old かっちゃん build —
     * see [markReminded1Day]/[markReminded1Hour]).
     *
     * #POI通知修正: [dateTime]が実際に変わった場合だけ、この端末のlocal_notification_state
     * (通知済み判定の正本)もクリアし、新しい日時に対して通知判定をやり直す。タイトルだけの
     * 変更ではクリアしない — 既存の通知済み状態を不必要にリセットしないため。比較は書き込み
     * 直前に再取得した現在のDB行を基準にする(roomEventId等の再取得と同じ理由 — [event]は
     * 呼び出し元の古いスナップショットの可能性があるため)。
     */
    suspend fun edit(event: CatEvent, title: String, dateTime: Long?) {
        val dateTimeChanged = dao.byId(event.id)?.dateTime != dateTime
        updateAndSync(event.copy(title = title, dateTime = dateTime, reminded1Day = false, reminded1Hour = false))
        if (dateTimeChanged) localNotificationDao.clear(event.id)
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

    /** タスク・予定どちらでも使える、担当ラベルだけを変更する汎用メソッド
     * (#142で仕事タスク向けに追加、#147で予定にも流用)。表示・同期先を
     * 絞るものではない — どの値(またはnull)でも、この行はみゆたん・かっちゃん
     * 双方の端末に同じ1件として表示・同期され続ける。 */
    suspend fun setAssignee(event: CatEvent, assignee: String?) {
        updateAndSync(event.copy(assignee = assignee))
    }

    /**
     * #148 フェーズ1/2: 予定(isTask=false)を、正本は予定のまま仕事/プラベ
     * タスク画面にも同時表示する(またはやめる)ための専用更新経路。
     * updateAndSync()を経由するため、#143の「呼び出し元の古いスナップショット
     * が持つroomEventIdでDB側の値を巻き戻さない」保護をそのまま受け継ぐ —
     * ここで独自にdao.update()を呼んだり[event]を丸ごと上書きしたりしない。
     * isTask自体は変更しない。
     *
     * フェーズ2: ONにする瞬間、categoryが未設定(null)なら[CatEvent.CATEGORY_WORK]
     * を自動設定する — 「仕事としてやる予定を仕事タスクにも表示する」という
     * 今回の目的に沿った、null時だけの一度きりの推定。既にWORK/PRIVATEの
     * どちらかに分類済みなら絶対に上書きしない。OFFにしても、この時に設定
     * されたcategoryを勝手に消したりnullへ戻したりはしない(既存の分類を
     * 破壊しない方針を優先)。categoryの判定は[event]の値ではなく、書き込み
     * 直前に再取得した現在のDB行の値を使う — roomEventIdと同じ理由で、
     * 呼び出し元の古いスナップショットに基づいて誤った推定をしないため。
     */
    suspend fun setAlsoShowAsTask(event: CatEvent, enabled: Boolean) {
        val currentCategory = dao.byId(event.id)?.category ?: event.category
        val category = if (enabled && currentCategory == null) CatEvent.CATEGORY_WORK else currentCategory
        updateAndSync(event.copy(alsoShowAsTask = enabled, category = category))
    }

    /**
     * #148 Maps-2A/Maps-2C: [CatEvent.locationText]だけを変更する専用更新経路。
     * [setAssignee]/[setAlsoShowAsTask]と同じ形で[updateAndSync]を経由する
     * だけの薄いラッパーで、#143の「呼び出し元の古いスナップショットが持つ
     * roomEventIdでDB側の値を巻き戻さない」保護をそのまま受け継ぐ — ここで
     * 独自にdao.update()を呼んだり別の保存経路を新設したりしない。
     *
     * Maps-2C: 書き込み直前に`dao.byId(event.id)`で現在のDB行を再取得し、
     * [locationText]以外の全フィールドは["現在のDB行"の値]をそのまま使う
     * ([event]の値ではなく) — [setAlsoShowAsTask]がcategoryについて行っている
     * のと同じ理由。例えば「予定登録直後、WORK判定でsetAlsoShowAsTask(true)
     * が呼ばれた直後」に、その呼び出し元が受け取った(まだalsoShowAsTask=false
     * のままの)古い[CatEvent]スナップショットで[setLocation]を呼ぶと、再取得
     * せずに[event]をそのまま使った場合はalsoShowAsTask=trueを誤ってfalseへ
     * 巻き戻してしまう。再取得によりこれを防ぐ。
     */
    suspend fun setLocation(event: CatEvent, locationText: String?) {
        val current = dao.byId(event.id) ?: event
        updateAndSync(current.copy(locationText = locationText))
    }

    suspend fun dueFor1DayReminder(windowStart: Long, windowEnd: Long) =
        dao.dueFor1DayReminder(windowStart, windowEnd)

    suspend fun dueFor1HourReminder(windowStart: Long, windowEnd: Long) =
        dao.dueFor1HourReminder(windowStart, windowEnd)

    /** #POI通知修正: この端末自身の通知可否判定に使う、local_notification_state
     * ベースの候補取得。[com.mspg.poicat.notify.ReminderWorker]が使う。 */
    suspend fun due1DayLocally(lowerBoundExclusive: Long, upperBoundInclusive: Long) =
        dao.due1DayLocally(lowerBoundExclusive, upperBoundInclusive)

    suspend fun due1HourLocally(lowerBoundExclusive: Long, upperBoundInclusive: Long) =
        dao.due1HourLocally(lowerBoundExclusive, upperBoundInclusive)

    /** この端末で1日前/1時間前通知を実際に表示した直後に呼ぶ、端末ローカルのみの記録
     * (Firestoreへは送らない、パートナー端末の通知可否に一切影響しない)。 */
    suspend fun markNotified1DayLocally(eventId: Long) = localNotificationDao.markNotified1Day(eventId)

    suspend fun markNotified1HourLocally(eventId: Long) = localNotificationDao.markNotified1Hour(eventId)

    /** 旧かっちゃん版との後方互換性のためだけに維持する、Firestore共有フィールドへの
     * 書き込み。新版自身の通知可否判定にはもう使わない([due1DayLocally]/
     * [markNotified1DayLocally]参照)。 */
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
