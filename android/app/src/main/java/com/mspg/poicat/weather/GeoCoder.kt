package com.mspg.poicat.weather

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "MariTanWeather"

/**
 * #POI マリたん秘書性能② Stage 2: 地名→緯度経度の変換だけを行う、Open-Meteo
 * Geocoding API(APIキー不要・無料、https://open-meteo.com/en/docs/geocoding-api)
 * への薄いラッパー。[com.mspg.poicat.gemini.GeminiSearchService]と同じ
 * HttpURLConnection + org.jsonパターンを踏襲し、新規ライブラリ依存は
 * 追加していない。有料プラン・APIキー・Billingは一切使用しない。
 */
object GeoCoder {
    data class Location(val name: String, val latitude: Double, val longitude: Double)

    sealed class Outcome {
        data class Found(val location: Location) : Outcome()

        /** 通信は成功したが、日本国内の候補が1件も見つからなかった。海外の
         * 同名地名を誤って採用しないための、意図的な「決定しない」結果。 */
        object NotFound : Outcome()

        /** 無料枠を使い切った(HTTP 429)。自動的に有料プランへ移行することは
         * 絶対にしない。 */
        object QuotaExceeded : Outcome()
    }

    private const val BASE_URL = "https://geocoding-api.open-meteo.com/v1/search"

    /**
     * [placeName]を日本国内の地点として解決する。複数候補がある場合は、
     * country_code=="JP"の中でOpen-Meteo自身が返す順序の先頭(最も関連度が
     * 高いもの)を採用する — 曖昧な複数候補から独自に1件を推測することはしない。
     * 日本の候補が1件も無い場合は[Outcome.NotFound]を返す。
     *
     * [Result]がfailureになるのは、レスポンス自体が壊れている等の予期しない
     * 例外の場合のみ — 呼び出し元はこれを通信障害として扱う。
     */
    suspend fun resolve(placeName: String): Result<Outcome> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(placeName, "UTF-8")
            val url = "$BASE_URL?name=$encoded&count=10&language=ja&format=json"
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
                    Log.w(TAG, "Open-Meteo geocoding error $responseCode: $text")
                    error("Open-Meteo geocoding error $responseCode")
                }

                val results = JSONObject(text).optJSONArray("results")
                val found = (0 until (results?.length() ?: 0))
                    .map { i -> results!!.getJSONObject(i) }
                    .firstOrNull { it.optString("country_code") == "JP" }

                if (found == null) {
                    Outcome.NotFound
                } else {
                    Outcome.Found(
                        Location(
                            name = found.optString("name"),
                            latitude = found.getDouble("latitude"),
                            longitude = found.getDouble("longitude"),
                        ),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
