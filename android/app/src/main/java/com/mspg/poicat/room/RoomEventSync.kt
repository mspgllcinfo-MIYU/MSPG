package com.mspg.poicat.room

import android.content.Context
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.mspg.poicat.data.AppDatabase
import com.mspg.poicat.data.CatEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * スケジュール/メモ/タスク（[CatEvent]）の4桁PINルーム共有。
 *
 * ルーム未参加（[RoomStore.roomId] == null）の間は、push/pullどちらも即座に何もしない
 * — 既存のローカル専用動作には一切影響しない（Run #104/#105のPhotoDriveSync/
 * FileDriveSyncと同じ「ローカルが常に主、共有は後追いのbest-effort」という考え方）。
 *
 * push（[pushUpsert]/[pushDelete]）はCatEventRepositoryの各書き込みメソッドから
 * fire-and-forgetで呼ばれる。pull（[startListening]）はアプリ起動時に一度だけ登録される
 * Firestoreのリアルタイムリスナーで、受け取った変更を直接dao経由でローカルへ書き込む
 * ため、pushの経路を再度通らず、push↔pullが無限に往復することはない。
 *
 * 競合解決: 各行の[CatEvent.updatedAt]を比較するだけの単純な「新しい方が勝つ
 * (last-write-wins)」方式。同時編集が起きても片方の変更が消えるだけで、アプリが
 * クラッシュしたり無限ループしたりすることはない。
 */
object RoomEventSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerRegistration: ListenerRegistration? = null

    /**
     * #143: [startListening]が受け取るDocumentChangeごとの「ローカルDB反映」
     * (dao.byRoomEventIdでの存在確認 → 無ければinsert / あればupdate)を、
     * 複数のDocumentChangeについて同時に走らせないための排他ロック。
     *
     * Dispatchers.IOはマルチスレッドで、[scope].launchはDocumentChangeごと
     * (あるいはスナップショット配信ごと)に新しいコルーチンを起動するため、
     * このロックが無いと「まだローカルに存在しない」という判定が複数の
     * コルーチンで同時に真になり得る — 例えば「タスク作成」の直後に「担当変更」
     * のような編集を行うと、作成分と更新分がほぼ同時にFirestoreスナップショット
     * として届き、片方のdao.insert()がまだ完了していない間にもう片方の
     * dao.byRoomEventIdも同じくnullを返してしまい、同じ論理タスクが2行として
     * insertされる(実機で確認された重複タスクの一因)。
     *
     * Dispatchers.IO.limitedParallelism(1)のような「ディスパッチャ単位」の
     * 制限だけでは不十分 — Roomのsuspend DAO呼び出しは内部で
     * db.queryExecutor等の別ディスパッチャへ一時的にwithContextで移るため、
     * その間だけ元のディスパッチャの「枠」が空き、別のコルーチンがその隙に
     * 割り込んで動き出せてしまう。[Mutex]はディスパッチャをまたいだ
     * suspend区間全体をロックできるため、この隙間を作らない。
     */
    private val applyMutex = Mutex()

    /**
     * #POI同期race修正: 同一ローカル行(CatEvent.id)に対して短時間に複数の[pushUpsert]が
     * 呼ばれる場合(例: CatBrain.rememberScheduleWithWorkJudgmentが
     * repository.remember()の直後にrepository.setAlsoShowAsTaskを呼ぶ経路など、
     * BBの通常利用で毎回発生する)、各呼び出しが「roomEventIdがまだnullの古い
     * スナップショット」だけを見て、互いの完了を待たずにそれぞれ新しいUUIDを
     * 発行してしまい、Firestore上に同じ論理イベントに対する複数のdocumentが
     * 作られてしまう実機不具合が確認された(先に完了した方のdocumentは
     * どのローカル行からも参照されない孤児となり、次回アプリ再起動時の
     * listener初回スナップショットでローカルの重複行として現れる)。
     *
     * [withEventLock]で同一event.idの[pushUpsert]/[pushDelete]全体(最新DB状態の確認→
     * UUID発行判断→Firestore書き込み→[onRoomEventIdAssigned]完了)を直列化し、後から
     * 実行される呼び出しが必ず先行する呼び出しの結果(ローカルDBへ書き込まれた
     * roomEventId)を見てからUUID発行の有無を判断できるようにする。既存の[applyMutex]
     * (Firestore受信/pull側)とは別の排他区間 — pull処理まで不要にブロックしない。
     *
     * 単一のグローバルMutex 1個で全event.idのpushを直列化する初期実装は採用しない —
     * オフライン時、予定Aの`.await()`がサーバー確認まで(オンライン復帰まで)完了しない
     * Firestoreの仕様上、無関係な予定B/C/Dのpushまで長時間ブロックしてしまうため。
     * [PushLock.refCount]で「今、このevent.idのロックを使いたいコルーチンが何人いるか」
     * を数え、0になった時点で[pushLocks]からエントリを取り除くことで、CatEvent数に
     * 応じてMapが無限に増え続けることも避けている。
     *
     * [pushLocks]の読み書きは必ず[ConcurrentHashMap.compute]経由で行う — 同一キーに対する
     * compute呼び出し全体はConcurrentHashMapがアトミックに保証するため、「参照数0で
     * エントリを消す」処理と「参照数を増やして既存ロックを再利用する」処理が別の
     * コルーチンで同時に走っても、消したはずのロックがまだ待機中のコルーチンから
     * 見えている、または消えた直後に同じevent.id用の別のロックが新たに作られて
     * 待機側と食い違う、といった競合状態は起きない(あるコルーチンの参照が有効な間、
     * そのcomputeが完了するまで他のcomputeは同じキーに対してブロックされるため)。
     */
    private class PushLock {
        val mutex = Mutex()
        var refCount = 0
    }

    private val pushLocks = ConcurrentHashMap<Long, PushLock>()

    private fun acquireEventLock(eventId: Long): PushLock {
        lateinit var acquired: PushLock
        pushLocks.compute(eventId) { _, existing ->
            val lock = existing ?: PushLock()
            lock.refCount++
            acquired = lock
            lock
        }
        return acquired
    }

    private fun releaseEventLock(eventId: Long) {
        pushLocks.compute(eventId) { _, existing ->
            val lock = existing ?: return@compute null
            lock.refCount--
            if (lock.refCount <= 0) null else lock
        }
    }

    /** 同一[eventId]についての[block]呼び出しだけを直列化する(別eventIdなら並行実行可能)。
     * 例外・キャンセル時も[releaseEventLock]が必ず呼ばれるため、ロック管理状態が
     * 壊れることはない。 */
    private suspend inline fun <T> withEventLock(eventId: Long, block: () -> T): T {
        val lock = acquireEventLock(eventId)
        try {
            return lock.mutex.withLock { block() }
        } finally {
            releaseEventLock(eventId)
        }
    }

    private fun db() = FirebaseFirestore.getInstance()
    private fun eventsRef(roomId: String) = db().collection("rooms").document(roomId).collection("events")

    private fun toMap(event: CatEvent): Map<String, Any?> = mapOf(
        "title" to event.title,
        "dateTime" to event.dateTime,
        "createdAt" to event.createdAt,
        "reminded1Day" to event.reminded1Day,
        "reminded1Hour" to event.reminded1Hour,
        "isTask" to event.isTask,
        "completed" to event.completed,
        "category" to event.category,
        "updatedAt" to event.updatedAt,
        "assignee" to event.assignee,
        "alsoShowAsTask" to event.alsoShowAsTask,
        "locationText" to event.locationText,
    )

    /**
     * ローカルで新規追加/更新された1件をFirestoreへ反映する。[withEventLock]で同一
     * event.idについて直列化されているため、UUID発行判断の直前に必ずローカルDBの
     * 最新状態(直前の[pushUpsert]呼び出しが書き込んだ可能性のあるroomEventIdを含む)を
     * 再確認できる — 呼び出し元が渡した[event]のroomEventIdがnullでも、DB側に既に
     * (先行する呼び出しによって)roomEventIdが記録されていればそれをそのまま再利用し、
     * 新規UUIDは発行しない。DBにも無い場合だけ、新規にUUIDを割り当てる。書き込みが
     * 成功し、かつ今回新たにUUIDを発行した場合にだけ[onRoomEventIdAssigned]で呼び出し元
     * へ知らせる（＝ローカルの行へ保存するのは呼び出し元＝CatEventRepositoryの責務。
     * 書き込み失敗時はroomEventIdを持たないままなので、次のpush機会に再度新規プッシュ
     * として扱われる）。別のevent.idへの[pushUpsert]/[pushDelete]はこの呼び出しを
     * 待たずに並行して進む。
     *
     * [SetOptions.merge]を使い、[toMap]に載っていないフィールドはFirestore側の
     * 既存の値をそのまま残す（＝完全上書きしない）。これにより、将来この端末より
     * 新しいバージョンが追加した未知のフィールド（例: 担当者）を、まだ更新していない
     * 端末がこのイベントを編集・完了操作しても消してしまわないようにする。[toMap]が
     * 送る各フィールド自体は常にこのイベントの最新値で上書きされる点は従来と変わらない。
     */
    suspend fun pushUpsert(context: Context, event: CatEvent, onRoomEventIdAssigned: suspend (String) -> Unit) {
        withEventLock(event.id) {
            runCatching {
                val roomId = RoomStore(context).roomId ?: return
                val dao = AppDatabase.get(context).catEventDao()
                val existingRoomEventId = dao.byId(event.id)?.roomEventId ?: event.roomEventId
                val roomEventId = existingRoomEventId ?: UUID.randomUUID().toString()
                eventsRef(roomId).document(roomEventId).set(toMap(event), SetOptions.merge()).await()
                if (existingRoomEventId == null) onRoomEventIdAssigned(roomEventId)
            }
        }
    }

    /**
     * #POI同期race修正: [pushUpsert]と同じ[withEventLock](event.id)で直列化する —
     * 同一event.idに対する編集push(pushUpsert)と削除push(pushDelete)が別々の
     * タイミングでFirestoreへ書き込み/削除を送ると、削除が先に完了した直後に、既に
     * 直前から進行中だった編集pushの書き込みが後から到着して削除済みdocumentを
     * 復活させてしまう、という順序の競合を防ぐ。既存の削除仕様(論理削除ではなく
     * Firestore document自体をdeleteする方式)自体は変更していない。
     *
     * 注意(既知の制限、今回のスコープ外): ここで直列化できるのは「roomEventIdが
     * 既に確定している既存イベント」への編集/削除の競合のみ。新規作成の初回push
     * (roomEventIdがまだnull)が完了するより前に、その同じローカル行が削除された
     * 場合、削除時点のローカル行は既に無くなっているため[pushDelete]は
     * `event.roomEventId ?: return`で何もできず、初回pushが後から発行した
     * roomEventIdをFirestore上から消す手段が無い(既存の、この修正以前からある
     * ギャップ)。これを解消するには[CatEventRepository]側の削除経路自体の見直しが
     * 必要で、今回の`RoomEventSync.kt`単体の修正範囲を超えるため対応していない。
     */
    suspend fun pushDelete(context: Context, event: CatEvent) {
        withEventLock(event.id) {
            runCatching {
                val roomId = RoomStore(context).roomId ?: return
                val roomEventId = event.roomEventId ?: return
                eventsRef(roomId).document(roomEventId).delete().await()
            }
        }
    }

    /** ルーム参加済みならFirestore側の変更を監視し、ローカルRoom DBへ反映する。
     * アプリプロセスにつき一度だけ登録すればよい（[MainActivity]から呼ばれる）。
     * ルーム未参加なら何もしない — 後からルームに参加した場合は次回起動時に有効になる
     * （V1の制約として許容する。ルーム参加操作自体からも明示的に呼び直す）。 */
    fun startListening(context: Context) {
        if (listenerRegistration != null) return
        val roomId = RoomStore(context).roomId ?: return
        val dao = AppDatabase.get(context).catEventDao()
        val localNotificationDao = AppDatabase.get(context).localNotificationStateDao()
        listenerRegistration = eventsRef(roomId).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            for (change in snapshot.documentChanges) {
                // #POI同期修正②: このdocument自身が「まだFirestoreへ書き込み中/サーバー
                // 未確認の、自分自身の変更」を反映しただけのローカルエコーである場合は
                // 無視する。理由: pushUpsertの書き込みが完了する前にこのローカルエコーが
                // 先に届くと、その時点ではまだmarkRoomEventIdによるroomEventIdの記録が
                // 終わっておらず、dao.byRoomEventIdが該当ローカル行を見つけられないため
                // 「新規」として別行をinsertしてしまい、同じ内容が2行登録される(実機で
                // 確認された二重登録の直接原因)。change.document.metadata.hasPendingWrites()
                // はdocumentごとに個別の値(Firestore SDK自体がdocument単位で保持している)
                // であり、以前使っていたsnapshot.metadata.hasPendingWrites()(スナップ
                // ショット全体で1つの値 — 含まれるいずれかのdocumentがpendingなら全体が
                // trueになる)より粒度が細かい。これにより、この端末の別の予定がpending中
                // でも、同じスナップショットに乗ってきた相手端末発の変更(pendingではない)
                // まで一緒に捨ててしまうことがなくなる。hasPendingWrites()==trueは「まだ
                // サーバー未確認の、この端末自身の変更」だけを意味するため、他端末からの
                // 変更を取りこぼすことはない — サーバー確認後(hasPendingWrites==false)に
                // 改めて届いた時点では、markRoomEventIdは既に完了しているはずなので、
                // 正しく「既存行の更新」として扱われる。
                if (change.document.metadata.hasPendingWrites()) continue
                val roomEventId = change.document.id
                scope.launch {
                    // #143: このDocumentChangeの反映が完了するまで、他のDocumentChangeの
                    // 反映処理を待たせる — 1件ずつ直列実行にする。既存の判定・書き込み
                    // ロジック自体は一切変更していない。
                    applyMutex.withLock {
                        runCatching {
                            if (change.type == DocumentChange.Type.REMOVED) {
                                dao.byRoomEventId(roomEventId)?.let { dao.delete(it) }
                                return@runCatching
                            }
                            val data = change.document.data ?: return@runCatching
                            val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
                            val local = dao.byRoomEventId(roomEventId)
                            if (local != null && remoteUpdatedAt <= local.updatedAt) {
                                // ローカルの方が同じか新しい — 何もしない（こちらの内容は
                                // 次のpushでFirestoreへ反映される）。
                                return@runCatching
                            }
                            val newDateTime = (data["dateTime"] as? Number)?.toLong()
                            val merged = CatEvent(
                                id = local?.id ?: 0,
                                title = data["title"] as? String ?: "",
                                dateTime = newDateTime,
                                createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                                reminded1Day = data["reminded1Day"] as? Boolean ?: false,
                                reminded1Hour = data["reminded1Hour"] as? Boolean ?: false,
                                isTask = data["isTask"] as? Boolean ?: false,
                                completed = data["completed"] as? Boolean ?: false,
                                category = data["category"] as? String,
                                roomEventId = roomEventId,
                                updatedAt = remoteUpdatedAt,
                                // 旧版端末(assigneeをまだ知らないバージョン)が書いたドキュメントには
                                // このキー自体が無い — その場合はnull(未設定)として扱う。
                                assignee = data["assignee"] as? String,
                                // #148フェーズ1: 同じく旧版端末(alsoShowAsTaskをまだ知らない
                                // バージョン)が書いたドキュメントにはこのキー自体が無い —
                                // その場合は必ずfalse(通常予定のまま)として扱う。
                                alsoShowAsTask = data["alsoShowAsTask"] as? Boolean ?: false,
                                // #148 Maps-2A: 同じく旧版端末(locationTextをまだ知らない
                                // バージョン)が書いたドキュメントにはこのキー自体が無い —
                                // その場合はnull(「場所なし」)として扱う。
                                locationText = data["locationText"] as? String,
                            )
                            if (local == null) {
                                dao.insert(merged)
                            } else {
                                // #POI通知修正: 受信した内容で日時が実際に変わっていた場合
                                // だけ、この端末のlocal_notification_state(通知済み判定の
                                // 正本、Firestoreには一切同期しない)をクリアし、新しい日時
                                // に対して通知判定をやり直す。reminded1Day/reminded1Hourは
                                // 上のmergedで(旧版互換のため)そのまま反映するだけで、
                                // local_notification_stateへは絶対に書き込まない — 相手端末
                                // が既に通知済みという情報を、この端末の通知抑制に使わない
                                // ため。タイトル等、日時以外だけの変更ではクリアしない。
                                val dateTimeChanged = local.dateTime != newDateTime
                                dao.update(merged)
                                if (dateTimeChanged) localNotificationDao.clear(merged.id)
                            }
                        }
                    }
                }
            }
        }
    }
}
