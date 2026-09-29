package com.mspg.poicat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PhotoMemoLinkDao {
    @Insert
    suspend fun insert(link: PhotoMemoLink): Long

    @Delete
    suspend fun delete(link: PhotoMemoLink)

    @Query("SELECT * FROM photo_memo_links WHERE eventId = :eventId")
    suspend fun linksForEvent(eventId: Long): List<PhotoMemoLink>

    @Query("SELECT EXISTS(SELECT 1 FROM photo_memo_links WHERE photoId = :photoId AND eventId = :eventId)")
    suspend fun exists(photoId: Long, eventId: Long): Boolean

    @Query("DELETE FROM photo_memo_links WHERE photoId = :photoId AND eventId = :eventId")
    suspend fun deleteLink(photoId: Long, eventId: Long)

    /** Used when a memo is deleted: drops its links without touching the photos themselves. */
    @Query("DELETE FROM photo_memo_links WHERE eventId = :eventId")
    suspend fun deleteAllForEvent(eventId: Long)

    /** Used when a photo is deleted from the album: drops any memo references to it. */
    @Query("DELETE FROM photo_memo_links WHERE photoId = :photoId")
    suspend fun deleteAllForPhoto(photoId: Long)
}
