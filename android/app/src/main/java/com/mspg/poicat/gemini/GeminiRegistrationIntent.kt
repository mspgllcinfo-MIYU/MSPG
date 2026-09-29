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

private const val TAG = "PoiRegistrationIntent"

/**
 * #148 Phase 3-2: 発話が「そもそも予定登録の意図を持つ発話か」だけを判定する、
 * 判定専用の軽量呼び出し。[GeminiWorkJudge]（登録が確定した*後*に、その内容が
 * 仕事かどうかを判定するもの）とは別の問いであり、意図的に別ファイル・別
 * system_instructionとして分離している — 1回のAI呼び出しに両方の判定を
 * 混ぜない。[GeminiSearchService]（マリたんの人格会話用）とも当然分離している。
 *
 * 送信する内容は呼び出し元([com.mspg.poicat.brain.CatBrain])が渡す
 * [rawInput]（今回発話・入力された文章そのもの）だけ — [GeminiWorkJudge]が
 * 送る「日付語を除去済みのtitle」とは異なり、こちらは日付語を含む発話全体を
 * 送る(「明日」「15日」等の日付語自体が、その後に用件が続いているかどうかの
 * 判断材料になり得るため)。POI内部のDB内容(予定一覧/仕事タスク一覧/メモ/
 * アルバム/ファイル/ルーム情報/過去の履歴)は一切含めない。
 *
 * [GeminiSearchService]/[GeminiWorkJudge]と同じ方針で、新規SDK依存
 * (com.google.ai.client.generativeai等)は追加せず、HttpURLConnection +
 * org.jsonの素のREST呼び出しのみを使う。同じ無料枠・同じAPIキーを再利用
 * する(新しい有料プラン・課金設定は一切追加しない)。
 */
object GeminiRegistrationIntent {
    // GeminiSearchService/GeminiWorkJudgeと同じモデルを使う。無料枠事情も
    // 同一のため、判定専用に別モデルを選ぶ理由がない。
    private const val MODEL = "gemini-3.5-flash-lite"
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    private const val SYSTEM_INSTRUCTION =
        "あなたは、入力された1文が「具体的な予定・用事の登録を意図した発話」か" +
            "どうかだけを判定する分類器です。" +
            "説明や前置きは不要です。次のいずれか1語だけを返してください: " +
            "SCHEDULE, NOT_SCHEDULE, UNKNOWN。" +
            "\n・「明日、銀行」「15日に取引先と打ち合わせ」「来月15日に病院」の" +
            "ように、具体的な予定/用事/行動を登録しようとしている発話だと判断" +
            "できる場合はSCHEDULE。" +
            "\n・「今日は暑いね」「明日は雨かな」「マリたん元気？」のように、" +
            "感想・天気の話・世間話・質問など、予定登録の意図が無いと判断できる" +
            "場合はNOT_SCHEDULE。" +
            "\n・どちらとも判断できない場合はUNKNOWN。" +
            "\nSCHEDULE/NOT_SCHEDULE/UNKNOWN以外の語や、それ以上の説明文は絶対に" +
            "含めないでください。"

    suspend fun judge(rawInput: String): Result<GeminiOutcome> = withContext(Dispatchers.IO) {
        runCatching {
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
                            put("parts", JSONArray().put(JSONObject().put("text", rawInput)))
                        },
                    ),
                )
            }

            val url = "$API_BASE/$MODEL:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                // 予定登録の応答をいつまでも止めないよう、CatBrain側のwithTimeoutOrNull
                // より短めに設定しておく(接続自体が固まった場合の二重の保険)。
                connection.connectTimeout = 6_000
                connection.readTimeout = 6_000
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
                    Log.w(TAG, "Gemini registration-intent error $responseCode: $text")
                    error("Gemini registration-intent error $responseCode")
                }

                GeminiOutcome.Answer(text = extractAnswerText(text))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractAnswerText(responseJson: String): String {
        val candidates = JSONObject(responseJson).optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return (0 until parts.length())
            .mapNotNull { i ->
                val part = parts.getJSONObject(i)
                if (part.has("text")) part.getString("text") else null
            }
            .joinToString("")
    }
}
