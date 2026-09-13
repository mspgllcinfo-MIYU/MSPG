package com.mspg.poicat.maps

import android.net.Uri

/**
 * #148 Maps-1B: Android標準の共有(ACTION_SEND/EXTRA_TEXT)で届いたテキストが
 * 「Google Mapsの場所共有」らしいかどうかを、URLのhost名だけで緩やかに
 * 判定する。共有元がGoogle MapsかGeminiか、あるいはどのGoogle Mapsクライアント
 * バージョンかで処理を分ける必要はない — 届いたテキストの中にGoogle Maps系の
 * URLが含まれているかどうかだけを見る。
 *
 * 意図的にURL形式を1つに決め打ちしない: 短縮版(maps.app.goo.gl、
 * goo.gl/maps/...)、通常版(www.google.com/maps/...)、旧来のmaps.google.com
 * 形式など複数のURL形式が実際にあり得るため、host名の緩やかな一致で判定
 * する。一方で、"google.com"や"goo.gl"のような一般的すぎるhost(地図以外の
 * 大量のURL・短縮リンクにも使われる)は、パスが地図であることを示す接頭辞で
 * 始まる場合だけ地図共有とみなす — host無条件許可はしない。
 *
 * ここでは短縮URL(maps.app.goo.gl、goo.gl/maps/...)の中身をネットワークで
 * 展開・解析することは一切しない — 見つかったURL文字列をそのまま返すだけで、
 * 実際の解決は(ユーザーが「Googleマップで開く」を選んだ場合に)Android/
 * Google Maps側に委ねる。
 */
object SharedLocationDetector {
    // maps.app.goo.gl / maps.google.com は、host自体がGoogle Mapsの地図
    // 専用であるため、パスを問わず信頼する。
    private val exactMapsHosts = setOf("maps.app.goo.gl", "maps.google.com")

    // google.com(www付き含む)は地図以外の大量のURLにも一致するhostのため、
    // パスが"/maps"で始まる場合だけ地図共有とみなす(例:
    // https://www.google.com/maps/place/...)。
    private val mapsPathHosts = setOf("google.com", "www.google.com")

    // 実機で確認された実際の共有payload例: https://goo.gl/maps/ErDE1mKEnma9nbPB6
    // 「goo.gl」は地図以外にも汎用的に使われるGoogleの短縮リンクドメインの
    // ため、host自体を無条件で信頼せず、パスが"/maps/"で始まる場合だけ地図
    // 共有とみなす。
    private const val GOOGLE_SHORT_LINK_HOST = "goo.gl"
    private const val GOOGLE_SHORT_LINK_MAPS_PATH_PREFIX = "/maps/"

    /**
     * [text]の中からGoogle Maps系のURLを1つ探して返す。見つからない、または
     * 見つかったURLのhostが上記のいずれにも一致しない場合はnull。
     */
    fun extractMapsUrl(text: String): String? {
        val match = Regex("""https?://\S+""").find(text) ?: return null
        // 共有テキストにURLの直後、区切りなしで日本語の句読点/かっこ類が続く
        // ことがあるため、URLの一部として誤って取り込まないよう末尾を軽く
        // 削る。
        val raw = match.value.trimEnd('.', '、', '。', ')', '」', '』', ',', '，')
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null

        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()
        val isTrustedHost = host in exactMapsHosts ||
            (host in mapsPathHosts && path.startsWith("/maps")) ||
            (host == GOOGLE_SHORT_LINK_HOST && path.startsWith(GOOGLE_SHORT_LINK_MAPS_PATH_PREFIX))
        return if (isTrustedHost) raw else null
    }

    /** [text]がGoogle Maps系のURLを含む(＝場所共有らしい)かどうか。 */
    fun looksLikeLocationShare(text: String): Boolean = extractMapsUrl(text) != null
}
