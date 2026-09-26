package com.mspg.poicat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mspg.poicat.ocr.OcrIntakeResult

/**
 * #POI画像OCR: [PendingSharedLocation]/[PendingNavigation]と全く同じ「Composeツリーの
 * 外(ここではShareIntentHandler)から一度だけ状態を渡す」パターン。ここではまだ
 * Photo/CatEventを一切作らない — [OcrIntakeResult.tempFile]はOCR用の一時コピーで、
 * まだPhoto DBへの行は存在しない。実際にPhoto行を作る/CatEventを登録するのは、
 * AppRootが表示する確認ダイアログ([OcrConfirmDialog])でユーザーが明示的にOKした
 * 場合だけ。キャンセル時は呼び出し元(AppRoot)が[OcrIntakeResult.tempFile]を削除し、
 * pendingをnullへ戻すだけで、POIの正式データには何も残らない。
 */
object PendingOcrImage {
    var pending by mutableStateOf<OcrIntakeResult?>(null)
}
