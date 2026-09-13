package com.mspg.poicat.gemini

import android.content.Context
import com.mspg.poicat.LocalJsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** マリたんが「覚えて」で保存した1件の記憶。 */
data class MariTanMemory(val id: Long, val text: String, val createdAt: Long)

/** [MariTanMemoryStore.forget]の結果。 */
sealed class ForgetResult {
    object NothingStored : ForgetResult()
    object NotFound : ForgetResult()

    /** 削除候補が複数あり、どれのことか判断できなかった — 何も削除していない。 */
    object Ambiguous : ForgetResult()
    data class Removed(val memory: MariTanMemory) : ForgetResult()
}

// 「覚えて」本体に加え、「覚えといて」(=覚えておいての口語縮約形。「覚えて」を
// 部分文字列として含まないため別途必要)等の自然な言い方も拾う。長い表現を先に
// 並べる必要は無い(firstOrNullはどれか1つ一致すれば十分なため)。
private val REMEMBER_TRIGGERS = listOf("覚えて", "おぼえて", "覚えといて", "おぼえといて")
private val FORGET_TRIGGERS = listOf("忘れて", "わすれて", "忘れといて", "わすれといて")
private val MARI_TAN_PREFIXES = listOf("ねえマリたん、", "ねえマリたん", "マリたん、", "マリたん")

/** 認識された音声テキストが「覚えて」依頼なら、保存すべき内容を返す。違えばnull。 */
fun extractRememberContent(text: String): String? {
    val trigger = REMEMBER_TRIGGERS.firstOrNull { text.contains(it) } ?: return null
    val content = stripMariTanPrefix(text.substringBefore(trigger)).trimEnd('、', '。', ' ', '　')
    return content.ifBlank { null }
}

/** 認識された音声テキストが「忘れて」依頼なら、検索クエリ相当の文字列を返す。違えばnull。 */
fun extractForgetQuery(text: String): String? {
    val trigger = FORGET_TRIGGERS.firstOrNull { text.contains(it) } ?: return null
    val content = stripMariTanPrefix(text.substringBefore(trigger)).trimEnd('、', '。', ' ', '　')
    return content.ifBlank { null }
}

/** マリたん呼びかけ部分の接頭辞を取り除く。[ExternalAiLauncher]からも
 * (発話全体が起動キーワードそのものかどうかの判定に)再利用するため同一
 * パッケージへ公開している。 */
internal fun stripMariTanPrefix(text: String): String {
    val trimmed = text.trim()
    val prefix = MARI_TAN_PREFIXES.firstOrNull { trimmed.startsWith(it) }
    return if (prefix != null) trimmed.removePrefix(prefix).trim() else trimmed
}

/**
 * マリたん専用の「覚えて」記憶ストア。黒猫AI([com.mspg.poicat.ChatRepository]の
 * 会話履歴)とは完全に別のファイル(mari_tan_memories.json)へ保存する —
 * 全会話を永久保存するのではなく、ユーザーが明示的に「覚えて」と言った内容だけを
 * 追加する。[LocalJsonStore]経由でアプリの内部ストレージへ永続化するので、
 * アプリ再起動後も保持される。既存のChatRepository/CatEventRepository等の
 * データには一切触れない、完全に新規・独立したファイル。
 *
 * 件数は[MAX_MEMORIES]で上限を設け、超えたら一番古いものから取り除く —
 * 「毎回全Memoryを大量送信しない」という制約を、そもそも保持件数を小さく
 * 保つことで満たす。個人利用の現実的な範囲ではこの上限に達することはまず無い。
 * 通常の会話では[GeminiSearchService.ask]へ全件をそのまま渡すが、1件あたり
 * 短い一言メモなので、上限50件でも消費tokenは無料枠のTPMに対して無視できる
 * 量に収まる。
 */
object MariTanMemoryStore {
    private const val FILE_NAME = "mari_tan_memories.json"
    private const val MAX_MEMORIES = 50

    suspend fun all(context: Context): List<MariTanMemory> = withContext(Dispatchers.IO) {
        load(context)
    }

    suspend fun remember(context: Context, text: String): MariTanMemory = withContext(Dispatchers.IO) {
        val memories = load(context).toMutableList()
        val memory = MariTanMemory(id = System.currentTimeMillis(), text = text, createdAt = System.currentTimeMillis())
        memories.add(memory)
        while (memories.size > MAX_MEMORIES) memories.removeAt(0)
        save(context, memories)
        memory
    }

    /** [query]に最も近い記憶を1件だけ削除する。候補が無ければ[ForgetResult.NotFound]、
     * 複数の候補が僅差で並ぶ場合は[ForgetResult.Ambiguous]を返し、何も削除しない
     * （曖昧な場合に勝手に大量削除しないため）。 */
    suspend fun forget(context: Context, query: String): ForgetResult = withContext(Dispatchers.IO) {
        val memories = load(context)
        if (memories.isEmpty()) return@withContext ForgetResult.NothingStored

        val scored = memories
            .map { it to bigramOverlap(query, it.text) }
            .filter { it.second > MATCH_THRESHOLD }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return@withContext ForgetResult.NotFound
        if (scored.size >= 2 && (scored[0].second - scored[1].second) < AMBIGUITY_MARGIN) {
            return@withContext ForgetResult.Ambiguous
        }

        val target = scored.first().first
        save(context, memories.filterNot { it.id == target.id })
        ForgetResult.Removed(target)
    }

    private fun load(context: Context): List<MariTanMemory> {
        val json = LocalJsonStore.read(context, FILE_NAME) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                MariTanMemory(id = obj.getLong("id"), text = obj.getString("text"), createdAt = obj.getLong("createdAt"))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, memories: List<MariTanMemory>) {
        val array = JSONArray()
        memories.forEach { memory ->
            array.put(
                JSONObject().apply {
                    put("id", memory.id)
                    put("text", memory.text)
                    put("createdAt", memory.createdAt)
                },
            )
        }
        LocalJsonStore.write(context, FILE_NAME, array.toString())
    }

    // 「コーヒーが好きっていうの忘れて」→「私コーヒーが好きなの」のような、表現の
    // 揺れがあっても一致を検出できるよう、文字bigramの重なり具合で近さを測る
    // （日本語は単語の区切りが無いため、形態素解析なしでも使える簡易的な方法）。
    private const val MATCH_THRESHOLD = 0.3
    private const val AMBIGUITY_MARGIN = 0.15

    private fun charBigrams(s: String): Set<String> {
        val clean = s.filter { !it.isWhitespace() }
        if (clean.length < 2) return if (clean.isEmpty()) emptySet() else setOf(clean)
        return (0 until clean.length - 1).map { clean.substring(it, it + 2) }.toSet()
    }

    private fun bigramOverlap(a: String, b: String): Double {
        val bigramsA = charBigrams(a)
        val bigramsB = charBigrams(b)
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        val overlap = bigramsA.intersect(bigramsB).size
        return overlap.toDouble() / minOf(bigramsA.size, bigramsB.size)
    }
}
