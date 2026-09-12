package com.mspg.poicat.room

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.mspg.poicat.data.FileDatabase
import com.mspg.poicat.data.PhotoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * アルバム/ファイルの「POI上の削除状態」だけを夫婦2台で共有する、Drive原本には一切
 * 触れない論理削除(tombstone)の同期。
 *
 * 【背景・設計方針(ユーザー指示)】以前はPOIで削除した写真/ファイルのGoogle Drive
 * 原本も一緒に削除していたが、方針を変更した — Drive側は常に原本の保管庫として扱い、
 * 物理削除は二度と行わない。一方で「POI上では削除済み」という状態そのものは2台で
 * 共有したい(片方が消したものが、Driveの再取得でもう片方に復活してこないように)。
 *
 * 実データ([Photo]/[StoredFile]の行そのもの)は物理削除しない — [Photo.deletedAt]/
 * [StoredFile.deletedAt]を立てるだけの論理削除(ローカルの一覧系クエリはこれを
 * フィルタする)。この「削除済みdriveFileIdの一覧」を`rooms/{roomId}/driveTombstones`
 * という軽量なFirestoreコレクションに記録する。ドキュメントの中身は削除時刻のみ —
 * 写真/ファイルの実データは一切含まない。
 *
 * - [pushTombstone]: この端末で「×」削除されたdriveFileIdを1件登録する
 *   (fire-and-forget、失敗してもローカルの削除状態自体には影響しない)。
 * - [startListening]: パートナー端末が削除したdriveFileIdを受信し、こちらのローカル
 *   Room DB(Photo/StoredFile)に同じdriveFileIdの行があれば、その行もdeletedAtを立てて
 *   非表示にする(ローカルファイル・Drive原本のどちらも物理削除しない)。
 *   [RoomEventSync.startListening]と同じ理由でhasPendingWrites()==trueの自端末エコーは
 *   無視する(実データ二重登録バグの修正と同じ対策 — ここでも同じ罠を避けておく)。
 * - [isTombstoned]: [RoomCatalogSync]がDriveフォルダから新規ダウンロードする前に確認
 *   する — 既に削除済みとして記録されているdriveFileIdは、まだこの端末にローカル行が
 *   無くても再ダウンロードしない。
 *
 * ルーム未参加なら全関数が即noop。
 */
object RoomDriveTombstoneSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerRegistration: ListenerRegistration? = null

    private fun db() = FirebaseFirestore.getInstance()
    private fun tombstonesRef(roomId: String) =
        db().collection("rooms").document(roomId).collection("driveTombstones")

    /** [driveFileId]を削除済みとして記録する。ローカルのdeletedAt付与は呼び出し元
     * ([com.mspg.poicat.data.PhotoRepository.softDelete]等)の責務 — ここではFirestoreへの
     * 反映のみを行う。 */
    suspend fun pushTombstone(context: Context, driveFileId: String) {
        runCatching {
            val roomId = RoomStore(context).roomId ?: return
            tombstonesRef(roomId).document(driveFileId)
                .set(mapOf("deletedAt" to FieldValue.serverTimestamp()))
                .await()
        }
    }

    /** [driveFileId]が既に(自端末またはパートナー端末により)削除済みとして記録されて
     * いるか。取得に失敗した場合はfalse(=削除されていない扱い)を返す — 判定できない
     * ことを理由にDriveからの正常な取り込みを止めないため。 */
    suspend fun isTombstoned(context: Context, driveFileId: String): Boolean = runCatching {
        val roomId = RoomStore(context).roomId ?: return false
        tombstonesRef(roomId).document(driveFileId).get().await().exists()
    }.getOrDefault(false)

    fun startListening(context: Context) {
        if (listenerRegistration != null) return
        val roomId = RoomStore(context).roomId ?: return
        val appContext = context.applicationContext
        listenerRegistration = tombstonesRef(roomId).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            // RoomEventSync.startListeningと同じ対策: 自端末がまだサーバー未確認で
            // 書き込み中のtombstoneまで即座に受信してしまうのを無視する。
            if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
            for (change in snapshot.documentChanges) {
                val driveFileId = change.document.id
                scope.launch {
                    runCatching { applyTombstoneLocally(appContext, driveFileId) }
                }
            }
        }
    }

    /** パートナー端末発のtombstoneを、こちらのローカル行(存在すれば)へも反映する —
     * ローカルファイル・Drive原本のどちらも物理削除しない、deletedAtを立てるだけ。 */
    private suspend fun applyTombstoneLocally(context: Context, driveFileId: String) {
        val now = System.currentTimeMillis()

        val photoDao = PhotoDatabase.get(context).photoDao()
        photoDao.byDriveFileId(driveFileId)?.let { photo ->
            if (photo.deletedAt == null) photoDao.update(photo.copy(deletedAt = now))
        }

        val fileDao = FileDatabase.get(context).storedFileDao()
        fileDao.byDriveFileId(driveFileId)?.let { file ->
            if (file.deletedAt == null) fileDao.update(file.copy(deletedAt = now))
        }
    }
}
