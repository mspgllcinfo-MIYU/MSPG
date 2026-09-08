package com.mspg.poicat

data class ChatMessage(
    val role: String,
    val text: String,
    val timestamp: Long,
    /**
     * Photo ids attached to this message: either what the cat AI found for a photo
     * search reply, or (Phase B) a photo the user picked and sent — empty otherwise.
     */
    val photoIds: List<Long> = emptyList(),
)

enum class ChatRoom(val label: String, val fileSuffix: String) {
    WORK("仕事", "work"),
    PRIVATE("プライベート", "private"),
    CASUAL("雑談", "casual"),
}
