package com.mspg.poicat.drive

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.StoredFile
import java.io.File

/**
 * 一時的な診断用 — [PhotoUploadDebug]と同じ形で、ファイル側の直近1回分のアップロード
 * 結果を保持する。写真側とは独立したオブジェクトのまま（意図的に共有しない — 片方の
 * 表示が他方の状態で上書きされないようにするため）。
 */
object FileUploadDebug {
    var lastResult: String? by mutableStateOf(null)
        private set

    internal fun record(text: String) {
        lastResult = text
    }
}

/**
 * ファイル追加時のGoogle Driveバックグラウンドアップロード。[PhotoDriveSync]と同じ
 * 設計をそのまま踏襲する（写真側のコードは一切変更していない — 共通化はせず、この
 * ファイルへ最小変更で横展開しただけ）:
 *
 * - ローカル保存（[FileRepository.importFromUri]）は呼び出し側で必ず先に完了しており、
 *   これはその後に「試すだけ」の追加処理 — ここで何が起きても、既にローカルに保存済みの
 *   ファイルや、ファイル追加という操作自体の成否には一切影響しない。
 * - Googleの同意画面をインタラクティブに表示しない — 既にGoogle連携画面でdrive.file
 *   権限を許可済みであればサイレントにアクセストークンが取れるはずで、その場合のみ
 *   アップロードを試みる。まだ権限が無い/失効している場合は静かに諦め、
 *   driveSyncStatusをFAILEDのままにする。
 * - 写真側と違い、元のファイル名とMIME typeを可能な限りそのまま使う —
 *   PDFはPDFとして、WordはWordファイルとしてDrive上で正常に開けるようにするため、
 *   拡張子やContent-Typeを勝手に変えない。
 * - アップロード成功後はDrive側の実サイズをgetFileInfo()で取得し、ローカルの元バイト数
 *   と一致した場合のみSYNCED（成功）とみなす（写真側と同じ検証方式）。
 */
object FileDriveSync {
    suspend fun syncNewFile(activity: Activity, fileRepository: FileRepository, file: StoredFile) {
        // このrunCatchingは診断用ではなく、ここで何が起きてもローカル保存側の呼び出し元
        // へ絶対に例外を伝播させないための最終防波堤。
        runCatching {
            if (file.driveSyncStatus == StoredFile.DRIVE_SYNC_SYNCED && file.driveFileId != null) {
                return@runCatching
            }

            val folderId = DriveConnectionStore(activity).fileFolderId
            if (folderId == null) {
                // Driveフォルダ未接続 — 何もしない（PENDINGのまま）。
                return@runCatching
            }

            val outcome = GoogleAuthManager.requestDriveAuthorization(activity).getOrElse {
                fileRepository.markDriveSyncStatus(file.id, StoredFile.DRIVE_SYNC_FAILED)
                return@runCatching
            }
            val accessToken = when (outcome) {
                is GoogleAuthManager.AuthorizationOutcome.Granted -> outcome.accessToken
                // ファイル追加のたびに同意画面を割り込ませたくないので、ここでは
                // インタラクティブな解決は行わず諦めるだけ。
                is GoogleAuthManager.AuthorizationOutcome.ResolutionNeeded -> {
                    fileRepository.markDriveSyncStatus(file.id, StoredFile.DRIVE_SYNC_FAILED)
                    return@runCatching
                }
            }

            fileRepository.markDriveSyncStatus(file.id, StoredFile.DRIVE_SYNC_SYNCING)
            val bytes = runCatching { File(file.filePath).readBytes() }.getOrNull()
            if (bytes == null) {
                fileRepository.markDriveSyncStatus(file.id, StoredFile.DRIVE_SYNC_FAILED)
                return@runCatching
            }

            val localSize = bytes.size
            // 元のファイル名・元のMIME typeをそのまま使う（写真側のように拡張子を
            // 固定した名前へ差し替えない）— PDF/Word/Excel等がDrive上でも正しい
            // 種類のファイルとして開けるようにするため。
            DriveFolderRepository.uploadFile(accessToken, folderId, file.fileName, file.mimeType, bytes)
                .onSuccess { uploaded ->
                    val info = DriveFolderRepository.getFileInfo(accessToken, uploaded.id).getOrNull()
                    val driveSize = info?.size
                    val sizeMatches = driveSize != null && driveSize == localSize.toLong()
                    FileUploadDebug.record(
                        "file.id=${file.id} name=${file.fileName} local=${localSize}B sent=${localSize}B " +
                            "drive size=${driveSize ?: "取得失敗"}B mimeType=${info?.mimeType ?: "取得失敗"} " +
                            "match=$sizeMatches",
                    )
                    if (sizeMatches) {
                        fileRepository.markDriveSynced(file.id, uploaded.id)
                    } else {
                        fileRepository.markDriveSyncStatus(file.id, StoredFile.DRIVE_SYNC_FAILED)
                    }
                }
                .onFailure {
                    FileUploadDebug.record("file.id=${file.id} name=${file.fileName} local=${localSize}B アップロード自体が失敗：${it.message ?: it.javaClass.simpleName}")
                    fileRepository.markDriveSyncStatus(file.id, StoredFile.DRIVE_SYNC_FAILED)
                }
        }
    }
}
