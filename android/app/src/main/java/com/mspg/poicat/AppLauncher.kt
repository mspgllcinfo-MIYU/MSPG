package com.mspg.poicat

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * #POI マリたん秘書性能② Stage 1: 目的地を伴わない「アプリを開くだけ」の単純起動。
 * [com.mspg.poicat.gemini.ExternalAiLauncher](外部AIアプリ起動)と同じ
 * 「Android Intentで既存アプリを開くだけ、内部にAPI・課金を一切組み込まない」
 * という方針の、Google Maps/Drive向けの小さな専用ランチャー。
 *
 * [com.mspg.poicat.maps.MapsLauncher](目的地付きの地図検索・経路案内)とは
 * 完全に独立した別オブジェクト — こちらを追加するために[MapsLauncher]/
 * [com.mspg.poicat.gemini.ExternalAiLauncher]の既存コードは一切変更していない。
 */
object AppLauncher {
    private const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val GOOGLE_DRIVE_PACKAGE = "com.google.android.apps.docs"

    // 目的地なしのGoogle Maps Web版。MapsLauncherのSEARCH_BASE/NAVIGATION_BASEと
    // 同じ「https URLで開けば、未インストールでも端末の標準ブラウザへ自然に
    // フォールバックする」考え方をそのまま踏襲している。
    private const val MAPS_WEB_FALLBACK_URL = "https://www.google.com/maps"

    /**
     * Google Mapsを目的地なしで起動する。アプリがインストール済みならそれを
     * 直接起動し、未インストールの場合は安全なWeb版(ブラウザ)へフォールバック
     * する。
     */
    fun openGoogleMaps(context: Context): Boolean {
        if (launchPackage(context, GOOGLE_MAPS_PACKAGE)) return true
        return openUrl(context, MAPS_WEB_FALLBACK_URL)
    }

    /**
     * Google Driveを起動する。Driveはユーザー固有データのためMapsのような
     * 汎用Web版フォールバックは行わない — 標準パッケージが見つからない場合は
     * 何もせずfalseを返すだけ(クラッシュしない)。
     */
    fun openGoogleDrive(context: Context): Boolean = launchPackage(context, GOOGLE_DRIVE_PACKAGE)

    private fun launchPackage(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun openUrl(context: Context, url: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
