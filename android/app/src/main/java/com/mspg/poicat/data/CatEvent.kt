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
     * 担当ラベル（[ASSIGNEE_MIYU]/[ASSIGNEE_KATCHAN]/[ASSIGNEE_BOTH]、または
     * 未設定のnull）。元々は仕事タスク（[isTask]かつ[category] ==
     * [CATEGORY_WORK]）専用として追加したが（#142）、#147で予定（[isTask] ==
     * false かつ[dateTime]あり）にもそのまま流用している — DBスキーマ・
     * Firestore構造はどちらも変更していない。表示や同期を担当別に絞るための
     * ものではない — どの値でも、この行はみゆたん・かっちゃん双方の端末に
     * 同じ1件として表示・同期され続ける。nullは「未設定」という実在の状態
     * であり、既存の行を黙って「2人」等へ書き換えることはしない。
     */
    val assignee: String? = null,
    /**
     * #148 フェーズ1: [isTask] == false かつ[dateTime]ありの予定を、正本は
     * 予定のまま保持しつつ、仕事/プラベタスク画面にも同時に表示するための
     * 独立フラグ。[isTask]自体の意味は一切変えない — 「予定を2重登録せず、
     * 1つの正本データを両方のビューから見る」という設計方針そのもの。
     *
     * - isTask=true → 従来通りの通常タスク（このフラグは無関係）。
     * - isTask=false かつ dateTimeあり かつ alsoShowAsTask=false →
     *   従来通りの通常予定（カレンダーのみ）。
     * - isTask=false かつ dateTimeあり かつ alsoShowAsTask=true →
     *   予定を正本としながらタスクビューにも表示するハイブリッド予定。
     *
     * 独立した2件目のCatEventを作る方式は採用しない — 編集・削除・担当
     * 変更・完了状態が2行の間でズレる危険を避けるため。既存の全ての行は
     * 追加のMigrationでfalseのまま残り、この値を自動的にtrueへ書き換える
     * 処理は無い（フェーズ1時点ではUI/AI判定どちらも未実装のため、実際に
     * trueになる経路自体がまだ存在しない）。
     */
    val alsoShowAsTask: Boolean = false,
    /**
     * #148 Maps-2A: 共有(Google Maps/Gemini等からのAndroid標準共有)または
     * 将来の入力経路で紐付けられた場所を、加工せず生のテキストのまま保持
     * する基盤フィールド。POI自身は住所解決・座標変換を一切行わない
     * ([com.mspg.poicat.maps.SharedLocationDetector]/[com.mspg.poicat.maps.MapsLauncher]
     * 参照)ため、施設名・URL(短縮URL含む)等が混在した1本のフリーテキストを
     * そのまま保存する — 表示・再度開く際は、この文字列に対して改めて
     * [com.mspg.poicat.maps.SharedLocationDetector.extractMapsUrl]を呼べば
     * よく、別の構造化フィールド(住所/緯度経度等)は用意しない。
     *
     * 未設定はnull(「場所なし」という実在の状態、[category]/[assignee]と
     * 同じ扱い)。[category]/[assignee]と同様、NOT NULL制約もDEFAULT値も
     * 持たない — 既存の全ての行はこのMigrationでnullのまま残り、値を自動
     * 推測して埋める処理は無い。
     *
     * Maps-2Aの時点ではこのフィールドへ書き込むUI/AI判定はまだ存在しない
     * ([CatEventRepository.setLocation]という保存経路だけを用意する) —
     * 「予定に追加」UI等、実際にこの値を設定する機能は後続のフェーズで
     * 追加する。
     */
    val locationText: String? = null,
) {
    companion object {
        const val CATEGORY_WORK = "work"
        const val CATEGORY_PRIVATE = "private"

        const val ASSIGNEE_MIYU = "みゆたん"
        const val ASSIGNEE_KATCHAN = "かっちゃん"
        const val ASSIGNEE_BOTH = "2人"
    }
}
