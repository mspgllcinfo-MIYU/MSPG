package com.mspg.poicat.ocr

import android.content.Context
import java.io.File

/**
 * #POI画像OCR: 画像→OCR→内容解析までを1つの関数呼び出しでまとめる、確認画面用の
 * 結果データと処理の入口。まだCatEvent/Photoは一切作らない — ここで返す
 * [Result.tempFile]は[com.mspg.poicat.data.PhotoRepository.copyUriToTempFile]が
 * 作った一時コピーで、正式なPhoto行はまだ存在しない。
 */
data class OcrIntakeResult(
    val tempFile: File,
    val ocrText: String,
    val suggestedTitle: String?,
    val suggestedDateTime: Long?,
    val suggestedLocationText: String?,
)

object OcrIntake {
    /**
     * まず[TravelDocumentParser]でOCR原文から機械的に確実な範囲だけを抽出する。
     * titleまで組み立てられた場合はそれを採用し、Gemini(画像は送らずテキストのみ)は
     * 呼ばない。titleが組み立てられなかった場合にだけ[OcrGeminiAnalyzer]を試し、
     * 失敗・未設定・レート制限時は単に空の解析結果のまま進む — OCR原文自体は
     * どちらの場合も必ず保持され、確認画面でユーザーが読める。
     */
    suspend fun analyze(context: Context, tempFile: File): OcrIntakeResult {
        val ocrText = ImageTextRecognizer.recognize(context, tempFile)
        val local = TravelDocumentParser.parse(ocrText)
        val resolved = if (local.title == null && ocrText.isNotBlank()) {
            OcrGeminiAnalyzer.analyze(ocrText) ?: local
        } else {
            local
        }
        return OcrIntakeResult(
            tempFile = tempFile,
            ocrText = ocrText,
            suggestedTitle = resolved.title,
            suggestedDateTime = resolved.dateTime,
            suggestedLocationText = resolved.locationText,
        )
    }
}
