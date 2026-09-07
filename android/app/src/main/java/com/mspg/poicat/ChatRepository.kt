package com.mspg.poicat

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject

object ChatRepository {
    private var appContext: Context? = null
    private val messagesByRoom: Map<ChatRoom, SnapshotStateList<ChatMessage>> =
        ChatRoom.entries.associateWith { mutableStateListOf() }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        ChatRoom.entries.forEach { load(it) }
    }

    fun messages(room: ChatRoom): List<ChatMessage> = messagesByRoom.getValue(room)

    fun addMessage(room: ChatRoom, message: ChatMessage) {
        messagesByRoom.getValue(room).add(message)
        persist(room)
    }

    private fun fileName(room: ChatRoom) = "chat_${room.fileSuffix}.json"

    private fun persist(room: ChatRoom) {
        val context = appContext ?: return
        val array = JSONArray()
        messagesByRoom.getValue(room).forEach { msg ->
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("text", msg.text)
            obj.put("timestamp", msg.timestamp)
            array.put(obj)
        }
        LocalJsonStore.write(context, fileName(room), array.toString())
    }

    private fun load(room: ChatRoom) {
        val context = appContext ?: return
        val json = LocalJsonStore.read(context, fileName(room)) ?: return
        runCatching {
            val array = JSONArray(json)
            val list = messagesByRoom.getValue(room)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ChatMessage(
                        role = obj.getString("role"),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                    )
                )
            }
        }
    }
}
