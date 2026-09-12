package com.mspg.poicat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface StoredFileDao {
    @Insert
    suspend fun insert(file: StoredFile): Long

    @Update
    suspend fun update(file: StoredFile)

    @Delete
    suspend fun delete(file: StoredFile)

    /** All files, newest-saved first. 論理削除済み(deletedAt != null)の行は除外する。 */
    @Query("SELECT * FROM stored_files WHERE deletedAt IS NULL ORDER BY savedAt DESC")
    suspend fun all(): List<StoredFile>

    /** Batch fetch by id — used to re-read a single file's current row before updating it.
     * Deliberately NOT filtered by deletedAt: this is a read-modify-write lookup (e.g. to mark
     * a Drive sync status), not a user-facing list, so it must still find a soft-deleted row. */
    @Query("SELECT * FROM stored_files WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<StoredFile>

    /** Looks up a file already known by its Drive file id — used by the room-share catalog
     * refresh to skip a Drive file this device already has a local row for. Deliberately NOT
     * filtered by deletedAt: a soft-deleted row still counts as "already have this file" so it
     * doesn't get silently re-downloaded and reappear. */
    @Query("SELECT * FROM stored_files WHERE driveFileId = :driveFileId LIMIT 1")
    suspend fun byDriveFileId(driveFileId: String): StoredFile?
}
