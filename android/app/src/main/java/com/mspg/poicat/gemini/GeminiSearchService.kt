package com.mspg.poicat.gemini

import android.util.Log
import com.mspg.poicat.auth.FirebaseAnonymousAuth
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "MariTanGemini"

/** マリたんが1回の質問に対してGeminiから受け取った結果。 */
sealed class GeminiOutcome {
    data class Answer(val text: String) : GeminiOutcome()

    /** 無料枠を使い切った(HTTP 429)。マリたんを「ふて寝」状態にする合図 — 自動的に
     * 有料機能へ移行することは絶対にしない。 */
    object QuotaExceeded : GeminiOutcome()

    /** Worker未設定、またはFirebase認証に失敗した場合。ネットワークには出ない
     * (Worker未設定時)か、Gemini本体には到達しない(認証失敗時)。 */
    object NotConfigured : GeminiOutcome()
}

/**
 * マリたん専用のGemini呼び出し窓口。
 *
 * 【Version 2での変更: Gemini APIキーをAPKへ一切埋め込まない】
 * Version 1はBuildConfig.GEMINI_API_KEY経由でAPIキーをアプリへ直接埋め込み、
 * Gemini APIを直接呼んでいた。しかしAPKをGitHub Releaseでログイン不要公開する
 * 方針になったため、APKを入手した誰もがデコンパイルでキーを読み取れてしまう
 * 状態は許容できない。そのためVersion 2では、Cloudflare Worker
 * (cloudflare/openai-proxy、`/v1/gemini/generate`エンドポイント)を薄い中継
 * として経由する構成へ変更した:
 *
 *   マリたん(このクラス)
 *     --[Firebase IDトークンを添えてHTTPS]--> Cloudflare Worker
 *     --[Workerのシークレットとして保持するGEMINI_API_KEYを付与]--> Gemini API
 *
 * Gemini APIキー自体はWorkerのシークレットとしてのみ存在し、Androidアプリの
 * コード・APK・GitHubリポジトリのどこにも一切登場しない。認証は
 * [FirebaseAnonymousAuth]による匿名認証 — Google連携でのサインインは不要で、
 * アプリ起動時に自動的に済む。
 *
 * system_instruction/contentsの組み立て（キャラクター設定・Memory注入・回答の
 * 長さルール）は引き続きこのクラス(クライアント側)で行う — Cloudflare側の
 * 再デプロイ無しでマリたんの応答調整ができるようにするため。Workerは認証・
 * レート制限・APIキー注入だけを担当する薄いプロキシ。DriveFolderRepositoryと
 * 同じ方針で、新規SDK依存は追加せずHttpURLConnection + org.jsonの素のREST
 * 呼び出しのみを使う。
 *
 * Version 1ではGoogle Search Grounding(tools.google_search)を使わない: 実機と
 * GitHub Actions上でのA/Bテストで、全く同じキー/モデル/エンドポイントでも
 * grounding無し=HTTP 200成功、grounding有り=HTTP 429(RESOURCE_EXHAUSTED)が
 * 再現し、grounding機能自体の無料枠がこのプロジェクトでは極端に少ないことが
 * 確定した。Version 2でも変更なし(Worker側も`tools`を受け付けない)。
 */
object GeminiSearchService {
    // モデル選定方針は変更なし(gemini-3.5-flash-liteが無料枠15 RPMで最大)。
    // ただしVersion 2ではモデル名自体もWorker側(wrangler.tomlのGEMINI_MODEL)で
    // 固定しており、クライアントはモデルを指定しない(コスト管理・誤用防止のため)。

    // TODO: Cloudflare Workerを実際にデプロイした後、あなたのアカウントの
    // サブドメインへ差し替えてください(cloudflare/openai-proxy/README.md参照)。
    // Worker URL自体は秘密情報ではありません(Firebase IDトークン無しではWorker
    // が401で弾くため) — ソースコードに書いても問題ありません。
    private const val WORKER_BASE_URL = "https://poicat-openai-proxy.YOUR-SUBDOMAIN.workers.dev"
    private const val UNCONFIGURED_MARKER = "YOUR-SUBDOMAIN"

    // 性格(知的で好奇心旺盛、猫らしい親しみやすい口調)は変更していない —
    // ユーザーが気に入っている「ちょっとおしゃべりでアホ可愛い」雰囲気はこの
    // 一文が生み出しているため、そのまま維持し、回答の長さだけを発話内容に
    // 応じて使い分けるルールを追記してある。
    private const val SYSTEM_INSTRUCTION =
        "あなたは「マリたん」という知的で好奇心旺盛なキジ猫です。日本語で、猫らしい" +
            "親しみやすい口調で答えてください。" +
            "\n\n【回答の長さルール】" +
            "\n・質問への回答/操作の実行/用事の返事のとき: 先に結論・答えを述べる。" +
            "基本1〜2文、長くても3文まで。必要以上に説明を広げない。" +
            "\n・雑談のとき(「暇だね」「今日疲れた」「なんか話そう」「マリたん何してた?」" +
            "等、明らかに世間話をしている場合): 今まで通り、猫らしい自然なおしゃべりで" +
            "少し話を広げてよい。ただし一方的な長話にはせず、ユーザーとテンポよく" +
            "会話のキャッチボールができる長さを保つ。" +
            "\n・ユーザーが「詳しく教えて」「もっと教えて」「ちゃんと説明して」等、" +
            "明示的に詳しい説明を求めたときだけ、長めに詳しく答えてよい。"

    suspend fun ask(query: String, memories: List<String> = emptyList()): Result<GeminiOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            if (WORKER_BASE_URL.contains(UNCONFIGURED_MARKER)) return@runCatching GeminiOutcome.NotConfigured

            val idToken = FirebaseAnonymousAuth.currentIdToken()
            if (idToken.isNullOrBlank()) return@runCatching GeminiOutcome.NotConfigured

            // マリたん専用Memory(「覚えて」で保存された分のみ、MariTanMemoryStore経由)を
            // 必要な範囲でsystem_instructionへ追記する。黒猫AIの会話履歴やPOI内の他の
            // データは一切含めない。件数はMariTanMemoryStore側でMAX_MEMORIES件に上限
            // されているため、ここで際限なく肥大化することはない。
            val systemInstructionText = if (memories.isEmpty()) {
                SYSTEM_INSTRUCTION
            } else {
                SYSTEM_INSTRUCTION + "\n\nユーザーについて覚えていること:\n・" + memories.joinToString("\n・")
            }

            val requestBody = JSONObject().apply {
                put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstructionText))),
                )
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", query)))
                        },
                    ),
                )
                // toolsは付けない(Google Search Grounding封じ) — クラスコメント参照。
            }

            val url = "$WORKER_BASE_URL/v1/gemini/generate"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                connection.doOutput = true
                connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                val ok = responseCode in 200..299
                val stream = if (ok) connection.inputStream else connection.errorStream
                val text = stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }
                    ?: "(no response body)"

                if (!ok) {
                    if (responseCode == 429) return@runCatching GeminiOutcome.QuotaExceeded
                    // 一般ユーザー画面には出さないが、実機調査が要る場合はlogcat
                    // (タグ: MariTanGemini)で追える。
                    Log.w(TAG, "Gemini proxy error $responseCode: $text")
                    error("Gemini proxy error $responseCode")
                }

                GeminiOutcome.Answer(text = extractAnswerText(text))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractAnswerText(responseJson: String): String {
        val candidates = JSONObject(responseJson).optJSONArray("candidates") ?: JSONArray()
        if (candidates.length() == 0) return "うまく答えを見つけられなかったにゃ"
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val combined = (0 until parts.length())
            .mapNotNull { i ->
                val part = parts.getJSONObject(i)
                if (part.has("text")) part.getString("text") else null
            }
            .joinToString("")
        return combined.ifBlank { "うまく答えを見つけられなかったにゃ" }
    }
}
