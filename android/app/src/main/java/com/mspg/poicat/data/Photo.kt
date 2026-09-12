package com.mspg.poicat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single saved photo. The image file itself lives once in the app's
 * private storage ([filePath]); everything else here is just metadata used
 * to find and group it — an album tag, and (for a later phase) a link to
 * one [CatEvent] row so the same photo can be shown from a schedule or a
 * memo without ever being copied. Lives in its own [PhotoDatabase], kept
 * separate from the existing `cat_events` database so this feature can
 * never affect that schema or the data already stored there.
 */
@Entity(tableName = "photos")
data class Photo(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Absolute path to the copy of the image stored in app-private storage. */
    val filePath: String,
    /** Short user-entered title/memo for the photo. */
    val caption: String? = null,
    /** Free-text album/category name ("病院", "旅行", …), or null for uncategorized. */
    val albumName: String? = null,
    /** Epoch millis when the photo was added to the app. */
    val addedAt: Long = System.currentTimeMillis(),
    /** Unused: memo linking ended up needing a many-to-many relationship (one photo can be
     * linked from several memos), so it's tracked in [PhotoMemoLink] instead. Column is kept
     * rather than dropped, to avoid an unnecessary destructive-shaped migration for no gain. */
    val eventId: Long? = null,
    /** Epoch millis for the start of the calendar day this photo is linked to, or null. */
    val linkedDate: Long? = null,
    /** Google Driveへのアップロード状態。既存行(この列追加前に保存された写真)は
     * マイグレーションでPENDINGになるが、自動で再送はされない — 新規追加分のみ
     * 追加直後にアップロードを試みる。 */
    val driveSyncStatus: String = DRIVE_SYNC_PENDING,
    /** アップロード成功後のDrive側ファイルID。再アップロード防止に使う。 */
    val driveFileId: String? = null,
    /** 論理削除(tombstone)のタイムスタンプ、未削除ならnull。「×」削除はこのフィールドを
     * 立てるだけで、ローカルファイル・Google Drive原本のどちらも物理的には削除しない
     * — 一覧系クエリはこれがnullの行だけを返す。夫婦2台間ではこの削除状態自体を
     * [com.mspg.poicat.room.RoomDriveTombstoneSync]で共有する。 */
    val deletedAt: Long? = null,
) {
    companion object {
        const val DRIVE_SYNC_PENDING = "PENDING"
        const val DRIVE_SYNC_SYNCING = "SYNCING"
        const val DRIVE_SYNC_SYNCED = "SYNCED"
        const val DRIVE_SYNC_FAILED = "FAILED"
    }
}
