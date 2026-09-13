package com.mspg.poicat.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single thing the cat remembers: a schedule with a date+time, a plain
 * memo with no date, or (when [isTask] is set) a to-do item from the Poi
 * screen whose [dateTime] — if any — is its due date rather than a
 * reminder-worthy event time. Stored on-device (Room/SQLite) as the source
 * of truth; if a 4-digit-PIN room has been joined, [roomEventId]/[updatedAt]
 * also let [com.mspg.poicat.room.RoomEventSync] mirror this row to/from
 * Firestore as a best-effort background step — see that class for details.
 * With no room joined, nothing here ever touches the network.
 */
@Entity(tableName = "cat_events")
data class CatEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    /** Epoch millis, or null for a date-less memo/task. */
    val dateTime: Long?,
    val createdAt: Long = System.currentTimeMillis(),
    val reminded1Day: Boolean = false,
    val reminded1Hour: Boolean = false,
    val isTask: Boolean = false,
    val completed: Boolean = false,
    /**
     * Poi task category — [CATEGORY_WORK] or [CATEGORY_PRIVATE], or null when
     * not yet classified (every pre-v3 row, and every non-task schedule/memo
     * row, for which this concept doesn't apply). Null is a real, permanent
     * state, not just a migration artifact — it's how an unclassified task
     * stays re-classifiable later rather than being forced into a guess.
     */
    val category: String? = null,
    /** ルーム共有用のFirestore側ドキュメントID（`rooms/{roomId}/events/{roomEventId}`）。
     * ローカルの[id]は端末ごとに独立したautoIncrementなので共有には使えず、初回の
     * Firestoreプッシュ時にランダムなUUIDとして割り当てられ、以後はこのIDで
     * 同じ行を指し続ける。ルーム未参加、またはこの行がまだ一度もプッシュされて
     * いない間はnull。 */
    val roomEventId: String? = null,
    /** この行が最後に変更された時刻（epoch millis）。ルーム共有時の
     * 「新しい方を勝たせる」競合解決にのみ使う — 通常のローカル専用動作には影響しない。 */
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 仕事タスク（[isTask]かつ[category] == [CATEGORY_WORK]）だけに意味を持つ担当
     * ラベル（[ASSIGNEE_MIYU]/[ASSIGNEE_KATCHAN]/[ASSIGNEE_BOTH]、または未設定の
     * null）。表示や同期を担当別に絞るためのものではない — どの値でも、この
     * タスクはみゆたん・かっちゃん双方の端末に同じ1件として表示・同期され続ける。
     * nullは「未設定」という実在の状態であり、既存タスクを黙って「2人」等へ
     * 書き換えることはしない。
     */
    val assignee: String? = null,
) {
    companion object {
        const val CATEGORY_WORK = "work"
        const val CATEGORY_PRIVATE = "private"

        const val ASSIGNEE_MIYU = "みゆたん"
        const val ASSIGNEE_KATCHAN = "かっちゃん"
        const val ASSIGNEE_BOTH = "2人"
    }
}
