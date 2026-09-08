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

    /** All photos, newest-added first. */
    @Query("SELECT * FROM photos ORDER BY addedAt DESC")
    suspend fun all(): List<Photo>

    @Query("SELECT * FROM photos WHERE albumName = :album ORDER BY addedAt DESC")
    suspend fun byAlbum(album: String): List<Photo>

    /** Distinct album names in use, for the filter chips / picker. */
    @Query("SELECT DISTINCT albumName FROM photos WHERE albumName IS NOT NULL AND albumName != '' ORDER BY albumName ASC")
    suspend fun albumNames(): List<String>

    /** Photos linked to one cat_events row (a schedule or a memo) — used from a later phase. */
    @Query("SELECT * FROM photos WHERE eventId = :eventId ORDER BY addedAt DESC")
    suspend fun byEvent(eventId: Long): List<Photo>
}
