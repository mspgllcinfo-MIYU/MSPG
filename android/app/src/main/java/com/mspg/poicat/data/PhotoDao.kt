package com.mspg.poicat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: Photo): Long

    @Update
    suspend fun update(photo: Photo)

    @Delete
    suspend fun delete(photo: Photo)

    /** 「×」削除(論理削除)専用 — idを条件にdeletedAtカラムだけをUPDATEする、ピンポイント
     * な書き込み。呼び出し元がUI側で保持している(バックグラウンドのDriveアップロード
     * 完了より前の可能性がある)古いPhotoスナップショットを経由しないため、driveFileId
     * やその他のフィールドを誤って古い値へ巻き戻すことがない。 */
    @Query("UPDATE photos SET deletedAt = :deletedAt WHERE id = :photoId")
    suspend fun markDeleted(photoId: Long, deletedAt: Long)

    /** キャプション/アルバム名/カレンダー日付編集専用 — idを条件にこの3つと
     * metadataUpdatedAtだけをUPDATEする、ピンポイントな書き込み。[markDeleted]と同じ
     * 理由で、UI側の古いPhotoスナップショット全体を書き戻すことはしない — driveFileId・
     * driveSyncStatus・deletedAt等の他フィールドを一切巻き戻さない。 */
    @Query(
        "UPDATE photos SET caption = :caption, albumName = :albumName, linkedDate = :linkedDate, " +
            "metadataUpdatedAt = :metadataUpdatedAt WHERE id = :photoId",
    )
    suspend fun updateMetadata(photoId: Long, caption: String?, albumName: String?, linkedDate: Long?, metadataUpdatedAt: Long)

    /** All photos, newest-added first. 論理削除済み(deletedAt != null)の行は除外する —
     * 「×」削除はこのdeletedAtを立てるだけの論理削除なので、一覧系クエリは全て
     * deletedAt IS NULLで揃える。 */
    @Query("SELECT * FROM photos WHERE deletedAt IS NULL ORDER BY addedAt DESC")
    suspend fun all(): List<Photo>

    @Query("SELECT * FROM photos WHERE albumName = :album AND deletedAt IS NULL ORDER BY addedAt DESC")
    suspend fun byAlbum(album: String): List<Photo>

    /** Photos linked to a specific calendar day (see [Photo.linkedDate]). */
    @Query("SELECT * FROM photos WHERE linkedDate BETWEEN :startOfDay AND :endOfDay AND deletedAt IS NULL ORDER BY addedAt DESC")
    suspend fun byLinkedDate(startOfDay: Long, endOfDay: Long): List<Photo>

    /** Batch fetch for resolving a memo's linked photo ids (see [PhotoMemoLinkDao]) to rows, and
     * for internal Drive-sync-status bookkeeping. Deliberately NOT filtered by deletedAt (see
     * [StoredFileDao.byIds]) — softDelete already removes a photo's memo links, so this never
     * needs to filter a soft-deleted row out for the memo-photo-strip use, and the bookkeeping
     * use needs to find the row regardless of its deleted state. */
    @Query("SELECT * FROM photos WHERE id IN (:ids) ORDER BY addedAt DESC")
    suspend fun byIds(ids: List<Long>): List<Photo>

    /** Looks up a photo already known by its Drive file id — used by the room-share catalog
     * refresh to skip a Drive file this device already has a local row for. Deliberately NOT
     * filtered by deletedAt: a soft-deleted row still counts as "already have this file" so it
     * doesn't get silently re-downloaded and reappear. */
    @Query("SELECT * FROM photos WHERE driveFileId = :driveFileId LIMIT 1")
    suspend fun byDriveFileId(driveFileId: String): Photo?

    /** Distinct album names in use, for the filter chips / picker. */
    @Query("SELECT DISTINCT albumName FROM photos WHERE albumName IS NOT NULL AND albumName != '' AND deletedAt IS NULL ORDER BY albumName ASC")
    suspend fun albumNames(): List<String>

    /** Photos added or (if set) linked to a calendar day within the range — used by cat AI photo search. */
    @Query("SELECT * FROM photos WHERE addedAt BETWEEN :start AND :end AND deletedAt IS NULL ORDER BY addedAt DESC")
    suspend fun byAddedAtRange(start: Long, end: Long): List<Photo>

    /** Photos whose caption or album name contains the keyword — used by cat AI photo search. */
    @Query(
        "SELECT * FROM photos WHERE (caption LIKE '%' || :keyword || '%' OR albumName LIKE '%' || :keyword || '%') " +
            "AND deletedAt IS NULL ORDER BY addedAt DESC",
    )
    suspend fun searchByCaptionOrAlbum(keyword: String): List<Photo>
}
