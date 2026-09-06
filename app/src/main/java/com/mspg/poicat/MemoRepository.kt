package com.mspg.poicat

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import java.time.LocalDate
import java.util.UUID

/** Ver.1ではプチメモは常に2人共有（「自分だけ」の選択肢はない）。 */
data class PetitMemo(
    val id: String,
    val date: LocalDate,
    val text: String,
    val photoUris: List<Uri>,
    val createdAt: Long,
)

object MemoRepository {
    private val _memos = mutableStateListOf<PetitMemo>()
    val memos: List<PetitMemo> get() = _memos

    fun addMemo(date: LocalDate, text: String, photoUris: List<Uri>) {
        _memos.add(
            0,
            PetitMemo(
                id = UUID.randomUUID().toString(),
                date = date,
                text = text.trim(),
                photoUris = photoUris,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun memosOn(date: LocalDate): List<PetitMemo> = _memos.filter { it.date == date }
}
