package com.mspg.poicat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface StoredFileDao {
    @Insert
    suspend fun insert(file: StoredFile): Long

    @Delete
    suspend fun delete(file: StoredFile)

    /** All files, newest-saved first. */
    @Query("SELECT * FROM stored_files ORDER BY savedAt DESC")
    suspend fun all(): List<StoredFile>
}
