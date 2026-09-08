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
    /** Id of a linked cat_events row (a schedule or a memo) — for a later phase, unused for now. */
    val eventId: Long? = null,
    /** Epoch millis for the start of the calendar day this photo is linked to, or null. */
    val linkedDate: Long? = null,
)
