package com.mspg.poicat.weather

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "MariTanWeather"

/**
 * #POI マリたん秘書性能② Stage 2: 緯度経度から天気を取得するだけの、
 * Open-Meteo Weather Forecast API(APIキー不要・無料、
 * https://open-meteo.com/en/docs)への薄いラッパー。課金・有料プラン・
 * APIキーは一切使用しない。[com.mspg.poicat.gemini.GeminiSearchService]と
 * 同じHttpURLConnection + org.jsonパターンを踏襲している。
 */
object WeatherService {
    data class Answer(
        val description: String,
        /** 現在天気(dateがnullの場合)の気温。日別予報の場合は常にnull。 */
        val temperature: Double?,
        /** 日別予報(dateを指定した場合)の最高/最低気温。現在天気の場合は常にnull。 */
        val temperatureMax: Double?,
        val temperatureMin: Double?,
        /** 日別予報の降水確率(%)。現在天気にはOpen-Meteo側に確率という概念が
         * 無いため常にnull。 */
        val precipitationProbability: Int?,
    )

    sealed class Outcome {
        data class Success(val answer: Answer) : Outcome()

        /** 無料枠を使い切った(HTTP 429)。自動的に有料プランへ移行することは
         * 絶対にしない。 */
        object QuotaExceeded : Outcome()
    }

    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    /**
     * [date]がnull(=今)なら現在天気([current]ブロック)、指定されていればその
     * 日の日別予報([daily]ブロックの該当インデックス)を返す。forecast_days=3
     * としているため、今日/明日/明後日までを1回のAPI呼び出しでカバーできる —
     * 呼び出しごとに追加のAPIコールは発生しない。今回は予定との連携を行わない
     * ため、時刻別(hourly)予報は使わない。
     *
     * [Result]がfailureになるのは予期しない例外の場合のみ — 呼び出し元はこれを
     * 通信障害として扱う。
     */
    suspend fun fetch(latitude: Double, longitude: Double, date: LocalDate?): Result<Outcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE_URL?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=Asia%2FTokyo&forecast_days=3"
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    connection.requestMethod = "GET"

                    val responseCode = connection.responseCode
                    val ok = responseCode in 200..299
                    val stream = if (ok) connection.inputStream else connection.errorStream
                    val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }
                        ?: "(no response body)"

                    if (!ok) {
                        if (responseCode == 429) return@runCatching Outcome.QuotaExceeded
                        Log.w(TAG, "Open-Meteo weather error $responseCode: $text")
                        error("Open-Meteo weather error $responseCode")
                    }

                    Outcome.Success(parseAnswer(JSONObject(text), date))
                } finally {
                    connection.disconnect()
                }
            }
        }

    private fun parseAnswer(json: JSONObject, date: LocalDate?): Answer {
        if (date == null) {
            val current = json.optJSONObject("current")
            val code = current?.optInt("weather_code", -1) ?: -1
            val temp = current?.optDouble("temperature_2m")?.takeUnless { it.isNaN() }
            return Answer(describeWeatherCode(code), temp, null, null, null)
        }

        val daily = json.optJSONObject("daily")
        val dates = daily?.optJSONArray("time")
        var index = -1
        if (dates != null) {
            for (i in 0 until dates.length()) {
                if (dates.optString(i) == date.toString()) {
                    index = i
                    break
                }
            }
        }
        if (index < 0) return Answer("天気不明", null, null, null, null)

        val code = daily?.optJSONArray("weather_code")?.optInt(index, -1) ?: -1
        val tMax = daily?.optJSONArray("temperature_2m_max")?.optDouble(index)?.takeUnless { it.isNaN() }
        val tMin = daily?.optJSONArray("temperature_2m_min")?.optDouble(index)?.takeUnless { it.isNaN() }
        val precip = daily?.optJSONArray("precipitation_probability_max")?.optInt(index, -1)?.takeIf { it >= 0 }
        return Answer(describeWeatherCode(code), null, tMax, tMin, precip)
    }

    // WMO Weather interpretation codes(Open-Meteoが採用している標準コード表)を、
    // マリたんの短い日本語表現へ変換するだけ — Open-Meteoが返していない情報を
    // 推測で付け加えることはしない。
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "晴れ"
        1, 2 -> "晴れ時々くもり"
        3 -> "くもり"
        45, 48 -> "霧"
        51, 53, 55, 56, 57 -> "霧雨"
        61, 63, 65, 66, 67, 80, 81, 82 -> "雨"
        71, 73, 75, 77, 85, 86 -> "雪"
        95, 96, 99 -> "雷雨"
        else -> "天気不明"
    }
}
