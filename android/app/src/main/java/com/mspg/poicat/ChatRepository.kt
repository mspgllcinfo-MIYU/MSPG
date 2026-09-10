package com.mspg.poicat

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject

// 100点仕様: 猫AIの会話は1画面に統合された。以前は仕事/プライベート/雑談ごとに
// 別ファイル（chat_work.json等）へ保存していたが、今は単一の chat_all.json へ
// 時系列で保存する。既存の3ファイルが残っている場合（アップデート後の初回起動）は、
// それらを一度だけ読み込んでタイムスタンプ順にマージし、chat_all.json として
// 書き出す — 元の3ファイルは削除せずそのまま残す（安全側に倒す。二度と読まれない
// だけで、何かの理由で必要になっても消えていない）。
object ChatRepository {
    private var appContext: Context? = null
    private val messages: SnapshotStateList<ChatMessage> = mutableStateListOf()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    fun messages(): List<ChatMessage> = messages

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        persist()
    }

    private const val MERGED_FILE_NAME = "chat_all.json"

    private fun legacyFileName(room: ChatRoom) = "chat_${room.fileSuffix}.json"

    private fun persist() {
        val context = appContext ?: return
        LocalJsonStore.write(context, MERGED_FILE_NAME, serialize(messages))
    }

    private fun serialize(list: List<ChatMessage>): String {
        val array = JSONArray()
        list.forEach { msg ->
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("text", msg.text)
            obj.put("timestamp", msg.timestamp)
            if (msg.photoIds.isNotEmpty()) {
                obj.put("photoIds", JSONArray(msg.photoIds))
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parse(json: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                // photoIds is a newer field — absent on chat history saved before this
                // feature existed, so default to no photos rather than failing to load.
                val photoIdsArray = obj.optJSONArray("photoIds")
                val photoIds = if (photoIdsArray != null) {
                    List(photoIdsArray.length()) { photoIdsArray.getLong(it) }
                } else {
                    emptyList()
                }
                result.add(
                    ChatMessage(
                        role = obj.getString("role"),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                        photoIds = photoIds,
                    )
                )
            }
        }
        return result
    }

    private fun load() {
        val context = appContext ?: return
        val mergedJson = LocalJsonStore.read(context, MERGED_FILE_NAME)
        if (mergedJson != null) {
            messages.addAll(parse(mergedJson))
            return
        }

        // First launch after the 1画面統合 update: merge whatever legacy per-room
        // files exist (any may be absent — a fresh install has none at all) into
        // one timeline, then persist it as the new canonical store so this
        // migration only ever runs once.
        val merged = ChatRoom.entries
            .mapNotNull { room -> LocalJsonStore.read(context, legacyFileName(room)) }
            .flatMap { parse(it) }
            .sortedBy { it.timestamp }
        if (merged.isNotEmpty()) {
            messages.addAll(merged)
            persist()
        }
    }
}
