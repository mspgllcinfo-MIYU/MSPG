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

// 100点仕様: 猫AIの会話画面は1つに統合され、ユーザーがこれを選ぶ場面はもうない。
// 旧バージョン（仕事/プライベート/雑談の3部屋）で保存された履歴ファイルを一度だけ
// 読み込んで1つの会話へ統合するため、ChatRepository内部の移行処理だけがこの enum を
// 使い続ける。
enum class ChatRoom(val label: String, val fileSuffix: String) {
    WORK("仕事", "work"),
    PRIVATE("プライベート", "private"),
    CASUAL("雑談", "casual"),
}
