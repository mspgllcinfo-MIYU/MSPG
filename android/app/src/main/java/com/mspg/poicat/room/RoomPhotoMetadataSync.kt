package com.mspg.poicat.room

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.mspg.poicat.data.PhotoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * アルバムのキャプション/アルバム名/カレンダー日付編集を夫婦2台で同期する。
 *
 * 写真の実体(画像バイト)は既存の[com.mspg.poicat.drive.PhotoDriveSync]/
 * [com.mspg.poicat.drive.RoomCatalogSync]がGoogle Drive経由で扱う仕組みのままで、
 * ここでは一切触れない・再アップロードもしない — 同期するのは軽量なメタデータ
 * (文字列/日付)のみで、`rooms/{roomId}/photoMetadata/{driveFileId}`という
 * Firestoreドキュメントに記録する。ローカルの[com.mspg.poicat.data.Photo]の主キー
 * (id)は端末ごとに別々の値になるため使えず、[com.mspg.poicat.data.Photo.driveFileId]
 * (両端末が共有する唯一の共通キー)でドキュメントを特定する。まだDriveへ
 * アップロードされていない(driveFileIdが無い)写真の編集は同期対象外— アップロード
 * 完了後の編集から同期され始める。
 *
 * 競合解決は[RoomEventSync]と同じ、[com.mspg.poicat.data.Photo.metadataUpdatedAt]の
 * 比較によるlast-write-wins。[RoomEventSync]/[RoomDriveTombstoneSync]と同じ理由で
 * hasPendingWrites()==trueの自端末エコーは無視する(実データ二重登録バグの修正と
 * 同じ対策)。
 *
 * ルーム未参加なら全関数が即noop。
 */
object RoomPhotoMetadataSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerRegistration: ListenerRegistration? = null

    private fun db() = FirebaseFirestore.getInstance()
    private fun metadataRef(roomId: String) =
        db().collection("rooms").document(roomId).collection("photoMetadata")

    /** ローカル編集をFirestoreへ反映する。ローカルDBへの反映は呼び出し元
     * ([com.mspg.poicat.data.PhotoRepository.updateDetails]/[com.mspg.poicat.data.PhotoRepository.markDriveSynced])
     * が必ず先に完了済みで、これは後追いのfire-and-forget処理 — 失敗してもローカルの
     * 編集結果には一切影響しない。
     *
     * #POI画像OCR: [ocrText]は旧かっちゃん版がまだ知らないフィールド。彼が
     * caption等を編集してpushした際、彼の書くdataマップにはocrTextのキー自体が
     * 無い — [SetOptions.merge]を使うことで、そのdataマップに載っていない
     * フィールド(ocrTextに限らず将来同様に追加される未知のフィールドも含む)は
     * Firestore側の既存の値をそのまま残す(完全上書きしない)。以前は[SetOptions]無しの
     * 完全上書きだったため、旧版がocrText付きドキュメントを編集すると、その
     * ocrTextがFirestore上から消えてしまう恐れがあった([RoomEventSync.pushUpsert]の
     * toMapが同じ理由で既にmergeを使っているのと同じ対策)。
     */
    suspend fun pushMetadata(
        context: Context,
        driveFileId: String,
        caption: String?,
        albumName: String?,
        linkedDate: Long?,
        ocrText: String?,
        updatedAt: Long,
    ) {
        runCatching {
            val roomId = RoomStore(context).roomId ?: return
            val data = mapOf(
                "caption" to caption,
                "albumName" to albumName,
                "linkedDate" to linkedDate,
                "ocrText" to ocrText,
                "updatedAt" to updatedAt,
            )
            metadataRef(roomId).document(driveFileId).set(data, SetOptions.merge()).await()
        }
    }

    fun startListening(context: Context) {
        if (listenerRegistration != null) return
        val roomId = RoomStore(context).roomId ?: return
        val appContext = context.applicationContext
        listenerRegistration = metadataRef(roomId).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
            for (change in snapshot.documentChanges) {
                val driveFileId = change.document.id
                val data = change.document.data
                scope.launch {
                    runCatching { applyMetadataLocally(appContext, driveFileId, data) }
                }
            }
        }
    }

    /** パートナー端末発の編集を、こちらのローカル行(driveFileIdで特定できれば)へ反映する。
     * [PhotoDao.updateMetadata]でこの4項目とmetadataUpdatedAtだけをピンポイント
     * UPDATEし、driveFileId等の他フィールドは一切書き換えない
     * ([com.mspg.poicat.data.PhotoRepository.softDelete]と同じ設計判断)。
     *
     * #POI画像OCR: [ocrText]は旧かっちゃん版が書いたドキュメントにはキー自体が無い
     * (彼のバージョンはこの機能を知らない)。その場合は[photo.ocrText]（今この端末が
     * 既に持っている値）をそのまま維持する — dataに無いからといってnullへ書き換える
     * と、みゆたん端末が読み取ったOCR原文を、かっちゃん端末の(ocrTextと無関係な)
     * caption編集だけで消してしまうことになるため。 */
    private suspend fun applyMetadataLocally(context: Context, driveFileId: String, data: Map<String, Any?>) {
        val photoDao = PhotoDatabase.get(context).photoDao()
        val photo = photoDao.byDriveFileId(driveFileId) ?: return
        val remoteUpdatedAt = (data["updatedAt"] as? Number)?.toLong() ?: return
        if (remoteUpdatedAt <= photo.metadataUpdatedAt) return

        val caption = data["caption"] as? String
        val albumName = data["albumName"] as? String
        val linkedDate = (data["linkedDate"] as? Number)?.toLong()
        val ocrText = if (data.containsKey("ocrText")) data["ocrText"] as? String else photo.ocrText
        photoDao.updateMetadata(photo.id, caption, albumName, linkedDate, ocrText, remoteUpdatedAt)
    }
}
