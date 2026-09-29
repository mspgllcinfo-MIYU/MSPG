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

private const val TAG = "PoiWorkJudge"

/**
 * #148 Phase 3-1: 予定のタイトルが「仕事として実行・対応すべき内容」かどうかを
 * Geminiに一言だけ判定させる、判定専用の軽量呼び出し。[GeminiSearchService]
 * (マリたんの人格会話用、system_instructionがマリたんの口調・雑談ルールを
 * 定義するもの)とは完全に分離している — 判定結果を自由文でなく固定3語
 * (WORK/NOT_WORK/UNKNOWN)だけに限定させるための専用system_instructionを
 * 使うため、[GeminiSearchService.ask]を流用しない。
 *
 * 送信する内容は呼び出し元([com.mspg.poicat.brain.CatBrain])が渡す[title]
 * （予定として登録されるタイトル文字列そのもの）だけ — POI内部のDB内容
 * (予定一覧/仕事タスク一覧/メモ/アルバム/ファイル/ルーム情報/過去の履歴)は
 * 一切含めない。
 *
 * [GeminiSearchService]と同じ方針で、新規SDK依存(com.google.ai.client.
 * generativeai等)は追加せず、HttpURLConnection + org.jsonの素のREST呼び出し
 * のみを使う。返り値は[GeminiOutcome]を[GeminiSearchService]とそのまま共有
 * する — 呼び出し元が最終的に安全側(UNKNOWN)へ倒す判断は、この関数ではなく
 * 呼び出し元(CatBrain)側で行う。
 */
object GeminiWorkJudge {
    // GeminiSearchServiceと同じモデルを使う。無料枠事情も同一のため、判定専用に
    // 別モデルを選ぶ理由がない。
    private const val MODEL = "gemini-3.5-flash-lite"
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    private const val SYSTEM_INSTRUCTION =
        "あなたは予定の内容が仕事かどうかを判定するだけの分類器です。" +
            "説明や前置きは不要です。次のいずれか1語だけを返してください: " +
            "WORK, NOT_WORK, UNKNOWN。" +
            "\n・仕事(業務・取引先対応・社内事務・現場対応など)だと明確に判断できる場合はWORK。" +
            "\n・私用(通院・美容・買い物・家族・友人・趣味など)だと明確に判断できる場合はNOT_WORK。" +
            "\n・どちらとも判断できない場合はUNKNOWN。" +
            "\nWORK/NOT_WORK/UNKNOWN以外の語や、それ以上の説明文は絶対に含めないでください。"

    suspend fun judge(title: String): Result<GeminiOutcome> = withContext(Dispatchers.IO) {
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
                            put("parts", JSONArray().put(JSONObject().put("text", title)))
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
                    Log.w(TAG, "Gemini work-judge error $responseCode: $text")
                    error("Gemini work-judge error $responseCode")
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
