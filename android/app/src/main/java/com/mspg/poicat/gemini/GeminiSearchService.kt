package com.mspg.poicat.gemini

import com.mspg.poicat.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** マリたんが1回の質問に対してGeminiから受け取った結果。 */
sealed class GeminiOutcome {
    data class Answer(val text: String) : GeminiOutcome()

    /** 無料枠を使い切った(HTTP 429)。マリたんを「ふて寝」状態にする合図 — 自動的に
     * 有料機能へ移行することは絶対にしない。 */
    object QuotaExceeded : GeminiOutcome()

    /** APIキーが未設定(BuildConfig.GEMINI_API_KEYが空)。ネットワークには一切出ない。 */
    object NotConfigured : GeminiOutcome()
}

/**
 * マリたん専用のGemini呼び出し窓口。DriveFolderRepositoryと同じ方針で、新規SDK依存
 * (com.google.ai.client.generativeai等)は追加せず、HttpURLConnection + org.jsonの
 * 素のREST呼び出しのみを使う — このプロジェクトは過去にFirebase BOMのKotlin
 * メタデータ非互換でCIが壊れた経験があり、新しいSDKバージョンを増やすこと自体が
 * リスクになるため。
 *
 * 送信する内容は呼び出し元(MariTan UI)が渡した[query]（マリたんのマイクで今回
 * 認識された音声テキストそのもの）だけ — 黒猫AIの会話履歴、仕事/プラベ/タスク/
 * メモ/カレンダー/アルバム/ファイル/Google Drive/ルーム共有データ等、POI内部の
 * 情報は一切含めない。固定のsystem_instruction（簡潔に日本語で答えるよう指示する
 * だけの定型文）以外にAPIへ渡すものはない。
 */
object GeminiSearchService {
    // 2026年時点で現行のFlash系モデル。costとレイテンシのバランスを優先し、
    // Google Search grounding(tools.google_search)に対応したモデルを選んでいる。
    // 将来モデル名が変わった場合はこの定数だけを差し替えれば良い。
    private const val MODEL = "gemini-2.5-flash"
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val SYSTEM_INSTRUCTION =
        "あなたは「マリたん」という知的で好奇心旺盛なキジ猫です。ユーザーの質問について" +
            "Google検索で最新情報を調べ、簡潔に（3〜4文程度まで）日本語で、猫らしい親しみやすい" +
            "口調で答えてください。"

    suspend fun ask(query: String): Result<GeminiOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            // GitHub Actions Secretの値がコピペ等で前後に空白/改行を含んでいた場合に
            // URLが壊れないよう防御的にtrimする。
            val apiKey = BuildConfig.GEMINI_API_KEY.trim()
            if (apiKey.isBlank()) return@runCatching GeminiOutcome.NotConfigured

            val requestBody = JSONObject().apply {
                put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))),
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", query)))
                        },
                    ),
                )
                // Google Search groundingを有効化 — 「今日の天気」等、モデル単体の
                // 知識だけでは答えられない最新情報を調べて持って帰ってくる、マリたんの
                // 役割そのものに必要な機能。
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
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
                // errorStreamがnullになるケース(一部の接続失敗等)でもHTTPコード自体は
                // デバッグ表示に残るよう、本文読み取り失敗はtext側だけで吸収する。
                val stream = if (ok) connection.inputStream else connection.errorStream
                val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }
                    ?: "(no response body)"

                if (!ok) {
                    if (responseCode == 429) return@runCatching GeminiOutcome.QuotaExceeded
                    error("Gemini API error $responseCode: $text")
                }

                GeminiOutcome.Answer(text = extractAnswerText(text))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractAnswerText(responseJson: String): String {
        val candidates = JSONObject(responseJson).optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return "うまく答えを見つけられなかったにゃ"
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val combined = (0 until parts.length())
            .mapNotNull { i ->
                val part = parts.getJSONObject(i)
                if (part.has("text")) part.getString("text") else null
            }
            .joinToString("")
        return combined.ifBlank { "うまく答えを見つけられなかったにゃ" }
    }
}
