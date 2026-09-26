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
    /** キャプション/アルバム名/カレンダー日付編集の競合解決用タイムスタンプ
     * (CatEvent.updatedAtと同じ考え方のlast-write-wins)。夫婦間で共有中(driveFileId
     * あり)の写真の編集内容を[com.mspg.poicat.room.RoomPhotoMetadataSync]で同期する
     * ために使う。既存行はmigrationで0になる — 0はどんな実際の編集時刻よりも必ず
     * 古い扱いになるだけで、実害はない。 */
    val metadataUpdatedAt: Long = 0,
    /** #POI画像OCR: この写真から端末内OCRで読み取った生テキスト(照合用の原文)。
     * captionのような編集UIは無く、作成時に一度だけ設定され、以後変更されない
     * (ユーザーが手動修正するのはCatEvent側のタイトル/日時/場所であり、この原文
     * 自体は書き換えない)。既存(この列追加前)の写真は全てnullのまま残る — 未読取
     * の実在の状態であり、後から自動でOCRし直すことはしない。 */
    val ocrText: String? = null,
) {
    companion object {
        const val DRIVE_SYNC_PENDING = "PENDING"
        const val DRIVE_SYNC_SYNCING = "SYNCING"
        const val DRIVE_SYNC_SYNCED = "SYNCED"
        const val DRIVE_SYNC_FAILED = "FAILED"
    }
}
