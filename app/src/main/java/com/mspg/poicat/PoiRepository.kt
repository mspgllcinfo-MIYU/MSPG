package com.mspg.poicat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * POIで送った内容の単一の保管場所（Phase 3時点ではローカルファイルへの保存のみ）。
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
    private const val FILE_NAME = "poi_items.json"

    private val _items = mutableStateListOf<PoiItem>()
    val items: List<PoiItem> get() = _items

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

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
        persist()
    }

    fun markRead(id: String) {
        val index = _items.indexOfFirst { it.id == id }
        if (index >= 0) {
            _items[index] = _items[index].copy(isRead = true)
            persist()
        }
    }

    fun delete(id: String) {
        _items.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray()
        _items.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("draft", item.draft.toJson())
            obj.put("senderLabel", item.senderLabel)
            obj.put("timestamp", item.timestamp)
            obj.put("isRead", item.isRead)
            array.put(obj)
        }
        LocalJsonStore.write(context, FILE_NAME, array.toString())
    }

    private fun load() {
        val context = appContext ?: return
        val json = LocalJsonStore.read(context, FILE_NAME) ?: return
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val draft = poiDraftFromJson(obj.getJSONObject("draft")) ?: continue
                _items.add(
                    PoiItem(
                        id = obj.getString("id"),
                        draft = draft,
                        senderLabel = obj.getString("senderLabel"),
                        timestamp = obj.getLong("timestamp"),
                        isRead = obj.getBoolean("isRead"),
                    ),
                )
            }
        }
    }
}

private fun PoiDraft.toJson(): JSONObject {
    val obj = JSONObject()
    when (this) {
        is PoiDraft.Photo -> {
            obj.put("type", "photo")
            obj.put("uri", uri.toString())
        }
        is PoiDraft.MemoText -> {
            obj.put("type", "memo")
            obj.put("text", text)
        }
        is PoiDraft.Link -> {
            obj.put("type", "link")
            obj.put("url", url)
        }
        is PoiDraft.FileDoc -> {
            obj.put("type", "file")
            obj.put("uri", uri.toString())
            obj.put("name", name)
        }
    }
    return obj
}

private fun poiDraftFromJson(obj: JSONObject): PoiDraft? = when (obj.optString("type")) {
    "photo" -> PoiDraft.Photo(Uri.parse(obj.getString("uri")))
    "memo" -> PoiDraft.MemoText(obj.getString("text"))
    "link" -> PoiDraft.Link(obj.getString("url"))
    "file" -> PoiDraft.FileDoc(Uri.parse(obj.getString("uri")), obj.getString("name"))
    else -> null
}
