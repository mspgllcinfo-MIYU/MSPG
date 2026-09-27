package com.mspg.poicat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * #POI画像共有3択: [PendingSharedLocation]/[PendingOcrImage]と同じ「Composeツリーの
 * 外(ShareIntentHandler)から一度だけ状態を渡す」パターン。image/*が共有された直後、
 * まだ何も確定保存しない(Photo DB行なし、CatEventなし、チャット履歴なし)状態で、
 * ユーザーが「予定を読み取る/アルバムに保存/猫AIに送る」のどれを選ぶかを待つ。
 *
 * [tempFile]はEXTRA_STREAMのUriから既にコピーした一時ファイル — 共有元Uriの
 * 読み取り権限がユーザーの選択待ちの間に失効しても影響しない(選択後はこの
 * ローカルファイルだけを使う)。[sharedText]は共有Intentに付いていたEXTRA_TEXT
 * (あれば)で、「猫AIに送る」を選んだ場合だけキャプションとして使う —
 * EXTRA_TEXTの有無自体でOCR/アルバム/猫AIのルートを自動判定することはしない。
 *
 * キャンセル時は呼び出し元(AppRoot)が[tempFile]を削除し、pendingをnullへ戻す
 * だけで、POIの正式データには何も残らない。
 */
object PendingImageShareChoice {
    data class Data(val tempFile: File, val sharedText: String?)

    var pending by mutableStateOf<Data?>(null)
}
