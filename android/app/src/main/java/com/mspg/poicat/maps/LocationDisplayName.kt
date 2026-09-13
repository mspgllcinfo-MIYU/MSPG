package com.mspg.poicat.maps

/**
 * #148 Maps-2C: [com.mspg.poicat.data.CatEvent.locationText]から、人間向けの
 * 表示名を安全に導出する。新しいDB schemaフィールドは不要 — [locationText]
 * が「施設名＋Maps URL」の形で保存されていた場合に限り、URL部分を取り除いた
 * 残りをそのまま表示名として使う。
 *
 * 施設名を推測・捏造することは絶対にしない: 共有された生テキストにURL以外
 * の文字列が実際に含まれていない場合(URLだけが共有された場合)は必ずnullを
 * 返し、呼び出し元は「Googleマップで開く」のような安全な既定表示へ
 * フォールバックする。短縮URLのネットワーク展開・Places/Geocoding API等に
 * よる施設名の取得はここでも一切行わない —
 * [SharedLocationDetector.extractMapsUrl]をそのまま再利用するだけで、
 * [SharedLocationDetector]自体には一切手を加えていない。
 *
 * 将来、ユーザーの直接入力やマリたんとの会話等、別の経路で信頼できる
 * 場所名が[locationText]に含まれるようになった場合も、この関数は同じ
 * ロジック(URL部分を取り除いた残り)でそのまま対応できる — 追加のschema
 * 変更は不要。
 */
object LocationDisplayName {
    fun extractDisplayName(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val url = SharedLocationDetector.extractMapsUrl(trimmed)
            ?: return trimmed // URLを含まない場合、テキスト全体(ユーザーが直接入力した場所名等)がそのまま表示名。

        val remainder = trimmed.replace(url, "").trim(' ', '\n', '\t', '、', '。', '-', '—', ':', '：')
        return remainder.ifBlank { null }
    }
}
