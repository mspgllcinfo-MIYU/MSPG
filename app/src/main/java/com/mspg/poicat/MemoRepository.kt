package com.mspg.poicat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
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
    private const val FILE_NAME = "memos.json"

    private val _memos = mutableStateListOf<PetitMemo>()
    val memos: List<PetitMemo> get() = _memos

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

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
        persist()
    }

    fun memosOn(date: LocalDate): List<PetitMemo> = _memos.filter { it.date == date }

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray()
        _memos.forEach { memo ->
            val obj = JSONObject()
            obj.put("id", memo.id)
            obj.put("date", memo.date.toString())
            obj.put("text", memo.text)
            val uriArray = JSONArray()
            memo.photoUris.forEach { uriArray.put(it.toString()) }
            obj.put("photoUris", uriArray)
            obj.put("createdAt", memo.createdAt)
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
                val uriArray = obj.getJSONArray("photoUris")
                val uris = (0 until uriArray.length()).map { Uri.parse(uriArray.getString(it)) }
                _memos.add(
                    PetitMemo(
                        id = obj.getString("id"),
                        date = LocalDate.parse(obj.getString("date")),
                        text = obj.getString("text"),
                        photoUris = uris,
                        createdAt = obj.getLong("createdAt"),
                    ),
                )
            }
        }
    }
}
