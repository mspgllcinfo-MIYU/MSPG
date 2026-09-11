package com.mspg.poicat.drive

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import java.io.File

/**
 * 一時的な診断用— 写真アップロードがDrive上で正しく開けるようになったことを実機で
 * 確認できるまでの間だけ、直近1回分のアップロード結果（ローカルバイト数／送信バイト数
 * ／Drive側が実際に記録したサイズ・mimeType）をConnectionScreenのデバッグパネルに
 * 表示するために保持する。Compose Stateなので値を更新すれば読んでいる画面が自動で
 * 再コンポーズされる（画面側から明示的にポーリングする必要はない）。
 */
object PhotoUploadDebug {
    var lastResult: String? by mutableStateOf(null)
        private set

    internal fun record(text: String) {
        lastResult = text
    }
}

/**
 * 写真追加時のGoogle Driveバックグラウンドアップロード。
 *
 * ローカル保存（[PhotoRepository.importFromUri]/[PhotoRepository.registerCapturedFile]）は
 * 呼び出し側で必ず先に完了しており、これはその後に「試すだけ」の追加処理 — ここで
 * 何が起きても、既にローカルに保存済みの写真や、写真追加という操作自体の成否には
 * 一切影響しない（呼び出し元は成功結果を返した後にこれを呼ぶだけで、結果を待つ必要も
 * 途中経過に応じて何かする必要もない）。
 *
 * ConnectionScreen（Google連携画面）とは違い、ここではGoogleの同意画面をインタラク
 * ティブに表示しない — 写真を追加するたびに予期せぬ同意ダイアログが割り込むのを
 * 避けるため。既にGoogle連携画面でdrive.file権限を許可済みであればサイレントに
 * （ResolutionNeeded無しで）アクセストークンが取れるはずで、その場合のみアップロード
 * を試みる。まだ権限が無い/失効している場合は静かに諦め、driveSyncStatusをFAILEDの
 * ままにする（Google連携画面から改めて接続すれば、次にこの関数が呼ばれたときにまた
 * 試せる）。
 */
object PhotoDriveSync {
    suspend fun syncNewPhoto(activity: Activity, photoRepository: PhotoRepository, photo: Photo) {
        // このrunCatchingは診断用ではなく、ここで何が起きてもローカル保存側の呼び出し元
        // へ絶対に例外を伝播させないための最終防波堤。
        runCatching {
            // 既にアップロード済みなら何もしない（重複アップロード防止の最終防波堤 —
            // 通常はこの関数自体が新規追加直後に一度だけ呼ばれる想定だが、将来の
            // 再試行呼び出し等からも安全に呼べるようにしておく）。
            if (photo.driveSyncStatus == Photo.DRIVE_SYNC_SYNCED && photo.driveFileId != null) {
                return@runCatching
            }

            val folderId = DriveConnectionStore(activity).albumFolderId
            if (folderId == null) {
                // Driveフォルダ未接続 — 何もしない（PENDINGのまま）。
                return@runCatching
            }

            val outcome = GoogleAuthManager.requestDriveAuthorization(activity).getOrElse {
                photoRepository.markDriveSyncStatus(photo.id, Photo.DRIVE_SYNC_FAILED)
                return@runCatching
            }
            val accessToken = when (outcome) {
                is GoogleAuthManager.AuthorizationOutcome.Granted -> outcome.accessToken
                // 写真追加のたびに同意画面を割り込ませたくないので、ここでは
                // インタラクティブな解決は行わず諦めるだけ。
                is GoogleAuthManager.AuthorizationOutcome.ResolutionNeeded -> {
                    photoRepository.markDriveSyncStatus(photo.id, Photo.DRIVE_SYNC_FAILED)
                    return@runCatching
                }
            }

            photoRepository.markDriveSyncStatus(photo.id, Photo.DRIVE_SYNC_SYNCING)
            val bytes = runCatching { File(photo.filePath).readBytes() }.getOrNull()
            if (bytes == null) {
                photoRepository.markDriveSyncStatus(photo.id, Photo.DRIVE_SYNC_FAILED)
                return@runCatching
            }

            val displayName = (photo.caption?.trim()?.ifBlank { null } ?: "photo_${photo.id}") + ".jpg"
            val localSize = bytes.size
            DriveFolderRepository.uploadFile(accessToken, folderId, displayName, "image/jpeg", bytes)
                .onSuccess { uploaded ->
                    // fileIdが返ってHTTP 200だっただけでは中身が正しく保存された証拠に
                    // ならない — Drive側へ実際のsize/mimeTypeを問い合わせ、ローカルの
                    // 元バイト数と一致した場合のみSYNCED（成功）とみなす。
                    val info = DriveFolderRepository.getFileInfo(accessToken, uploaded.id).getOrNull()
                    val driveSize = info?.size
                    val sizeMatches = driveSize != null && driveSize == localSize.toLong()
                    PhotoUploadDebug.record(
                        "photo.id=${photo.id} local=${localSize}B sent=${localSize}B " +
                            "drive size=${driveSize ?: "取得失敗"}B mimeType=${info?.mimeType ?: "取得失敗"} " +
                            "match=$sizeMatches",
                    )
                    if (sizeMatches) {
                        photoRepository.markDriveSynced(photo.id, uploaded.id)
                    } else {
                        // fileId自体は作られてしまっているが、サイズが一致しないので
                        // アップロード失敗として扱う（同じdriveFileIdは記録しない —
                        // 次回はdriveFileIdが無いままFAILEDから再アップロードを試みられる）。
                        photoRepository.markDriveSyncStatus(photo.id, Photo.DRIVE_SYNC_FAILED)
                    }
                }
                .onFailure {
                    PhotoUploadDebug.record("photo.id=${photo.id} local=${localSize}B アップロード自体が失敗：${it.message ?: it.javaClass.simpleName}")
                    photoRepository.markDriveSyncStatus(photo.id, Photo.DRIVE_SYNC_FAILED)
                }
        }
    }
}
