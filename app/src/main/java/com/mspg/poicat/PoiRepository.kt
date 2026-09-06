package com.mspg.poicat

import androidx.compose.runtime.mutableStateListOf
import java.util.UUID

/**
 * POIで送った内容の単一の保管場所（Phase 3時点ではローカルの送信履歴のみ）。
 * Firebase導入後はここをFirestore連携の実装に差し替える想定で、
 * HomeScreen/PoiScreen/BoxScreenはこのAPI（items/addSent/markRead/delete）だけに依存する。
 */
data class PoiItem(
    val id: String,
    val draft: PoiDraft,
    val senderLabel: String,
    val timestamp: Long,
    val isRead: Boolean,
)

object PoiRepository {
    private val _items = mutableStateListOf<PoiItem>()
    val items: List<PoiItem> get() = _items

    fun addSent(draft: PoiDraft) {
        _items.add(
            0,
            PoiItem(
                id = UUID.randomUUID().toString(),
                draft = draft,
                senderLabel = "自分（送信・仮データ）",
                timestamp = System.currentTimeMillis(),
                isRead = false,
            ),
        )
    }

    fun markRead(id: String) {
        val index = _items.indexOfFirst { it.id == id }
        if (index >= 0) _items[index] = _items[index].copy(isRead = true)
    }

    fun delete(id: String) {
        _items.removeAll { it.id == id }
    }
}
