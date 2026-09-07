package com.mspg.poicat

data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long,
)

enum class ChatRoom(val label: String, val fileSuffix: String) {
    WORK("仕事", "work"),
    PRIVATE("プライベート", "private"),
    CASUAL("雑談", "casual"),
}
