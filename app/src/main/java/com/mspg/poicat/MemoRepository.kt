package com.mspg.poicat

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import java.time.LocalDate
import java.util.UUID

/** Ver.1では「自分だけ / 2人共有」は選択項目のみ。実際の共有同期はFirebase導入後。 */
data class PetitMemo(
    val id: String,
    val date: LocalDate,
    val text: String,
    val photoUris: List<Uri>,
    val shared: Boolean,
    val createdAt: Long,
)

object MemoRepository {
    private val _memos = mutableStateListOf<PetitMemo>()
    val memos: List<PetitMemo> get() = _memos

    fun addMemo(date: LocalDate, text: String, photoUris: List<Uri>, shared: Boolean) {
        _memos.add(
            0,
            PetitMemo(
                id = UUID.randomUUID().toString(),
                date = date,
                text = text.trim(),
                photoUris = photoUris,
                shared = shared,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun memosOn(date: LocalDate): List<PetitMemo> = _memos.filter { it.date == date }
}
