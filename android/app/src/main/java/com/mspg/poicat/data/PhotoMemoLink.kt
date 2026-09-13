package com.mspg.poicat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A many-to-many link between one [Photo] and one `cat_events` row (a memo,
 * in practice — but just an id, since this database can't hold a real
 * foreign key into the separate `cat_events` database). One photo can be
 * linked from several memos, and one memo can have several photos; nothing
 * is ever duplicated, this table only records which pairs are linked.
 */
@Entity(tableName = "photo_memo_links")
data class PhotoMemoLink(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val photoId: Long,
    val eventId: Long,
    val linkedAt: Long = System.currentTimeMillis(),
)
