package com.mspg.poicat.drive

import android.app.Activity
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.room.RoomStore

/**
 * ルーム共有（4桁PIN）が有効な間だけ動く、写真/ファイルの「取り込み漏れチェック」。
 *
 * 写真/ファイル本体は元々Firebase Storageではなく共有Google Driveアカウント経由で
 * 保存する設計（Run #104/#105で確認済み）。夫婦2台が同じGoogleアカウントでDrive
 * フォルダへ接続していれば、片方が追加した写真/ファイルは技術的には既にもう片方の
 * アカウントからも見えるDrive上に存在している — ただしローカルのRoom DB
 * （PhotoDatabase/FileDatabase）はまだそれを1行としても持っていないため、アルバム/
 * ファイル画面には表示されない。この関数は「POI用/アルバム」「POI用/ファイル」
 * フォルダの中身をDrive APIで一覧し、ローカルにまだ無い（driveFileIdで判定）ものだけを
 * ダウンロードしてローカル行として取り込む — 既にある分は何もしない。
 *
 * ルーム未参加、またはDriveフォルダ未接続の場合は即座に何もしない。Google同意画面も
 * ここではインタラクティブに出さない（PhotoDriveSync/FileDriveSyncと同じ方針）。
 * 全体をrunCatchingで囲み、失敗しても既存のローカル一覧表示には一切影響しない —
 * 呼び出し側は既存のreload()の後にこれを呼ぶだけで良い。
 */
object RoomCatalogSync {
    suspend fun refreshAlbumCatalog(activity: Activity, photoRepository: PhotoRepository) {
        runCatching {
            if (RoomStore(activity).roomId == null) return
            val folderId = DriveConnectionStore(activity).albumFolderId ?: return
            val accessToken = silentAccessToken(activity) ?: return

            DriveFolderRepository.listFiles(accessToken, folderId).getOrNull()?.forEach { remote ->
                if (photoRepository.byDriveFileId(remote.id) != null) return@forEach
                val bytes = DriveFolderRepository.downloadFile(accessToken, remote.id).getOrNull() ?: return@forEach
                photoRepository.importFromDrive(bytes, remote.id)
            }
        }
    }

    suspend fun refreshFileCatalog(activity: Activity, fileRepository: FileRepository) {
        runCatching {
            if (RoomStore(activity).roomId == null) return
            val folderId = DriveConnectionStore(activity).fileFolderId ?: return
            val accessToken = silentAccessToken(activity) ?: return

            DriveFolderRepository.listFiles(accessToken, folderId).getOrNull()?.forEach { remote ->
                if (fileRepository.byDriveFileId(remote.id) != null) return@forEach
                val bytes = DriveFolderRepository.downloadFile(accessToken, remote.id).getOrNull() ?: return@forEach
                fileRepository.importFromDrive(bytes, remote.id, remote.name, remote.mimeType ?: "application/octet-stream")
            }
        }
    }

    /** サイレントに取れた場合だけトークンを返す — 同意画面が必要な場合(ResolutionNeeded)は
     * 何もせず諦める（写真/ファイル追加時のPhotoDriveSync/FileDriveSyncと同じ方針）。 */
    private suspend fun silentAccessToken(activity: Activity): String? {
        val outcome = GoogleAuthManager.requestDriveAuthorization(activity).getOrNull() ?: return null
        return (outcome as? GoogleAuthManager.AuthorizationOutcome.Granted)?.accessToken
    }
}
