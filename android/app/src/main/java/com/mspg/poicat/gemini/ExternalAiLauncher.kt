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
 * 「耳」(音声コマンド判定)の設計方針: マリたんの性格(ちょっと抜けていて愛嬌が
 * ある、完璧すぎない)は変えず、判定ロジックだけを実機での聞き取り失敗例に
 * 合わせて強化する。
 * - [ExternalAiApp.strongTriggers]: 「ChatGPT」「チャッピー」「ジェミニ」等、
 *   日常会話にまず出てこない一意な呼び名。文中のどこにあっても起動と判定する。
 * - [ExternalAiApp.weakTriggers]: 「GPT」「AIちゃん」「GoogleのAI」等、単体では
 *   雑談中にも出てきうる一般的な言い方。誤起動を防ぐため、(a)「開いて/行って/
 *   呼んで/起動して」等の起動意図の言葉と一緒に言われた場合、または
 *   (b)発話全体がその言い方だけで完結している場合(＝雑談の一部ではなく単独の
 *   コマンドとして発話された場合)のみ起動と判定する。
 */
private data class ExternalAiApp(
    val packageName: String,
    val displayName: String,
    val strongTriggers: List<String>,
    val weakTriggers: List<String> = emptyList(),
)

// 「開いて」「行って」「呼んで」等、起動意図を示す言葉。weakTriggersと組み合わせて
// 判定する時だけ使う(strongTriggersは単体で判定できるため不要)。
private val ACTION_WORDS = listOf("開いて", "行って", "呼んで", "起動して")

object ExternalAiLauncher {
    private val APPS = linkedMapOf(
        "chatgpt" to ExternalAiApp(
            packageName = "com.openai.chatgpt",
            displayName = "ChatGPT",
            strongTriggers = listOf(
                "chatgpt",
                "チャットgpt",
                "チャットジーピーティー",
                "チャットジーピーティ",
                "チャッピー",
            ),
            weakTriggers = listOf("gpt", "aiちゃん"),
        ),
        "gemini" to ExternalAiApp(
            packageName = "com.google.android.apps.bard",
            displayName = "Gemini",
            strongTriggers = listOf(
                "gemini",
                "ジェミニ",
                "ジェミナイ",
                "ジェミニー",
            ),
            weakTriggers = listOf("googleのai"),
        ),
        // 将来追加予定（未検証）:
        // "claude" to ExternalAiApp("com.anthropic.claude", "Claude", strongTriggers = listOf("claude", "クロード"))
    )

    /** [detectTarget]が返したキーワードから、ユーザー向けの表示名を得る。
     * 「見つからないにゃ」等の音声案内メッセージ組み立てに使う。 */
    fun displayName(keyword: String): String = APPS[keyword]?.displayName ?: keyword

    /** [text]（今回認識された音声そのまま）から起動対象のキーワードを検出する。
     * 該当が無ければnull。クラスコメント参照 — strongTriggersは単体で、
     * weakTriggersは起動意図の言葉との組み合わせ、または発話全体がその呼び名
     * だけの場合のみ判定する（雑談中の「AI」「Google」等への誤反応を防ぐ）。 */
    fun detectTarget(text: String): String? {
        val normalized = normalize(text)
        val hasActionWord = ACTION_WORDS.any { normalized.contains(it) }
        val wholeUtterance = normalize(
            stripMariTanPrefix(text).trimEnd('、', '。', '！', '!', '？', '?', ' ', '　'),
        )

        for ((key, app) in APPS) {
            if (app.strongTriggers.any { normalized.contains(it) }) return key
            val weakMatch = app.weakTriggers.any { trigger ->
                normalized.contains(trigger) && (hasActionWord || wholeUtterance == trigger)
            }
            if (weakMatch) return key
        }
        return null
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

    // 英字の大文字/半角・全角スペース・中黒・ハイフンの揺れを一括で吸収する。
    // (「チャット・ジーピーティー」「Chat-GPT」等)
    private fun normalize(text: String): String =
        text.lowercase()
            .replace(" ", "")
            .replace("　", "")
            .replace("・", "")
            .replace("-", "")
}
