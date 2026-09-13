package com.mspg.poicat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * #148 Maps-1B: [PendingNavigation]と全く同じ「Composeツリーの外(ここでは
 * ShareIntentHandler)から一度だけ状態を渡す」パターンで、Android標準の
 * 共有(ACTION_SEND)経由で届いた「地図/場所の共有らしいテキスト」を
 * AppRootの確認ダイアログへ橋渡しする。
 *
 * 保持するのは共有された生のテキストだけ — ここでは一切DBへ書き込まない
 * (CatEventもメモも作らない)。実際に何をするか(Googleマップで開く/メモに
 * 保存する/キャンセルする)は、AppRootが表示する確認ダイアログでユーザーが
 * 明示的に選択した結果としてのみ実行される。AppRootが消費した後は
 * [PendingNavigation]同様nullへ戻し、同じ共有が再度処理されないようにする。
 */
object PendingSharedLocation {
    var pending by mutableStateOf<String?>(null)
}
