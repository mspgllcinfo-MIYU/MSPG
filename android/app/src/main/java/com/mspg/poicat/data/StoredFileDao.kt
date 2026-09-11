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

    /** All files, newest-saved first. */
    @Query("SELECT * FROM stored_files ORDER BY savedAt DESC")
    suspend fun all(): List<StoredFile>

    /** Batch fetch by id — used to re-read a single file's current row before updating it. */
    @Query("SELECT * FROM stored_files WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<StoredFile>
}
