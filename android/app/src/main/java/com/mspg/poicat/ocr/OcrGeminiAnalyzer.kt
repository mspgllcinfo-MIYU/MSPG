package com.mspg.poicat.ocr

import android.util.Log
import com.mspg.poicat.BuildConfig
import com.mspg.poicat.brain.toEpochMilli
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "PoiOcrGemini"

/**
 * #POI画像OCR: [TravelDocumentParser]だけではタイトルまで組み立てられなかった場合に
 * だけ呼ばれる、OCR原文(テキストのみ)の構造化解析補助。画像そのものはここでも一切
 * 送信しない。
 *
 * [com.mspg.poicat.gemini.GeminiSearchService]と同じ方針(新規SDK依存を増やさず、
 * HttpURLConnection + org.jsonの素のREST呼び出しのみ)・同じモデル・同じ
 * BuildConfig.GEMINI_API_KEY・同じ429ハンドリングを踏襲する、マリたんとは無関係な
 * POI本体機能専用の別の呼び出し口 — 既存のGeminiSearchService/GeminiWorkJudgeの
 * コードは一切変更しない。
 *
 * 失敗・レート制限(429)・APIキー未設定・JSON解析失敗など、どの場合も単にnullを
 * 返すだけ — 呼び出し元([OcrIntake])はOCR原文をそのまま確認画面へ渡し、ユーザーの
 * 手動入力に委ねる。自動的に有料枠へ移行することはしない。
 */
object OcrGeminiAnalyzer {
    private const val MODEL = "gemini-3.5-flash-lite"
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    suspend fun analyze(ocrText: String): ParsedTravelInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = BuildConfig.GEMINI_API_KEY.trim()
            if (apiKey.isBlank()) return@runCatching null

            val requestBody = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", buildPrompt(ocrText))))
                        },
                    ),
                )
            }

            val url = "$API_BASE/$MODEL:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.doOutput = true
                connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                val ok = responseCode in 200..299
                val stream = if (ok) connection.inputStream else connection.errorStream
                val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }
                    ?: "(no response body)"

                if (!ok) {
                    Log.w(TAG, "Gemini API error $responseCode: $text")
                    return@runCatching null
                }

                parseModelOutput(extractAnswerText(text))
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun buildPrompt(ocrText: String): String =
        "以下は、スマートフォンの画像から端末内OCRで読み取った、航空券・新幹線・" +
            "ホテル予約・イベントチケット・病院予約票・予約確認画面等の原文です。\n" +
            "この原文に明確に書かれている情報だけを使い、次のJSON形式で1行だけ" +
            "答えてください。前後に説明文やコードブロックの記号(```)を付けないで" +
            "ください。\n" +
            "書かれていない項目は絶対に推測せず、必ずnullにしてください。\n" +
            "{\"title\": \"短いタイトルまたはnull\", \"date\": \"YYYY-MM-DD形式または" +
            "null\", \"time\": \"HH:MM形式またはnull\", \"location\": \"場所を表す" +
            "短い文字列またはnull\"}\n\n" +
            "原文:\n$ocrText"

    private fun extractAnswerText(responseJson: String): String {
        val candidates = JSONObject(responseJson).optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return (0 until parts.length())
            .mapNotNull { i -> parts.getJSONObject(i).let { if (it.has("text")) it.getString("text") else null } }
            .joinToString("")
    }

    /** モデルの返答から最初の`{`〜最後の`}`だけを取り出してJSONとして解析する —
     * 指示通りJSONのみを返さず前後に説明文を付けてくることがあるため、寛容に扱う。 */
    private fun parseModelOutput(rawText: String): ParsedTravelInfo? {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        val json = runCatching { JSONObject(rawText.substring(start, end + 1)) }.getOrNull() ?: return null

        fun fieldOrNull(key: String): String? =
            json.optString(key, null)?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

        val title = fieldOrNull("title")
        val location = fieldOrNull("location")
        val dateStr = fieldOrNull("date")
        val timeStr = fieldOrNull("time")
        val dateTime = if (dateStr != null) {
            runCatching {
                val date = LocalDate.parse(dateStr)
                val time = timeStr?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)
                LocalDateTime.of(date, time)
            }.getOrNull()?.toEpochMilli()
        } else {
            null
        }

        if (title == null && dateTime == null && location == null) return null
        return ParsedTravelInfo(title = title, dateTime = dateTime, locationText = location)
    }
}
