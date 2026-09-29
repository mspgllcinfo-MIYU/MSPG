package com.mspg.poicat.drive

import android.app.Activity
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.room.RoomDriveFolderSync
import com.mspg.poicat.room.RoomDriveTombstoneSync
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
 * 【重要・実機調査で判明した経緯】当初は「同名フォルダをDrive上で名前検索して全て
 * 発見する」方式(findAllFolders)を試したが、drive.fileスコープの制約により、
 * 同一Googleアカウント・同一アプリ・同一ルームであっても、別端末(別の認可イベント)
 * で作成されたフォルダはfiles.list検索の結果に出てこないことが実機で確認された。
 * そのため名前検索による発見には一切依存せず、[RoomDriveFolderSync](Firestore経由
 * で各端末が自分のalbumFolderId/fileFolderIdを共有するだけの仕組み)から取得した
 * パートナー端末の「既知のfolderId」に対して、既存の[DriveFolderRepository.listFiles]
 * (既知IDへの直接アクセス、検索ではない)を呼ぶ方式に変更した。
 *
 * フォルダ自体の削除・統合・移動・再作成は一切行わない。アップロード先
 * （[PhotoDriveSync]/[FileDriveSync]が使う既存のキャッシュ済みfolderId）も変更しない
 * — [RoomDriveFolderSync]はfolderIdを読み取って共有するだけで、書き換えは行わない。
 * 同一driveFileIdは全フォルダ分を通じて一度しか取り込まない（[refreshAlbumCatalog]/
 * [refreshFileCatalog]内の`seen`集合、およびbyDriveFileIdの既存チェック）。
 *
 * ルーム未参加、またはDriveフォルダ未接続（＝一度もこの端末で接続していない）の場合は
 * 即座に何もしない（[CatalogRefreshOutcome.SKIPPED]）。Google同意画面はここでは
 * インタラクティブに出さない（PhotoDriveSync/FileDriveSyncと同じ方針、勝手な再ログイン・
 * 認可解除は一切しない）— サイレントに認可が取れない場合は[CatalogRefreshOutcome.
 * AUTH_NOT_GRANTED]を返すだけで、以前のように完全に無言のまま終わらない。
 *
 * それ以外の失敗（一覧取得・ダウンロードの個別失敗等）は従来通りrunCatchingで囲み、
 * 既存のローカル一覧表示には一切影響しない。
 */
object RoomCatalogSync {
    enum class CatalogRefreshOutcome {
        /** ルーム未参加、またはこの端末がまだ対象フォルダに接続していない — 通常の
         * 状態で、UIに警告を出す必要はない。 */
        SKIPPED,

        /** フォルダには接続済みだが、Drive認可がサイレントに取れなかった
         * (ResolutionNeeded/失敗/タイムアウト)。以前は完全に無言で終わっていたが、
         * この端末のアップロード/ダウンロードが機能していない可能性が高いことを
         * 呼び出し元(画面)が示せるようにするための状態。 */
        AUTH_NOT_GRANTED,

        /** 一覧取得・取り込みを試みた(実際に何件取り込んだかは問わない)。 */
        OK,
    }

    suspend fun refreshAlbumCatalog(activity: Activity, photoRepository: PhotoRepository): CatalogRefreshOutcome {
        if (RoomStore(activity).roomId == null) return CatalogRefreshOutcome.SKIPPED
        val cachedFolderId = DriveConnectionStore(activity).albumFolderId ?: return CatalogRefreshOutcome.SKIPPED
        val accessToken = grantedAccessToken(activity) ?: return CatalogRefreshOutcome.AUTH_NOT_GRANTED

        runCatching {
            val partner = RoomDriveFolderSync.fetchPartnerFolderIds(activity)
            val folderIds = partner.albumFolderIds + cachedFolderId
            val seen = mutableSetOf<String>()
            for (folderId in folderIds) {
                DriveFolderRepository.listFiles(accessToken, folderId).getOrNull()?.forEach { remote ->
                    if (!seen.add(remote.id)) return@forEach
                    if (photoRepository.byDriveFileId(remote.id) != null) return@forEach
                    // POI上で(自端末かパートナー端末かを問わず)既に削除済みと記録されている
                    // 写真は、Drive原本がそのまま残っていても再取り込みしない — 削除した
                    // つもりのものが復活する問題(以前実機で報告)への対策。
                    if (RoomDriveTombstoneSync.isTombstoned(activity, remote.id)) return@forEach
                    val bytes = DriveFolderRepository.downloadFile(accessToken, remote.id).getOrNull() ?: return@forEach
                    photoRepository.importFromDrive(bytes, remote.id)
                }
            }
        }
        return CatalogRefreshOutcome.OK
    }

    suspend fun refreshFileCatalog(activity: Activity, fileRepository: FileRepository): CatalogRefreshOutcome {
        if (RoomStore(activity).roomId == null) return CatalogRefreshOutcome.SKIPPED
        val cachedFolderId = DriveConnectionStore(activity).fileFolderId ?: return CatalogRefreshOutcome.SKIPPED
        val accessToken = grantedAccessToken(activity) ?: return CatalogRefreshOutcome.AUTH_NOT_GRANTED

        runCatching {
            val partner = RoomDriveFolderSync.fetchPartnerFolderIds(activity)
            val folderIds = partner.fileFolderIds + cachedFolderId
            val seen = mutableSetOf<String>()
            for (folderId in folderIds) {
                DriveFolderRepository.listFiles(accessToken, folderId).getOrNull()?.forEach { remote ->
                    if (!seen.add(remote.id)) return@forEach
                    if (fileRepository.byDriveFileId(remote.id) != null) return@forEach
                    if (RoomDriveTombstoneSync.isTombstoned(activity, remote.id)) return@forEach
                    val bytes = DriveFolderRepository.downloadFile(accessToken, remote.id).getOrNull() ?: return@forEach
                    fileRepository.importFromDrive(bytes, remote.id, remote.name, remote.mimeType ?: "application/octet-stream")
                }
            }
        }
        return CatalogRefreshOutcome.OK
    }

    /** Grantedの場合だけアクセストークンを返す — 同意画面が必要な場合(ResolutionNeeded)
     * は何もせず諦める（写真/ファイル追加時のPhotoDriveSync/FileDriveSyncと同じ方針、
     * インタラクティブな再認可・再ログインは一切行わない）。 */
    private suspend fun grantedAccessToken(activity: Activity): String? {
        val outcome = GoogleAuthManager.requestDriveAuthorization(activity).getOrNull() ?: return null
        return (outcome as? GoogleAuthManager.AuthorizationOutcome.Granted)?.accessToken
    }
}
