package com.mspg.poicat.room

import android.content.Context
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.mspg.poicat.data.AppDatabase
import com.mspg.poicat.data.CatEvent
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    )

    /**
     * ローカルで新規追加/更新された1件をFirestoreへ反映する。[event]がまだ
     * roomEventIdを持たない（初回プッシュ）場合は新規にUUIDを割り当て、書き込みが
     * 成功した後にだけ[onRoomEventIdAssigned]で呼び出し元へ知らせる（＝ローカルの
     * 行へ保存するのは呼び出し元＝CatEventRepositoryの責務。書き込み失敗時は
     * roomEventIdを持たないままなので、次のpush機会に再度新規プッシュとして扱われる）。
     *
     * [SetOptions.merge]を使い、[toMap]に載っていないフィールドはFirestore側の
     * 既存の値をそのまま残す（＝完全上書きしない）。これにより、将来この端末より
     * 新しいバージョンが追加した未知のフィールド（例: 担当者）を、まだ更新していない
     * 端末がこのイベントを編集・完了操作しても消してしまわないようにする。[toMap]が
     * 送る各フィールド自体は常にこのイベントの最新値で上書きされる点は従来と変わらない。
     */
    suspend fun pushUpsert(context: Context, event: CatEvent, onRoomEventIdAssigned: suspend (String) -> Unit) {
        runCatching {
            val roomId = RoomStore(context).roomId ?: return
            val roomEventId = event.roomEventId ?: UUID.randomUUID().toString()
            eventsRef(roomId).document(roomEventId).set(toMap(event), SetOptions.merge()).await()
            if (event.roomEventId == null) onRoomEventIdAssigned(roomEventId)
        }
    }

    suspend fun pushDelete(context: Context, event: CatEvent) {
        runCatching {
            val roomId = RoomStore(context).roomId ?: return
            val roomEventId = event.roomEventId ?: return
            eventsRef(roomId).document(roomEventId).delete().await()
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
        listenerRegistration = eventsRef(roomId).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            // このスナップショットが「この端末自身がまだFirestoreへ書き込み中/サーバー
            // 未確認の、自分自身の変更」を反映しただけのローカルエコーである場合は
            // 無視する。理由: pushUpsertの書き込みが完了する前にこのローカルエコーが
            // 先に届くと、その時点ではまだmarkRoomEventIdによるroomEventIdの記録が
            // 終わっておらず、dao.byRoomEventIdが該当ローカル行を見つけられないため
            // 「新規」として別行をinsertしてしまい、同じ内容が2行登録される(実機で
            // 確認された二重登録の直接原因)。hasPendingWrites()==trueは「まだサーバー
            // 未確認の、この端末自身の変更」だけを意味するため、他端末からの変更を
            // 取りこぼすことはない — サーバー確認後(hasPendingWrites==false)に改めて
            // 届いた時点では、markRoomEventIdは既に完了しているはずなので、正しく
            // 「既存行の更新」として扱われる。
            if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
            for (change in snapshot.documentChanges) {
                val roomEventId = change.document.id
                scope.launch {
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
                        val merged = CatEvent(
                            id = local?.id ?: 0,
                            title = data["title"] as? String ?: "",
                            dateTime = (data["dateTime"] as? Number)?.toLong(),
                            createdAt = (data["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                            reminded1Day = data["reminded1Day"] as? Boolean ?: false,
                            reminded1Hour = data["reminded1Hour"] as? Boolean ?: false,
                            isTask = data["isTask"] as? Boolean ?: false,
                            completed = data["completed"] as? Boolean ?: false,
                            category = data["category"] as? String,
                            roomEventId = roomEventId,
                            updatedAt = remoteUpdatedAt,
                        )
                        if (local == null) dao.insert(merged) else dao.update(merged)
                    }
                }
            }
        }
    }
}
