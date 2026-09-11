package com.mspg.poicat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A file received via Android's share sheet (PDF, Word, Excel, PowerPoint,
 * plain text, or any other non-image type) and copied into app-private
 * storage. Lives in its own [FileDatabase], kept separate from every other
 * database so this feature can never affect photo/schedule/memo/task data
 * or require migrating it.
 */
@Entity(tableName = "stored_files")
data class StoredFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Absolute path to the copy of the file stored in app-private storage
     * (regular internal storage, not the cache dir — never auto-deleted). */
    val filePath: String,
    /** Original file name as reported by the sharing app, for display. */
    val fileName: String,
    /** MIME type as reported by the sharing app (e.g. "application/pdf"). */
    val mimeType: String,
    /** Epoch millis when the file was saved into the app. */
    val savedAt: Long = System.currentTimeMillis(),
    /** Google Driveへのアップロード状態。既存行(この列追加前に保存されたファイル)は
     * マイグレーションでPENDINGになるが、自動で再送はされない — 新規追加分のみ
     * 追加直後にアップロードを試みる。[Photo.driveSyncStatus]と同じ4値。 */
    val driveSyncStatus: String = DRIVE_SYNC_PENDING,
    /** アップロード成功後のDrive側ファイルID。再アップロード防止に使う。 */
    val driveFileId: String? = null,
) {
    companion object {
        const val DRIVE_SYNC_PENDING = "PENDING"
        const val DRIVE_SYNC_SYNCING = "SYNCING"
        const val DRIVE_SYNC_SYNCED = "SYNCED"
        const val DRIVE_SYNC_FAILED = "FAILED"
    }
}
