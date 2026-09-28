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
     *
     * #POI 実機不具合修正: 実機で「甲府」は解決できるのに「東京」「品川」が
     * [Outcome.NotFound]になる(＝通信自体は成功しレスポンスも正常に届いている
     * が、country_code=="JP"の候補が1件も返って来ない)という報告があった。
     * この関数は[Location.name](APIが返す表示用の地名)をどこからも参照して
     * いない — 呼び出し元は常に[latitude]/[longitude]だけを使い、実際に画面や
     * 発話へ出す地名は常にユーザー自身が入力した元の文字列(Stage 2/3の
     * `place`)をそのまま使っている。つまり`language=ja`は表示目的では一切
     * 使われておらず、それでいて検索対象の地名マッチング自体を日本語の
     * 別名データだけに絞り込んでしまっている可能性がある(Open-Meteoの
     * 検索仕様の詳細は非公開であり確証は無いが、"東京"/"品川"のように
     * 行政上の正式名(東京都/品川区)と口語の短い呼び方が異なる地名で
     * 症状が出ていることと矛盾しない)。表示用途に使っていない
     * `language=ja`を外し、`count`も10→20へ増やして「本来一致する候補が
     * 上位10件からこぼれる」ケースに備える — 特定の地名(「東京」等)を
     * 個別に救済するコードは一切追加していない、地名によらない一般的な
     * 検索条件の調整のみ。
     */
    suspend fun resolve(placeName: String): Result<Outcome> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(placeName, "UTF-8")
            val url = "$BASE_URL?name=$encoded&count=20&format=json"
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
