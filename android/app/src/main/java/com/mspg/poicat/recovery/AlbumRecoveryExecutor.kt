package com.mspg.poicat.recovery

import android.app.Activity
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.drive.DriveFolderRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「全部復活」の実行役。[AlbumRecoveryScanner]が集めたスキャン結果(読み取り専用)を
 * 受け取り、実ファイルをCOPY＋INSERTだけでPhotoDatabaseへ登録する司令塔。
 * [AlbumRecoveryScanner]自体の責務・実装は一切変更しない(引き続き読み取り専用)。
 *
 * 【絶対原則】
 * ・呼び出すのは[PhotoRepository.registerCapturedFile]/[PhotoRepository.importFromDrive]
 *   (いずれも既存・無変更)と、今回追加した[PhotoRepository.importRecoveredCopy]の
 *   3つだけ — いずれもINSERTのみで、UPDATE/DELETEは一切行わない。
 * ・Drive原本は[DriveFolderRepository.downloadFile](既存の読み取り専用API)でのみ
 *   読む。アップロード・削除・移動・改名は一切行わない。
 * ・Firestore(driveFolders/photoMetadata/driveTombstones)への書き込みは一切行わない。
 * ・1件の失敗で全体を中断しない — 各対象を[runCatching]で個別に処理し、成功/失敗を
 *   集計するだけ。失敗した対象の原本(ローカルファイル/Drive上のファイル)には一切
 *   触れない。
 * ・同一の救出対象を二重に復旧しない(「全部復活」を複数回押しても増殖しない) —
 *   driveFileIdが確定するもの([RecoveryTarget.DriveFile])は既存の
 *   [PhotoRepository.byDriveFileId]で、driveFileIdをnullのまま保存するもの
 *   ([RecoveryTarget.TombstonedDriveFile]/[RecoveryTarget.LocalSoftDeleteNoLink]/
 *   [RecoveryTarget.LocalSoftDeleteTombstoned])は[PhotoRepository.recoveryMarker]を
 *   内部ファイル名に埋め込んだ上で既存行のファイル名を検索して、それぞれ既存判定する。
 */
object AlbumRecoveryExecutor {

    sealed class RecoveryTarget {
        /** ローカルに実ファイルのみ存在し、Room行が無いもの。既存ファイルはそのまま
         * 参照するだけで、コピーは行わない。 */
        data class OrphanLocal(val filePath: String) : RecoveryTarget()

        /** Drive(自分/パートナー)にあり、まだローカルに対応する行が無い、tombstone対象
         * でもない通常のファイル。 */
        data class DriveFile(
            val origin: AlbumRecoveryScanner.DriveOrigin,
            val driveFileId: String,
            val name: String,
        ) : RecoveryTarget()

        /** Drive(自分/パートナー)にあり、driveTombstonesに記録済みのファイル。
         * driveFileIdへは紐付けない独立コピーとして救出する。 */
        data class TombstonedDriveFile(
            val origin: AlbumRecoveryScanner.DriveOrigin,
            val driveFileId: String,
            val name: String,
        ) : RecoveryTarget()

        /** ローカルでsoftDelete済み(deletedAt設定済み)だが実ファイルが残っており、
         * 元のPhoto行にdriveFileIdが無い(＝一度もDriveへ同期されていない)もの。 */
        data class LocalSoftDeleteNoLink(val filePath: String) : RecoveryTarget()

        /** ローカルでsoftDelete済みだが実ファイルが残っており、かつ元のPhoto行に
         * driveFileIdがある(＝tombstone対象と同種のリスクを持つ)もの。 */
        data class LocalSoftDeleteTombstoned(val filePath: String, val driveFileId: String) : RecoveryTarget()
    }

    data class RecoveryPlan(val targets: List<RecoveryTarget>) {
        val orphanLocalCount get() = targets.count { it is RecoveryTarget.OrphanLocal }
        val ownDriveCount get() = targets.count { it is RecoveryTarget.DriveFile && it.origin == AlbumRecoveryScanner.DriveOrigin.OWN }
        val partnerDriveCount get() = targets.count { it is RecoveryTarget.DriveFile && it.origin == AlbumRecoveryScanner.DriveOrigin.PARTNER }
        val tombstoneCount get() = targets.count { it is RecoveryTarget.TombstonedDriveFile }
        val softDeleteCount get() = targets.count { it is RecoveryTarget.LocalSoftDeleteNoLink || it is RecoveryTarget.LocalSoftDeleteTombstoned }
        val totalCount get() = targets.size
    }

    data class RecoveryOutcome(val target: RecoveryTarget, val success: Boolean, val reason: String?)

    data class RecoveryResult(val outcomes: List<RecoveryOutcome>) {
        val succeededCount get() = outcomes.count { it.success }
        val failedCount get() = outcomes.count { !it.success }
    }

    /**
     * 現在のスキャン結果から、今回まだ救出できていない対象だけを抽出する
     * (Drive/Firestoreへは一切書き込まない、ローカルDBの読み取りのみ)。
     * [executeRecovery]は必ずこの関数が返した[RecoveryPlan]をそのまま渡して実行する
     * ― 画面②で表示した件数と④で実際に処理する対象が食い違わないようにするため。
     */
    suspend fun buildPlan(
        preview: AlbumRecoveryScanner.AlbumRecoveryPreview,
        photoRepository: PhotoRepository,
    ): RecoveryPlan = withContext(Dispatchers.IO) {
        val targets = mutableListOf<RecoveryTarget>()
        val tombstoneIds = preview.firestoreTombstones.successOrNull().orEmpty()
        val allLocalRows = photoRepository.allIncludingDeleted()
        val claimedMarkers = mutableSetOf<String>()
        val claimedDriveIds = mutableSetOf<String>()

        fun tryClaimMarker(marker: String): Boolean {
            if (allLocalRows.any { File(it.filePath).name.contains(marker) }) return false
            return claimedMarkers.add(marker)
        }

        val localEntries = preview.localPhotos.successOrNull().orEmpty()
        localEntries.filter { it.category == AlbumRecoveryScanner.LocalPhotoCategory.ORPHAN_FILE }
            .forEach { targets.add(RecoveryTarget.OrphanLocal(it.filePath)) }

        val driveLists = listOf(
            AlbumRecoveryScanner.DriveOrigin.OWN to preview.ownDrivePhotos.successOrNull().orEmpty(),
            AlbumRecoveryScanner.DriveOrigin.PARTNER to preview.partnerDrivePhotos.successOrNull().orEmpty(),
        )
        for ((origin, entries) in driveLists) {
            for (entry in entries) {
                if (entry.id in tombstoneIds) {
                    val marker = PhotoRepository.recoveryMarker("tomb", entry.id)
                    if (tryClaimMarker(marker)) {
                        targets.add(RecoveryTarget.TombstonedDriveFile(origin, entry.id, entry.name))
                    }
                } else if (photoRepository.byDriveFileId(entry.id) == null && claimedDriveIds.add(entry.id)) {
                    targets.add(RecoveryTarget.DriveFile(origin, entry.id, entry.name))
                }
            }
        }

        localEntries.filter { it.category == AlbumRecoveryScanner.LocalPhotoCategory.SOFT_DELETED_FILE_PRESENT }
            .forEach { entry ->
                val driveFileId = entry.driveFileId
                if (driveFileId != null) {
                    val marker = PhotoRepository.recoveryMarker("tomb", driveFileId)
                    if (tryClaimMarker(marker)) {
                        targets.add(RecoveryTarget.LocalSoftDeleteTombstoned(entry.filePath, driveFileId))
                    }
                } else {
                    val marker = PhotoRepository.recoveryMarker("softrec", entry.filePath)
                    if (tryClaimMarker(marker)) {
                        targets.add(RecoveryTarget.LocalSoftDeleteNoLink(entry.filePath))
                    }
                }
            }

        RecoveryPlan(targets)
    }

    /**
     * [plan]の対象を1件ずつ復旧する。Drive由来の対象が1件でもあれば、サイレントな
     * (同意画面を割り込ませない)Drive認可確認を1回だけ行う — 既存の
     * [com.mspg.poicat.drive.RoomCatalogSync.grantedAccessToken]と同じ方針。
     * 取得できなかった場合、Drive由来の対象だけが失敗として記録され、ローカル由来の
     * 対象(孤立ローカル/ローカルsoftDelete)は影響を受けず処理を続ける。
     */
    suspend fun executeRecovery(
        activity: Activity,
        photoRepository: PhotoRepository,
        plan: RecoveryPlan,
    ): RecoveryResult = withContext(Dispatchers.IO) {
        val needsDriveToken = plan.targets.any {
            it is RecoveryTarget.DriveFile || it is RecoveryTarget.TombstonedDriveFile
        }
        val accessToken: String? = if (!needsDriveToken) {
            null
        } else {
            val outcome = runCatching { GoogleAuthManager.requestDriveAuthorization(activity) }
                .getOrNull()?.getOrNull()
            (outcome as? GoogleAuthManager.AuthorizationOutcome.Granted)?.accessToken
        }

        val outcomes = plan.targets.map { target ->
            val result = runCatching { recoverOne(photoRepository, target, accessToken) }
            RecoveryOutcome(
                target = target,
                success = result.isSuccess,
                reason = result.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "unknown" },
            )
        }
        RecoveryResult(outcomes)
    }

    private suspend fun recoverOne(photoRepository: PhotoRepository, target: RecoveryTarget, accessToken: String?) {
        when (target) {
            is RecoveryTarget.OrphanLocal -> {
                photoRepository.registerCapturedFile(File(target.filePath), caption = null, albumName = null)
            }

            is RecoveryTarget.DriveFile -> {
                val token = accessToken ?: error("Drive認可が取れていないにゃ")
                val bytes = DriveFolderRepository.downloadFile(token, target.driveFileId).getOrThrow()
                photoRepository.importFromDrive(bytes, target.driveFileId)
            }

            is RecoveryTarget.TombstonedDriveFile -> {
                val token = accessToken ?: error("Drive認可が取れていないにゃ")
                val bytes = DriveFolderRepository.downloadFile(token, target.driveFileId).getOrThrow()
                val marker = PhotoRepository.recoveryMarker("tomb", target.driveFileId)
                photoRepository.importRecoveredCopy(bytes, marker)
            }

            is RecoveryTarget.LocalSoftDeleteNoLink -> {
                val bytes = File(target.filePath).readBytes()
                val marker = PhotoRepository.recoveryMarker("softrec", target.filePath)
                photoRepository.importRecoveredCopy(bytes, marker)
            }

            is RecoveryTarget.LocalSoftDeleteTombstoned -> {
                val bytes = File(target.filePath).readBytes()
                val marker = PhotoRepository.recoveryMarker("tomb", target.driveFileId)
                photoRepository.importRecoveredCopy(bytes, marker)
            }
        }
        Unit
    }
}
