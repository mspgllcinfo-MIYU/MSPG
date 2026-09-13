package com.mspg.poicat.maps

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * #148 Maps-1A: マリたんの地図/ナビ命令から、Google Mapsを開くだけの薄い
 * ランチャー。[com.mspg.poicat.gemini.ExternalAiLauncher]と同じ「Android
 * Intentで既存のアプリを開くだけ」という方針だが、あちらがパッケージ名
 * 指定で特定アプリをフォアグラウンドに呼び出すのに対し、こちらは
 * Googleが公開しているMaps URL形式(`https://www.google.com/maps/...`)への
 * 通常のACTION_VIEWで開く — Google Maps Platform の有料API
 * (Places/Geocoding/Directions/Maps SDK)は一切使わず、位置検索・経路計算は
 * 全てGoogle Maps側に任せる。POI自身はAPIキーもBillingも持たない。
 *
 * https形式のURLを使うことで、Google Mapsアプリがインストール済みなら
 * そのままMapsアプリが開き、未インストールでも端末の標準ブラウザで
 * Google Maps Web版が開く — Google Maps固有のURIスキーム
 * (`google.navigation:`等)と違い、特別なfallback処理を書かずに済む。
 *
 * 出発地(origin)は一切指定しない — 現在地の取得・保存はPOI側では行わず、
 * Google Maps自身の現在地処理に委ねる。
 */
object MapsLauncher {
    private const val SEARCH_BASE = "https://www.google.com/maps/search/?api=1&query="
    private const val NAVIGATION_BASE = "https://www.google.com/maps/dir/?api=1&destination="

    // 音声認識で拾った目的地が異常に長い(誤動作・ノイズ等)場合の安全な上限。
    // 実際の地名・施設名がここまで長くなることは通常無い。
    private const val MAX_DESTINATION_LENGTH = 300

    /** [place]をGoogle Mapsの場所検索として開く。開けた場合はtrue。 */
    fun openSearch(context: Context, place: String): Boolean = openMapsUrl(context, SEARCH_BASE, place)

    /** [destination]までのGoogle Mapsナビ(経路)を開く。開けた場合はtrue。 */
    fun openNavigation(context: Context, destination: String): Boolean = openMapsUrl(context, NAVIGATION_BASE, destination)

    private fun openMapsUrl(context: Context, base: String, target: String): Boolean {
        val trimmed = target.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_DESTINATION_LENGTH) return false

        return runCatching {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(base + encoded)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
