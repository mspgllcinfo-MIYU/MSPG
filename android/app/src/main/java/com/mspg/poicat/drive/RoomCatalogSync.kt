package com.mspg.poicat.drive

import android.app.Activity
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.PhotoRepository
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
 * 実機調査で判明: 夫婦2台がそれぞれ別々のタイミングで初回のGoogle連携を行った場合、
 * [DriveFolderRepository.ensureFolder]の「名前で検索して無ければ作成」には排他制御が
 * 無いため、"POI用"や"アルバム"/"ファイル"という同名のフォルダが端末ごとに別IDで
 * 重複作成されてしまうことがある。この場合、各端末に保存されている
 * [DriveConnectionStore.albumFolderId]/[DriveConnectionStore.fileFolderId]（アップロード
 * 先として使う1つのID）だけを一覧しても、もう片方の端末が作った別IDのフォルダの中身は
 * 永久に見えない。
 *
 * そのため、ここでの「発見（読み取り）対象」は自端末のキャッシュ済みIDだけに限定せず、
 * 同名の"POI用"フォルダ・その下の同名の対象フォルダ（アルバム/ファイル）をDrive上で
 * 全て検索し、見つかった全フォルダのlistFiles結果を合算する（[discoverFolderIds]）。
 * フォルダ自体の削除・統合・移動・再作成は一切行わない — アップロード先
 * （[PhotoDriveSync]/[FileDriveSync]が使う既存のキャッシュ済みfolderId）も変更しない。
 * 同一driveFileIdは全フォルダ分を通じて一度しか取り込まない（[refreshAlbumCatalog]/
 * [refreshFileCatalog]内の`seen`集合、およびbyDriveFileIdの既存チェック）。
 *
 * ルーム未参加、またはDriveフォルダ未接続（＝一度もこの端末で接続していない）の場合は
 * 即座に何もしない（[CatalogRefreshOutcome.SKIPPED]）。Google同意画面はここでは
 * インタラクティブに出さない（PhotoDriveSync/FileDriveSyncと同じ方針、勝手な再ログイン・
 * 認可解除は一切しない）— サイレントに認可が取れない場合は[CatalogRefreshOutcome.
 * AUTH_NOT_GRANTED]を返すだけで、以前のように完全に無言のまま終わらない（呼び出し元の
 * 画面がこれを見て「Drive連携の確認が必要かも」といった非侵襲的な表示を出せるようにする
 * ためのもので、認可を強制的に取り直す処理はここには一切無い）。
 *
 * それ以外の失敗（一覧取得・ダウンロードの個別失敗等）は従来通りrunCatchingで囲み、
 * 既存のローカル一覧表示には一切影響しない。
 */
object RoomCatalogSync {
    // ConnectionScreenのFolderTarget/ensureFolderが実際に作成・検索する名前と完全に
    // 一致させる必要がある（フォルダの作成・接続先決定自体はConnectionScreen/
    // DriveConnectionStoreの既存ロジックのまま — ここでは名前検索のためだけに複製）。
    private const val POI_ROOT_FOLDER_NAME = "POI用"
    private const val ALBUM_FOLDER_NAME = "アルバム"
    private const val FILE_FOLDER_NAME = "ファイル"

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
            val folderIds = discoverFolderIds(accessToken, ALBUM_FOLDER_NAME, cachedFolderId)
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
            val folderIds = discoverFolderIds(accessToken, FILE_FOLDER_NAME, cachedFolderId)
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

    /**
     * [targetFolderName]という名前のフォルダを、"POI用"という名前のフォルダ(複数存在
     * する場合は全て)の直下から全て発見し、そのIDの集合を返す。フォルダの削除・統合・
     * 再作成は一切行わない — 発見(読み取り対象を広げる)だけ。[cachedFolderId]
     * (この端末が実際にアップロード先として使っているフォルダID)は、名前検索で万一
     * 見つからなかった場合でも読み取り対象から漏れないよう、常に結果に含める(既存の
     * 動作を後退させないための保険)。
     */
    private suspend fun discoverFolderIds(accessToken: String, targetFolderName: String, cachedFolderId: String): Set<String> {
        val ids = mutableSetOf(cachedFolderId)
        val roots = DriveFolderRepository.findAllFolders(accessToken, POI_ROOT_FOLDER_NAME, null).getOrNull() ?: emptyList()
        for (root in roots) {
            DriveFolderRepository.findAllFolders(accessToken, targetFolderName, root.id).getOrNull()?.forEach { ids.add(it.id) }
        }
        return ids
    }

    /** Grantedの場合だけアクセストークンを返す — 同意画面が必要な場合(ResolutionNeeded)
     * は何もせず諦める（写真/ファイル追加時のPhotoDriveSync/FileDriveSyncと同じ方針、
     * インタラクティブな再認可・再ログインは一切行わない）。 */
    private suspend fun grantedAccessToken(activity: Activity): String? {
        val outcome = GoogleAuthManager.requestDriveAuthorization(activity).getOrNull() ?: return null
        return (outcome as? GoogleAuthManager.AuthorizationOutcome.Granted)?.accessToken
    }
}
