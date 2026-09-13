package com.mspg.poicat.room

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.mspg.poicat.drive.DriveConnectionStore
import kotlinx.coroutines.tasks.await

/**
 * 各端末のDriveフォルダ接続情報(albumFolderId/fileFolderId)を、4桁PINルーム内の
 * パートナー端末と共有するためだけの、既存CatEvent([RoomEventSync])・tombstone
 * ([RoomDriveTombstoneSync])・写真メタデータ([RoomPhotoMetadataSync])同期とは
 * 完全に分離した小さな仕組み。
 *
 * 【背景】実機調査で判明: drive.fileスコープの`files.list`による名前検索(「POI用」
 * 「アルバム」等のフォルダ名で検索して発見する方式)は、同一Googleアカウント・
 * 同一アプリ・同一ルームであっても、別端末(別の認可イベント)で作成されたフォルダを
 * 検索結果として返さない。そのため、名前検索による「発見」には依存せず、各端末が
 * 「自分のfolderIdが何か」をこのFirestoreコレクションへ直接書き込み、パートナー
 * 端末はそれを読むだけで「既知のID」として使う。Drive API自体には新しい呼び出し
 * 方を追加しない — [com.mspg.poicat.drive.DriveFolderRepository.listFiles]は既存の
 * まま、既知のfolderIdを渡すだけで済む。
 *
 * ルーム未参加の間はpush/fetchのどちらも即座に何もしない。既存のCatEvent
 * (`rooms/{roomId}/events`)・tombstone(`rooms/{roomId}/driveTombstones`)・
 * photoMetadata(`rooms/{roomId}/photoMetadata`)とは別の、専用コレクション
 * `rooms/{roomId}/driveFolders/{deviceId}` にのみ書き込む。
 *
 * [DriveConnectionStore]のalbumFolderId/fileFolderId自体はここでは一切変更・
 * 削除しない — 読み取ってそのままFirestoreへ書き込むだけ。
 */
object RoomDriveFolderSync {
    data class PartnerFolderIds(val albumFolderIds: Set<String>, val fileFolderIds: Set<String>)

    private fun db() = FirebaseFirestore.getInstance()
    private fun collectionRef(roomId: String) =
        db().collection("rooms").document(roomId).collection("driveFolders")

    /**
     * この端末が現在持っているalbumFolderId/fileFolderId([DriveConnectionStore]、
     * ここでは一切変更・削除しない)を、そのままルームへ書き込む(fire-and-forgetでの
     * 利用を想定 — 失敗してもローカルのフォルダ接続状態には一切影響しない)。ルーム
     * 未参加、またはまだどちらのフォルダにも接続していない(両方null)場合は何もしない。
     */
    suspend fun pushFolderInfo(context: Context) {
        runCatching {
            val roomStore = RoomStore(context)
            val roomId = roomStore.roomId ?: return
            val driveStore = DriveConnectionStore(context)
            val albumFolderId = driveStore.albumFolderId
            val fileFolderId = driveStore.fileFolderId
            if (albumFolderId == null && fileFolderId == null) return

            val data = mapOf(
                "albumFolderId" to albumFolderId,
                "fileFolderId" to fileFolderId,
                "displayName" to roomStore.displayName,
                "updatedAt" to System.currentTimeMillis(),
            )
            collectionRef(roomId).document(roomStore.deviceId).set(data, SetOptions.merge()).await()
        }
    }

    /**
     * 同一ルーム内の、自分以外の端末が共有しているalbumFolderId/fileFolderIdを
     * 集める。ルーム未参加なら空集合(即noop)。パートナーがまだ一度も
     * [pushFolderInfo]を実行していない(＝この機能が無い旧バージョンを使っている、
     * またはまだフォルダに接続していない)場合も、単に何も見つからないだけで
     * 例外にはしない。
     */
    suspend fun fetchPartnerFolderIds(context: Context): PartnerFolderIds {
        val empty = PartnerFolderIds(emptySet(), emptySet())
        return runCatching {
            val roomStore = RoomStore(context)
            val roomId = roomStore.roomId ?: return empty
            val snapshot = collectionRef(roomId).get().await()
            val albumIds = mutableSetOf<String>()
            val fileIds = mutableSetOf<String>()
            for (doc in snapshot.documents) {
                if (doc.id == roomStore.deviceId) continue
                doc.getString("albumFolderId")?.let { albumIds.add(it) }
                doc.getString("fileFolderId")?.let { fileIds.add(it) }
            }
            PartnerFolderIds(albumIds, fileIds)
        }.getOrDefault(empty)
    }
}
