package com.mspg.poicat.gemini

import android.content.Context
import android.content.Intent

/**
 * マリたんから、端末にインストール済みの外部AIアプリをパッケージ名で起動する
 * だけのランチャー。ChatGPT API/Gemini API等をPOI内部へ新しく組み込むものでは
 * ない — Android Intentで既存のアプリを開くだけで、起動後の会話内容にPOIは
 * 一切関与しない（取得・保存・監視は一切しない）。
 *
 * 拡張可能な設計: [APPS]に1件追加するだけで「Claude開いて」等に将来対応できる。
 * Version 1ではChatGPTとGeminiの2つに正式対応する。
 *
 * 実機で「ChatGPT」「Gemini」と話しても起動しなかった不具合(2件)の原因と対策:
 * 1. AndroidManifest.xmlに`<queries>`宣言が無く、Android 11(API 30)以降の
 *    package visibility制限により、対象アプリが実機にインストール済みでも
 *    [android.content.pm.PackageManager.getLaunchIntentForPackage]が常にnullを
 *    返していた（未インストールと区別がつかない状態）。AndroidManifest.xmlへ
 *    `<queries><package android:name="com.openai.chatgpt" />
 *    <package android:name="com.google.android.apps.bard" /></queries>`を
 *    追加して解消した。
 * 2. 音声認識はJapanese([Locale.JAPANESE])で行っている
 *    ([com.mspg.poicat.buildSpeechIntent]参照)ため、「ChatGPT」「Gemini」と
 *    発話しても認識結果はカタカナ（例:「チャットジーピーティー」「ジェミニ」）
 *    になることが多く、英字"chatgpt"/"gemini"としか一致しない判定では
 *    検出できず、外部AI起動の意図判定に到達すらしないまま通常のGemini質問
 *    として扱われていた（「他のAIは開けないにゃ」等はGemini自身が生成した
 *    相槌であり、本ランチャーが返す文言ではない）。主要なカタカナ表記ゆれを
 *    [ExternalAiApp.triggers]へ追加して解消した。
 */
private data class ExternalAiApp(val packageName: String, val displayName: String, val triggers: List<String>)

object ExternalAiLauncher {
    // キーワード(検出用の内部キー) → アプリ情報。
    private val APPS = linkedMapOf(
        "chatgpt" to ExternalAiApp(
            packageName = "com.openai.chatgpt",
            displayName = "ChatGPT",
            triggers = listOf(
                "chatgpt",
                "チャットジーピーティー",
                "チャットgpt",
                "チャットジーピーティ",
                "チャッピー",
            ),
        ),
        "gemini" to ExternalAiApp(
            packageName = "com.google.android.apps.bard",
            displayName = "Gemini",
            triggers = listOf(
                "gemini",
                "ジェミニ",
                "ジェミナイ",
            ),
        ),
        // 将来追加予定（未検証）: "claude" to ExternalAiApp("com.anthropic.claude", "Claude", listOf("claude", "クロード"))
    )

    /** [detectTarget]が返したキーワードから、ユーザー向けの表示名を得る。
     * 「見つからないにゃ」等の音声案内メッセージ組み立てに使う。 */
    fun displayName(keyword: String): String = APPS[keyword]?.displayName ?: keyword

    /** [text]（今回認識された音声そのまま）から起動対象のキーワードを検出する。
     * 該当が無ければnull。英字表記に加え、日本語音声認識時によく現れるカタカナ
     * 表記ゆれ（[ExternalAiApp.triggers]）も見る — 全ての表記ゆれを網羅しては
     * いないが、代表的なものはカバーする。 */
    fun detectTarget(text: String): String? {
        val normalized = text.lowercase().replace(" ", "").replace("　", "")
        return APPS.entries.firstOrNull { (_, app) -> app.triggers.any { normalized.contains(it) } }?.key
    }

    /** [keyword]に対応するアプリを起動する。未インストール/起動失敗時はアプリを
     * 落とさずfalseを返すだけ — 呼び出し元がこれを見て音声で案内する。
     *
     * 対象パッケージはAndroidManifest.xmlの`<queries>`へ宣言済みであることが
     * 前提（宣言が無いとAndroid 11以降ではインストール済みでもnullが返る）。 */
    fun launch(context: Context, keyword: String): Boolean {
        val packageName = APPS[keyword]?.packageName ?: return false
        return runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
