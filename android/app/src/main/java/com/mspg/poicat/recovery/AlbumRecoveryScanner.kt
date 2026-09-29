package com.mspg.poicat.recovery

import android.app.Activity
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.firestore.FirebaseFirestore
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.drive.DriveConnectionStore
import com.mspg.poicat.drive.DriveFolderRepository
import com.mspg.poicat.room.RoomDriveFolderSync
import com.mspg.poicat.room.RoomStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * #POIアルバム復旧調査 Phase 1: みゆタン/かっちゃん2台分の写真について、ローカル/
 * Google Drive(自分・パートナー双方)/Firestore共有情報のどこに何が残っているかを、
 * 一切書き込みを行わずにスキャンして集計するだけの専用オブジェクト。
 *
 * 【このオブジェクト全体を貫く絶対原則】
 * ・[PhotoRepository]の書き込み系メソッド(importFromUri/importFromDrive/
 *   registerCapturedFile/updateDetails/softDelete/markDriveSyncStatus/
 *   markDriveSynced等)は一切呼ばない — 読み取り専用の[PhotoRepository.allIncludingDeleted]
 *   だけを使う。
 * ・[DriveFolderRepository]のuploadFile/downloadFileは一切呼ばない — listFiles/
 *   getFileInfo(いずれも既存の読み取り専用API、この2つ自体も一切変更していない)
 *   だけを使う。
 * ・Firestoreへのset/update/delete/FieldValue書き込みは一切行わない —
 *   collection().get()による一度きりの読み取り(スナップショットリスナーの
 *   startListeningも呼ばない)のみ。
 * ・[GoogleAuthManager]のsignIn(サインイン自体)は一切呼ばない — 既にサインイン
 *   済みの場合のサイレントなdrive.file権限確認([GoogleAuthManager.requestDriveAuthorization]、
 *   これも同意画面をインタラクティブに起動しない読み取り専用相当の呼び出し)だけを使う。
 * ・ローカル実ファイルは読み取り(存在確認・サイズ取得・ハッシュ計算・EXIF読み取り)
 *   のみで、コピー・移動・削除・書き換えは一切行わない。
 *
 * このオブジェクトが返す結果は全てメモリ上だけの一時データで、Room/Firestore/
 * SharedPreferencesのいずれにも保存しない — 呼び出し元(UI)が画面を閉じれば消える。
 */
object AlbumRecoveryScanner {

    /**
     * 各情報源の取得結果。「0件だった」ことと「そもそも確認できなかった」ことを
     * 呼び出し元が絶対に混同しないよう明示的に区別する — 実機で確認された、
     * Drive連携警告が「未接続」も「認証失敗」も同じ1文言に丸めていた反省を、
     * 今回のスキャン結果では繰り返さない。
     */
    sealed class ScanState<out T> {
        /** この情報源自体が対象外(例: ルーム未参加、フォルダ未接続) — 異常ではない。 */
        object NotApplicable : ScanState<Nothing>()

        /** Driveのサイレント認証確認がGranted以外だった(再同意が必要、または
         * Play Services側の一時的な問題)。「0件」と表示してはならない状態。 */
        object AuthRequired : ScanState<Nothing>()

        /** 通信エラー・タイムアウト等、原因を問わない取得失敗。「0件」と表示しては
         * ならない状態。 */
        data class Failed(val reason: String) : ScanState<Nothing>()

        data class Success<T>(val value: T) : ScanState<T>()

        /** [Success]なら中身を、それ以外(未取得/認証要/失敗)ならnullを返す —
         * UI側が「取得できていない場合は0件と表示しない」を安全に実装するための
         * 補助関数。 */
        fun successOrNull(): T? = (this as? Success<T>)?.value
    }

    enum class LocalPhotoCategory {
        /** ファイルあり＋Room行あり(deletedAtなし) — 正常。 */
        NORMAL,

        /** ファイルあり＋対応するRoom行なし(filePathで突き合わせても見つからない)。 */
        ORPHAN_FILE,

        /** Room行あり(deletedAtなし)＋ファイルなし。 */
        MISSING_FILE,

        /** Room行のdeletedAtが設定済み＋ファイルは残っている — 復旧候補。 */
        SOFT_DELETED_FILE_PRESENT,

        /** Room行のdeletedAtが設定済み＋ファイルも既に無い — 復旧不可、異常ではないが
         * 「その他の不整合」枠として分類上ここへ入れる。 */
        OTHER_INCONSISTENT,
    }

    data class LocalPhotoEntry(
        val category: LocalPhotoCategory,
        val photoId: Long?,
        val filePath: String,
        val driveFileId: String?,
        val sizeBytes: Long?,
        val sha256: String?,
        val exifDateTime: String?,
    )

    enum class DriveOrigin { OWN, PARTNER }

    data class DriveEntry(
        val origin: DriveOrigin,
        val folderId: String,
        val id: String,
        val name: String,
        val mimeType: String?,
        val sizeBytes: Long?,
    )

    enum class DuplicateConfidence {
        /** 同一driveFileIdが複数のローカル行に紐づいている(想定外・要確認)。 */
        LEVEL1_DRIVE_ID,

        /** ローカルファイル同士のSHA-256が完全一致 — バイナリとして同一。 */
        LEVEL2_HASH,

        /** サイズ＋EXIF撮影日時の両方が一致するが、ハッシュ不一致または未計算。 */
        LEVEL3_SIZE_AND_EXIF,

        /** サイズのみ一致等、確証が弱く判断できない(Drive側はダウンロード禁止のため
         * ハッシュ/EXIFを取得できず、常にこのレベルに留まる)。 */
        LEVEL4_UNKNOWN,
    }

    data class DuplicateGroup(val confidence: DuplicateConfidence, val members: List<String>)

    data class FirestoreDriveFoldersInfo(
        val ownAlbumFolderId: String?,
        val partnerAlbumFolderIds: Set<String>,
        val deviceDocCount: Int,
    )

    data class PhotoMetadataDoc(
        val driveFileId: String,
        val caption: String?,
        val albumName: String?,
        val updatedAt: Long?,
    )

    data class AlbumRecoveryPreview(
        val localPhotos: ScanState<List<LocalPhotoEntry>>,
        val ownDrivePhotos: ScanState<List<DriveEntry>>,
        val partnerDrivePhotos: ScanState<List<DriveEntry>>,
        val firestoreDriveFolders: ScanState<FirestoreDriveFoldersInfo>,
        val firestorePhotoMetadata: ScanState<List<PhotoMetadataDoc>>,
        val firestoreTombstones: ScanState<Set<String>>,
        val duplicateCandidates: List<DuplicateGroup>,
    )

    /**
     * 【重要な制約】このスキャンは実行した端末1台分のローカルファイルしか読めない —
     * パートナー端末のローカルファイルへは技術的にアクセスできないため、「ローカル
     * ファイル同士のハッシュ比較」による重複検出([DuplicateConfidence.LEVEL2_HASH]/
     * [DuplicateConfidence.LEVEL3_SIZE_AND_EXIF])は、この端末のローカル写真同士に
     * 限られる。夫婦2台の写真を横断して比較するには、パートナー端末でも同じ
     * スキャンを実行し、2つの結果を見比べる必要がある(Phase 1はその比較UIまでは
     * 提供しない)。Drive側(自分・パートナー双方の既知folderId)は一覧できるため、
     * driveFileId([DuplicateConfidence.LEVEL1_DRIVE_ID]相当の突き合わせ)を介した
     * 復旧候補の判定は2台分を横断して行える。
     */
    suspend fun scan(activity: Activity, photoRepository: PhotoRepository): AlbumRecoveryPreview =
        withContext(Dispatchers.IO) {
            val localPhotos = runCatching { scanLocalPhotos(activity, photoRepository) }
                .fold(
                    onSuccess = { ScanState.Success(it) },
                    onFailure = { ScanState.Failed(it.message ?: it::class.simpleName ?: "unknown") },
                )

            val driveConnectionStore = DriveConnectionStore(activity)
            val ownFolderId = driveConnectionStore.albumFolderId
            val roomId = RoomStore(activity).roomId

            val partnerFolderIds: Set<String> = if (roomId == null) {
                emptySet()
            } else {
                runCatching { RoomDriveFolderSync.fetchPartnerFolderIds(activity).albumFolderIds }.getOrDefault(emptySet())
            }

            // Drive関連のスキャン自体が対象外(自分もパートナーもフォルダ未接続)なら、
            // GoogleAuthManagerへの問い合わせ自体を省略する(不要なAPI呼び出しをしない)。
            val accessTokenState: ScanState<String>? = if (ownFolderId == null && partnerFolderIds.isEmpty()) {
                null
            } else {
                when (val outcome = runCatching { GoogleAuthManager.requestDriveAuthorization(activity) }.getOrNull()?.getOrNull()) {
                    is GoogleAuthManager.AuthorizationOutcome.Granted -> ScanState.Success(outcome.accessToken)
                    is GoogleAuthManager.AuthorizationOutcome.ResolutionNeeded -> ScanState.AuthRequired
                    null -> ScanState.AuthRequired
                }
            }

            val ownDrivePhotos = scanDriveFolder(accessTokenState, ownFolderId, DriveOrigin.OWN)
            val partnerDrivePhotos = if (partnerFolderIds.isEmpty()) {
                ScanState.NotApplicable
            } else {
                mergeDriveScans(partnerFolderIds.map { scanDriveFolder(accessTokenState, it, DriveOrigin.PARTNER) })
            }

            val firestoreDriveFolders = scanFirestoreDriveFolders(roomId, ownFolderId, partnerFolderIds)
            val firestorePhotoMetadata = scanFirestorePhotoMetadata(roomId)
            val firestoreTombstones = scanFirestoreTombstones(roomId)

            val duplicates = computeDuplicateCandidates(
                localPhotos.successOrNull().orEmpty(),
                ownDrivePhotos.successOrNull().orEmpty(),
                partnerDrivePhotos.successOrNull().orEmpty(),
            )

            AlbumRecoveryPreview(
                localPhotos = localPhotos,
                ownDrivePhotos = ownDrivePhotos,
                partnerDrivePhotos = partnerDrivePhotos,
                firestoreDriveFolders = firestoreDriveFolders,
                firestorePhotoMetadata = firestorePhotoMetadata,
                firestoreTombstones = firestoreTombstones,
                duplicateCandidates = duplicates,
            )
        }

    /**
     * [context.filesDir]/photos/ 配下の実ファイルと、[photoRepository.allIncludingDeleted]
     * (論理削除済みも含む全件、読み取り専用)を[Photo.filePath]で突き合わせるだけ —
     * ファイルの読み取り(存在確認・サイズ・ハッシュ・EXIF)以外、一切の書き込みを
     * 行わない。ハッシュ計算はストリーミング読み込み(8KBバッファ)で行うため、
     * 動画等の大きなファイルでもメモリに全体を載せない。この関数自体が
     * [Dispatchers.IO]上で実行されるため(呼び出し元の[scan]がwithContextで
     * 包んでいる)、UIスレッドはブロックしない。
     */
    private suspend fun scanLocalPhotos(context: Context, photoRepository: PhotoRepository): List<LocalPhotoEntry> {
        val photoDir = File(context.filesDir, "photos")
        val filesOnDisk = photoDir.listFiles()?.toList().orEmpty()
        val allRows = photoRepository.allIncludingDeleted()
        val rowsByPath = allRows.associateBy { it.filePath }

        val entries = mutableListOf<LocalPhotoEntry>()

        for (file in filesOnDisk) {
            if (rowsByPath.containsKey(file.absolutePath)) continue
            entries.add(
                LocalPhotoEntry(
                    category = LocalPhotoCategory.ORPHAN_FILE,
                    photoId = null,
                    filePath = file.absolutePath,
                    driveFileId = null,
                    sizeBytes = file.length(),
                    sha256 = computeSha256(file),
                    exifDateTime = readExifDateTime(file),
                ),
            )
        }

        for (row in allRows) {
            val file = File(row.filePath)
            val exists = file.exists()
            val category = when {
                row.deletedAt == null && exists -> LocalPhotoCategory.NORMAL
                row.deletedAt == null && !exists -> LocalPhotoCategory.MISSING_FILE
                row.deletedAt != null && exists -> LocalPhotoCategory.SOFT_DELETED_FILE_PRESENT
                else -> LocalPhotoCategory.OTHER_INCONSISTENT
            }
            entries.add(
                LocalPhotoEntry(
                    category = category,
                    photoId = row.id,
                    filePath = row.filePath,
                    driveFileId = row.driveFileId,
                    sizeBytes = if (exists) file.length() else null,
                    sha256 = if (exists) computeSha256(file) else null,
                    exifDateTime = if (exists) readExifDateTime(file) else null,
                ),
            )
        }

        return entries
    }

    private fun computeSha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun readExifDateTime(file: File): String? = runCatching {
        val exif = ExifInterface(file.absolutePath)
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
    }.getOrNull()

    /** [folderId]がnullなら対象外。[DriveFolderRepository.listFiles]/[DriveFolderRepository.getFileInfo]
     * (どちらも既存の読み取り専用API、この関数自体は一切変更していない)だけを使い、
     * ダウンロード・アップロードは一切行わない。 */
    private suspend fun scanDriveFolder(
        accessTokenState: ScanState<String>?,
        folderId: String?,
        origin: DriveOrigin,
    ): ScanState<List<DriveEntry>> {
        if (folderId == null) return ScanState.NotApplicable
        val token = when (accessTokenState) {
            null, is ScanState.NotApplicable -> return ScanState.NotApplicable
            is ScanState.AuthRequired -> return ScanState.AuthRequired
            is ScanState.Failed -> return ScanState.Failed(accessTokenState.reason)
            is ScanState.Success -> accessTokenState.value
        }
        val listed = DriveFolderRepository.listFiles(token, folderId)
        return listed.fold(
            onSuccess = { files ->
                val entries = files.map { f ->
                    val info = DriveFolderRepository.getFileInfo(token, f.id).getOrNull()
                    DriveEntry(origin, folderId, f.id, f.name, f.mimeType, info?.size)
                }
                ScanState.Success(entries)
            },
            onFailure = { ScanState.Failed(it.message ?: it::class.simpleName ?: "unknown") },
        )
    }

    /** パートナーは複数のfolderId([RoomDriveFolderSync.fetchPartnerFolderIds]が
     * Setで返す、過去の複数回接続分)を持ち得るため、それぞれの結果を1つに集約する。
     * 1つでも成功していれば成功として扱い(取得できた分は無駄にしない)、全滅した
     * 場合だけAuthRequired/Failedを伝える。 */
    private fun mergeDriveScans(states: List<ScanState<List<DriveEntry>>>): ScanState<List<DriveEntry>> {
        if (states.isEmpty()) return ScanState.NotApplicable
        val successes = states.filterIsInstance<ScanState.Success<List<DriveEntry>>>()
        if (successes.isNotEmpty()) return ScanState.Success(successes.flatMap { it.value })
        if (states.any { it is ScanState.AuthRequired }) return ScanState.AuthRequired
        val failure = states.filterIsInstance<ScanState.Failed>().firstOrNull()
        return failure ?: ScanState.NotApplicable
    }

    private suspend fun scanFirestoreDriveFolders(
        roomId: String?,
        ownFolderId: String?,
        partnerFolderIds: Set<String>,
    ): ScanState<FirestoreDriveFoldersInfo> {
        if (roomId == null) return ScanState.NotApplicable
        return runCatching {
            val snapshot = FirebaseFirestore.getInstance()
                .collection("rooms").document(roomId).collection("driveFolders")
                .get().await()
            FirestoreDriveFoldersInfo(
                ownAlbumFolderId = ownFolderId,
                partnerAlbumFolderIds = partnerFolderIds,
                deviceDocCount = snapshot.documents.size,
            )
        }.fold(
            onSuccess = { ScanState.Success(it) },
            onFailure = { ScanState.Failed(it.message ?: it::class.simpleName ?: "unknown") },
        )
    }

    private suspend fun scanFirestorePhotoMetadata(roomId: String?): ScanState<List<PhotoMetadataDoc>> {
        if (roomId == null) return ScanState.NotApplicable
        return runCatching {
            val snapshot = FirebaseFirestore.getInstance()
                .collection("rooms").document(roomId).collection("photoMetadata")
                .get().await()
            snapshot.documents.map { doc ->
                PhotoMetadataDoc(
                    driveFileId = doc.id,
                    caption = doc.getString("caption"),
                    albumName = doc.getString("albumName"),
                    updatedAt = doc.getLong("updatedAt"),
                )
            }
        }.fold(
            onSuccess = { ScanState.Success(it) },
            onFailure = { ScanState.Failed(it.message ?: it::class.simpleName ?: "unknown") },
        )
    }

    private suspend fun scanFirestoreTombstones(roomId: String?): ScanState<Set<String>> {
        if (roomId == null) return ScanState.NotApplicable
        return runCatching {
            val snapshot = FirebaseFirestore.getInstance()
                .collection("rooms").document(roomId).collection("driveTombstones")
                .get().await()
            snapshot.documents.map { it.id }.toSet()
        }.fold(
            onSuccess = { ScanState.Success(it) },
            onFailure = { ScanState.Failed(it.message ?: it::class.simpleName ?: "unknown") },
        )
    }

    /**
     * 重複候補のグルーピング。「ファイル名/撮影日時/サイズが同じというだけの理由では
     * 同一と確定しない」という指示に従い、確証の強さごとにレベルを分け、
     * どのレベルであっても自動統合・自動削除は行わない(このオブジェクトは
     * グルーピング結果を返すだけで、削除・統合系のメソッド自体を持たない)。
     *
     * Drive側の候補([DriveEntry])はダウンロード禁止のためハッシュ/EXIFを取得
     * できず、サイズ一致だけの[DuplicateConfidence.LEVEL4_UNKNOWN]止まりになる —
     * これは意図的な安全側の制約であり、実装漏れではない。
     */
    private fun computeDuplicateCandidates(
        localPhotos: List<LocalPhotoEntry>,
        ownDrivePhotos: List<DriveEntry>,
        partnerDrivePhotos: List<DriveEntry>,
    ): List<DuplicateGroup> {
        val groups = mutableListOf<DuplicateGroup>()

        localPhotos.filter { it.driveFileId != null }
            .groupBy { it.driveFileId }
            .filter { it.value.size > 1 }
            .forEach { (id, members) ->
                groups.add(
                    DuplicateGroup(
                        DuplicateConfidence.LEVEL1_DRIVE_ID,
                        members.map { "ローカル:${it.filePath}(driveFileId=$id)" },
                    ),
                )
            }

        val level2Groups = localPhotos.filter { it.sha256 != null }
            .groupBy { it.sha256 }
            .filter { it.value.size > 1 }
        level2Groups.forEach { (_, members) ->
            groups.add(DuplicateGroup(DuplicateConfidence.LEVEL2_HASH, members.map { "ローカル:${it.filePath}" }))
        }
        val level2Paths = level2Groups.values.flatten().map { it.filePath }.toSet()

        localPhotos.filter { it.sizeBytes != null && it.exifDateTime != null && it.filePath !in level2Paths }
            .groupBy { it.sizeBytes to it.exifDateTime }
            .filter { it.value.size > 1 }
            .forEach { (_, members) ->
                groups.add(
                    DuplicateGroup(DuplicateConfidence.LEVEL3_SIZE_AND_EXIF, members.map { "ローカル:${it.filePath}" }),
                )
            }

        (ownDrivePhotos + partnerDrivePhotos).filter { it.sizeBytes != null }
            .groupBy { it.sizeBytes }
            .filter { it.value.size > 1 }
            .forEach { (_, members) ->
                groups.add(
                    DuplicateGroup(
                        DuplicateConfidence.LEVEL4_UNKNOWN,
                        members.map { "Drive(${if (it.origin == DriveOrigin.OWN) "自分" else "パートナー"}):${it.name}" },
                    ),
                )
            }

        return groups
    }
}
