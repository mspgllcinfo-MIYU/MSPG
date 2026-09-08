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

    /** Photos linked to a specific calendar day (see [Photo.linkedDate]). */
    @Query("SELECT * FROM photos WHERE linkedDate BETWEEN :startOfDay AND :endOfDay ORDER BY addedAt DESC")
    suspend fun byLinkedDate(startOfDay: Long, endOfDay: Long): List<Photo>

    /** Batch fetch for resolving a memo's linked photo ids (see [PhotoMemoLinkDao]) to rows. */
    @Query("SELECT * FROM photos WHERE id IN (:ids) ORDER BY addedAt DESC")
    suspend fun byIds(ids: List<Long>): List<Photo>

    /** Distinct album names in use, for the filter chips / picker. */
    @Query("SELECT DISTINCT albumName FROM photos WHERE albumName IS NOT NULL AND albumName != '' ORDER BY albumName ASC")
    suspend fun albumNames(): List<String>
}
