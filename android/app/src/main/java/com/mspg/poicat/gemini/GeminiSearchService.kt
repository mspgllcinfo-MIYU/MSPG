package com.mspg.poicat.gemini

import android.util.Log
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

private const val TAG = "MariTanGemini"

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
 *
 * Version 1ではGoogle Search Grounding(tools.google_search)を使わない: 実機と
 * GitHub Actions上でのA/Bテストで、全く同じキー/モデル/エンドポイントでも
 * grounding無し=HTTP 200成功、grounding有り=HTTP 429(RESOURCE_EXHAUSTED)が
 * 再現し、grounding機能自体の無料枠がこのプロジェクトでは極端に少ないことが
 * 確定した。マリたんの最優先要件は「無料でできるだけ長く・多く会話できること」
 * のため、Version 1では通常のテキスト生成のみを使い、天気/最新ニュース等の
 * リアルタイム検索が要る質問への対応はVersion 2で別途検討する。
 */
object GeminiSearchService {
    // モデル選定方針: マリたんの最優先要件は「性能」ではなく「できるだけ長時間・
    // 多くの回数を無料で会話できること」。ユーザーがGoogle AI Studioの
    // 「Gemini API のレート制限」画面(Project: MIYUxAI、無料枠)で実際に確認した
    // 値では、gemini-3.6-flashの無料枠は5 RPMだったのに対し、
    // gemini-3.5-flash-liteは15 RPM — 同じFlash系列の中で最も無料枠が大きい
    // （軽量="Lite"な分、上限が緩い）。通常会話・一般的な質問には十分な性能と
    // 判断し、無料での会話可能回数を最優先してこちらを使う。将来また変わった
    // 場合はこの定数だけを差し替えれば良い。
    private const val MODEL = "gemini-3.5-flash-lite"
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val SYSTEM_INSTRUCTION =
        "あなたは「マリたん」という知的で好奇心旺盛なキジ猫です。ユーザーの質問に対して" +
            "簡潔に（3〜4文程度まで）日本語で、猫らしい親しみやすい口調で答えてください。"

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
                // Version 1ではGoogle Search Grounding(tools.google_search)を使わない
                // — grounding機能自体の無料枠が別枠かつ極端に少なく、A/Bテストで
                // 429の直接原因と確定したため。クラスコメント参照。
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
                    if (responseCode == 429) return@runCatching GeminiOutcome.QuotaExceeded
                    // 一般ユーザー画面には出さないが、実機調査が要る場合はlogcat
                    // (タグ: MariTanGemini)で追える。
                    Log.w(TAG, "Gemini API error $responseCode: $text")
                    error("Gemini API error $responseCode")
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
