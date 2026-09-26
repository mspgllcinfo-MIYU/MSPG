package com.mspg.poicat.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import kotlinx.coroutines.tasks.await

/**
 * #POI画像OCR: Google ML Kit Text Recognition(日本語モデル)による端末内OCRの薄い
 * ラッパー。画像バイトは一切端末外(Google/Gemini含む)へ送信しない — 認識処理自体は
 * 完全にオンデバイスで行われる(日本語モデル自体の初回取得だけPlay Services経由の
 * ネットワークが必要)。
 *
 * 認識できない・失敗した場合は空文字列を返すだけで、例外を呼び出し元へ伝播させない
 * — OCR失敗はユーザー確認画面への到達を妨げるべきではなく、空のOCR原文のまま
 * 確認画面へ進み、ユーザーが手動でタイトル/日時等を入力できるようにする。
 */
object ImageTextRecognizer {
    suspend fun recognize(context: Context, imageFile: File): String {
        return runCatching {
            val image = InputImage.fromFilePath(context, Uri.fromFile(imageFile))
            val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            recognizer.process(image).await().text
        }.getOrDefault("")
    }
}
