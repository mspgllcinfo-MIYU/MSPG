package com.mspg.poicat.gemini

import android.content.Context
import android.content.Intent

/**
 * マリたんから、端末にインストール済みの外部AIアプリをパッケージ名で起動する
 * だけのランチャー。ChatGPT API/Gemini API等をPOI内部へ新しく組み込むものでは
 * ない — Android Intentで既存のアプリを開くだけで、起動後の会話内容にPOIは
 * 一切関与しない（取得・保存・監視は一切しない）。
 *
 * 拡張可能な設計: [APPS]にキーワード→パッケージ名を追加するだけで
 * 「Claude開いて」等に将来対応できる。Version 1ではChatGPTとGeminiの2つに
 * 正式対応する。
 */
object ExternalAiLauncher {
    // キーワード(小文字・空白除去後の音声認識テキストに含まれるか判定) → パッケージ名。
    private val APPS = linkedMapOf(
        "chatgpt" to "com.openai.chatgpt",
        "gemini" to "com.google.android.apps.bard",
        // 将来追加予定（未検証）: "claude" to "com.anthropic.claude"
    )

    /** [detectTarget]が返したキーワードから、ユーザー向けの表示名を得る。
     * 「見つからないにゃ」等の音声案内メッセージ組み立てに使う。 */
    fun displayName(keyword: String): String = when (keyword) {
        "chatgpt" -> "ChatGPT"
        "gemini" -> "Gemini"
        else -> keyword
    }

    /** [text]（今回認識された音声そのまま）から起動対象のキーワードを検出する。
     * 該当が無ければnull。ChatGPTは「ChatGPT」「Chat GPT」等、英語表記が
     * そのまま音声認識される場合に検出できる（カタカナ読み上げの表記ゆれ
     * 全てには対応していない — V1時点の既知の制約）。 */
    fun detectTarget(text: String): String? {
        val normalized = text.lowercase().replace(" ", "").replace("　", "")
        return APPS.keys.firstOrNull { normalized.contains(it) }
    }

    /** [keyword]に対応するアプリを起動する。未インストール/起動失敗時はアプリを
     * 落とさずfalseを返すだけ — 呼び出し元がこれを見て音声で案内する。 */
    fun launch(context: Context, keyword: String): Boolean {
        val packageName = APPS[keyword] ?: return false
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
