package com.mspg.poicat.brain

import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.gemini.GeminiOutcome
import com.mspg.poicat.gemini.GeminiRegistrationIntent
import com.mspg.poicat.gemini.GeminiWorkJudge
import com.mspg.poicat.maps.LocationDisplayName
import com.mspg.poicat.weather.GeoCoder
import com.mspg.poicat.weather.WeatherService
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.withTimeoutOrNull

/** A cat AI reply: the にゃ-voiced text, plus any photos found for a photo-search question
 * (empty for every other kind of reply). */
data class CatReply(val text: String, val photoIds: List<Long> = emptyList())

/** #148 Maps-1A: a Google Maps request recognized from user input — either
 * a plain place search or turn-by-turn navigation to it. Carries only the
 * free-text [destination] the user said and which of the two Google Maps
 * intents to launch; no Android [android.content.Intent]/Context involved
 * here, since [CatBrain] never launches Intents itself — see
 * [com.mspg.poicat.maps.MapsLauncher] for that. */
data class MapCommand(val destination: String, val mode: MapCommandMode)

enum class MapCommandMode { SEARCH, NAVIGATION }

/** #POI マリたん秘書性能② Stage 1: [CatBrain.detectSimpleAppLaunch]が返す、
 * 目的地なしで起動する対象アプリ。実際の起動は[com.mspg.poicat.AppLauncher]の責務。 */
enum class SimpleAppTarget { GOOGLE_MAPS, GOOGLE_DRIVE }

/**
 * #POI マリたん性能アップ Phase 1: 直前に成立したPOI質問([CatBrain.answerPoiQueryOrNull])
 * の検索条件だけを保持する、メモリ内だけの短命な状態。DB/Firestoreには一切保存
 * しない — 呼び出し元(MariTanRow)のComposeスコープが持つ、1回の会話の間だけの
 * 補助情報で、プロセス終了/アプリ再起動で自然に消える。
 *
 * [person]がnullなら「人物指定なし(夫婦共有全体)」、[CatEvent.ASSIGNEE_BOTH]なら
 * 明示的な「2人」、みゆたん/かっちゃんの名前ならその人物。[personIsSelf]がtrueの
 * 場合、[person]は「私/自分」経由で解決された値であることを示し、"2人"扱いの
 * 予定/仕事も自分のものとして一致対象に含める(既存の「私の仕事」判定と同じ考え方)。
 * [workOnly]はTASK限定で、仕事タスク(category=WORK)だけに絞るか([workTaskQuestionCore]
 * 経由)、Poiタスク全般([isTaskQuestion]経由)かを区別する。
 */
data class ConversationContext(
    val topic: ConversationTopic,
    val dateRange: Pair<LocalDate, LocalDate>? = null,
    val dateLabel: String? = null,
    val keyword: String? = null,
    val person: String? = null,
    val personIsSelf: Boolean = false,
    val workOnly: Boolean = false,
    /**
     * #POI マリたん秘書性能② Stage 3: 直前のSCHEDULE系の質問が「安全に1件だけ」に
     * 絞れた場合のみ設定されるスナップショット。0件または2件以上の場合は必ずnull —
     * 複数候補からAI判断で「たぶんこれ」と選ぶことは一切しない。「そこ雨？」等の
     * 場所非明示の天気継続質問だけがこれを参照する。DB/Firestoreへの永続化は行わない
     * (ConversationContext全体と同じくメモリ上のみ)。
     */
    val lastAnsweredEvent: AnsweredEventSnapshot? = null,
)

enum class ConversationTopic { SCHEDULE, TASK, MEMO }

/**
 * [ConversationContext.lastAnsweredEvent]用の最小スナップショット。[CatEvent]全体では
 * なく、Stage 3の「そこ雨？」に必要な最小限(一意識別用のid・title・dateTime・
 * locationText)だけを保持する — 予定登録側の仕様やRegistrationIntent等には一切
 * 関与しない、読み取り専用の複製。
 */
data class AnsweredEventSnapshot(
    val id: Long,
    val title: String,
    val dateTime: Long,
    val locationText: String?,
)

/**
 * The "memory cat" brain: everything is on-device pattern matching against
 * the Room database. No AI model, no network call. Given a line of chat
 * input it either remembers a new schedule/memo, answers a question from
 * what it already remembers, or just files the line away as a memo.
 */
class CatBrain(
    private val repository: CatEventRepository,
    private val photoRepository: PhotoRepository,
    /**
     * #144: 現在この端末を使っている利用者の表示名(「みゆたん」「かっちゃん」、
     * 未設定ならnull、正本は[com.mspg.poicat.room.RoomStore.displayName])を
     * 都度取得するための関数。固定値ではなく関数にしているのは、CatBrainの
     * インスタンス自体は呼び出し元でremember等により使い回される一方、表示名は
     * 設定画面でいつでも変更され得るため、呼ぶたびに最新値を読めるようにするため。
     * 「私の仕事」のような、話者本人を指す質問にのみ使う — 予定/メモ/他の
     * 仕事タスクの取得・表示・同期には一切影響しない。
     */
    private val currentDisplayName: () -> String? = { null },
) {

    suspend fun respond(input: String): CatReply {
        // Users often paste example phrases straight out of quoted instructions
        // (e.g. "「明日、病院」"); strip the quote marks so they don't end up
        // stuck in a saved title.
        val trimmed = input.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return CatReply("なに？にゃ")

        val now = LocalDateTime.now()

        // Checked first: "駐車場の写真見せて" also ends in "見せて" (a general query
        // trigger), so photo requests have to be intercepted before the generic
        // schedule/memo query path would otherwise answer from cat_events instead.
        if (isPhotoQuery(trimmed)) {
            return answerPhotoQuery(trimmed, now)
        }

        // #144: 「私の仕事」「今日の仕事」のような、仕事タスクの担当を尋ねる質問は
        // 「？」等を伴わない体言止めの言い方が多く、下のDateTimeParser.isQuery()の
        // 判定(「？」「いつ」や「教えて」「の予定は」等の特定の言い回しに限定した、
        // DateTimeParser.kt側の既存の質問検出)には一致しないものがほとんどのため、
        // その判定を待たずにここで独立に判定する。DateTimeParser.kt・
        // isTaskQuestion()・answerTaskQuery()・answerQuery()は一切変更していない。
        if (isWorkTaskQuestion(trimmed)) {
            if (looksOutOfScope(trimmed)) return CatReply(outOfScopeReply(trimmed))
            return CatReply(answerWorkTaskQuery(trimmed, now, currentDisplayName()))
        }

        // #147: 「私の予定」「みゆたんの予定」「かっちゃんの予定」「2人の予定」の
        // ように、予定の担当を人物指定で尋ねる質問も、仕事タスクと同様に独立した
        // 分岐として扱う。日付のみを指定した「今日の予定は？」等(人物指定なし)は
        // 対象にせず、従来通り下のanswerQuery()がそのまま処理する — 既存の
        // DateTimeParser.kt・answerQuery()は一切変更していない。
        if (isScheduleQuestionWithPerson(trimmed)) {
            if (looksOutOfScope(trimmed)) return CatReply(outOfScopeReply(trimmed))
            return CatReply(answerScheduleQueryByPerson(trimmed, now, currentDisplayName()))
        }

        if (DateTimeParser.isQuery(trimmed)) {
            // "今日の天気は？"/"日本の首都は？" would otherwise fall into answerQuery()
            // and get an answer built from a keyword that can never match anything in
            // cat_events ("の天気の予定はまだ入ってないにゃ") — checked here, before the
            // real query handlers, so a genuine zero-result POI question (e.g. "駐車場の
            // 番号なんだっけ？" with nothing saved yet) still gets its normal reply.
            if (looksOutOfScope(trimmed)) return CatReply(outOfScopeReply(trimmed))
            val text = if (isTaskQuestion(trimmed)) answerTaskQuery(trimmed, now) else answerQuery(trimmed, now)
            return CatReply(text)
        }

        // Checked before schedule registration: "今日ゴミ出しやる" contains "今日", a
        // valid schedule date, but the "やる" ending means it's a to-do, not an event.
        val completionKeywords = extractTaskCompletionKeywords(trimmed)
        if (completionKeywords.isNotEmpty()) {
            val task = run {
                for (keyword in completionKeywords) {
                    repository.incompleteTasksMatching(keyword).firstOrNull()?.let { return@run it }
                }
                null
            }
            return if (task != null) {
                repository.setTaskCompleted(task, true)
                CatReply("${task.title}終わったにゃ")
            } else {
                CatReply("そのタスクは見つからなかったにゃ")
            }
        }

        val taskContent = extractTaskCommand(trimmed)
        if (taskContent != null) {
            val (dueDate, titleRaw) = DateTimeParser.parseDueDate(taskContent, now)
            val title = DateTimeParser.cleanTitle(titleRaw, fallback = "タスク")
            // #POI 仕様変更: 指定なし＝2人(共有)をデフォルト担当とする。
            repository.addTask(title, dueDate?.toEpochMilli(), classifyTaskCategory(title), resolveRegistrationAssignee(trimmed, currentDisplayName()))
            return CatReply("ポイに入れたにゃ")
        }

        val memoContent = extractMemoCommand(trimmed)
        if (memoContent != null) {
            // "明日病院だから覚えといて" uses a memo-style "覚えといて" trigger, but the
            // content itself names a date — that makes it a schedule, not a memo.
            // #BB修正3(ユーザー承認済み): 日付語が見つかっただけでは即予定化しない —
            // judgeRegistrationIntentで「予定として十分に認識できる」(SCHEDULE)と判定
            // できた場合だけ予定登録する。「今日は暑いねって覚えといて」のような雑談を
            // メモ指示経由で誤って予定にしてしまわないための、registerScheduleCore
            // (マリたん用)と同じ判定をここにも適用したもの。判定を通らなかった場合は
            // 従来通りメモとして保存する — 明示的な保存指示自体は常に尊重する。
            val scheduleFromMemo = DateTimeParser.parseRegistration(memoContent, now)
            if (scheduleFromMemo != null && judgeRegistrationIntent(memoContent) == RegistrationIntent.SCHEDULE) {
                val (saved, judgment) = rememberScheduleWithWorkJudgment(
                    scheduleFromMemo.title,
                    scheduleFromMemo.dateTime.toEpochMilli(),
                    resolveRegistrationAssignee(trimmed, currentDisplayName()),
                )
                return CatReply(scheduleRegisteredReply(saved, judgment, now))
            }
            // #POI 仕様変更: 指定なし＝2人(共有)をデフォルト担当とする。
            repository.remember(memoContent, null, resolveRegistrationAssignee(trimmed, currentDisplayName()))
            return CatReply("メモしたにゃ")
        }

        // #BB修正3(ユーザー承認済み): マリたん専用のregisterScheduleCoreと同じ考え方を
        // BB(respond())にも適用 — 日付語が見つかっただけでは予定登録せず、
        // judgeRegistrationIntentが「予定として十分に認識できる」(SCHEDULE)と判定した
        // 場合だけ登録する。「今日は暑いね」等(NOT_SCHEDULE/UNKNOWN)は予定として保存
        // されない。
        val registration = DateTimeParser.parseRegistration(trimmed, now)
        if (registration != null && judgeRegistrationIntent(trimmed) == RegistrationIntent.SCHEDULE) {
            // #POI 仕様変更: 指定なし＝2人(共有)をデフォルト担当とする。
            val (saved, judgment) = rememberScheduleWithWorkJudgment(
                registration.title,
                registration.dateTime.toEpochMilli(),
                resolveRegistrationAssignee(trimmed, currentDisplayName()),
            )
            return CatReply(scheduleRegisteredReply(saved, judgment, now))
        }

        // #BB修正4(ユーザー承認済み): 「解釈できなかった発言は何であれメモとして保存
        // する」というキャッチオール仕様は廃止。明示的な保存指示(上のextractMemoCommand/
        // extractTaskCommand)でも、予定として十分に認識できる入力(直前のSCHEDULE判定)
        // でもない発言は、一切保存せず普通の会話として返す — 「猫かわいい」「眠い」
        // 「今日は暑いね」等の雑談がメモ/タスク/予定として保存されることはない。曖昧な
        // 通常会話を判定する新しいAI判定は追加しない(ユーザー方針: 既存のlooksOutOfScope
        // より先には何も判定を挟まない)。
        if (looksOutOfScope(trimmed)) {
            return CatReply(outOfScopeReply(trimmed))
        }
        return CatReply(chitchatReply())
    }

    /**
     * Phase C: sorts a photo (already saved to the album by the caller) using a caption
     * typed alongside it. Reuses the exact same trigger detection [respond] uses for
     * text — a caption that would register as a task/memo/schedule on its own does the
     * same thing here, just with the photo linked to whatever gets created.
     *
     * Deliberately never falls back to saving the caption as a plain memo: unlike
     * [respond]'s text-only fallback (where an unrecognized line is still worth
     * remembering on its own), a caption that matches nothing here is usually either a
     * question about the photo's contents ("これ何？") or a report this phase doesn't
     * handle (a task-completion caption like "牛乳買ったよ") — guessing at either would
     * create a wrong/duplicate record. The photo stays exactly as already saved; only
     * the reply says the cat couldn't sort it.
     */
    suspend fun respondToPhoto(caption: String, photo: Photo): CatReply {
        val trimmed = caption.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return CatReply("アルバムに入れたにゃ")

        val now = LocalDateTime.now()

        val taskContent = extractTaskCommand(trimmed)
        if (taskContent != null) {
            val (dueDate, titleRaw) = DateTimeParser.parseDueDate(taskContent, now)
            val title = DateTimeParser.cleanTitle(titleRaw, fallback = "タスク")
            // #POI 仕様変更: 指定なし＝2人(共有)をデフォルト担当とする。
            val task = repository.addTask(
                title,
                dueDate?.toEpochMilli(),
                classifyTaskCategory(title),
                resolveRegistrationAssignee(trimmed, currentDisplayName()),
            )
            photoRepository.linkToMemo(photo, task.id)
            return CatReply("ポイに入れて写真も残したにゃ")
        }

        val memoContent = extractMemoCommand(trimmed)
        if (memoContent != null) {
            val scheduleFromMemo = DateTimeParser.parseRegistration(memoContent, now)
            if (scheduleFromMemo != null) {
                photoRepository.updateDetails(
                    photo,
                    caption = photo.caption,
                    albumName = photo.albumName,
                    linkedDate = scheduleFromMemo.dateTime.toLocalDate().toEpochMilli(),
                )
                // #POI 仕様変更: 指定なし＝2人(共有)をデフォルト担当とする。
                repository.remember(
                    scheduleFromMemo.title,
                    scheduleFromMemo.dateTime.toEpochMilli(),
                    resolveRegistrationAssignee(trimmed, currentDisplayName()),
                )
                return CatReply("覚えて写真も残したにゃ")
            }
            val event = repository.remember(memoContent, null, resolveRegistrationAssignee(trimmed, currentDisplayName()))
            photoRepository.linkToMemo(photo, event.id)
            return CatReply("メモと一緒に写真も残したにゃ")
        }

        val registration = DateTimeParser.parseRegistration(trimmed, now)
        if (registration != null) {
            photoRepository.updateDetails(
                photo,
                caption = photo.caption,
                albumName = photo.albumName,
                linkedDate = registration.dateTime.toLocalDate().toEpochMilli(),
            )
            // #POI 仕様変更: 指定なし＝2人(共有)をデフォルト担当とする。
            repository.remember(
                registration.title,
                registration.dateTime.toEpochMilli(),
                resolveRegistrationAssignee(trimmed, currentDisplayName()),
            )
            return CatReply("覚えて写真も残したにゃ")
        }

        // Nothing matched a known sorting trigger. Never guess: no new schedule/Poi/memo
        // is created and the photo is left exactly as already saved (an uncategorized
        // album photo) — only the reply differs by what kind of caption this looks like.
        if (looksOutOfScope(trimmed)) {
            return CatReply(outOfScopeReply(trimmed))
        }
        if (looksLikeImageContentQuestion(trimmed)) {
            return CatReply(imageContentDeclineReply())
        }
        return CatReply(unsortablePhotoReply())
    }

    private val imageReferentialWords = listOf("これ", "この", "それ", "写真")

    /** "これ何？"/"この人誰？"/"これどう思う？" style — a question about the photo's
     * contents, which this app can't answer (no image-analysis AI is used). */
    private fun looksLikeImageContentQuestion(text: String): Boolean =
        DateTimeParser.isQuery(text) && imageReferentialWords.any { text.contains(it) }

    private val imageContentDeclineReplies = listOf(
        "写真の中身までは見れないにゃ",
        "それは他のAIに見てもらえにゃ",
    )

    private fun imageContentDeclineReply(): String = imageContentDeclineReplies.random()

    private val unsortablePhotoReplies = listOf(
        "これはまだ仕分けできないにゃ",
        "うまく仕分けできなかったにゃ",
        "ポイにもメモにもできなかったにゃ",
    )

    private fun unsortablePhotoReply(): String = unsortablePhotoReplies.random()

    /**
     * Phase A: a lightweight, keyword-based guard for topics PoiCat doesn't manage
     * (weather, news, general trivia, translation, arithmetic, recommendations, ...).
     * This can't perfectly tell "out of scope" apart from "in scope but not saved yet"
     * — it's the same kind of best-effort keyword matching DateTimeParser/CatBrain
     * already use everywhere else, not true language understanding.
     */
    private val outOfScopeSignals = listOf(
        "天気", "気温", "降水", "湿度", "台風", "花粉",
        "ニュース", "速報", "株価", "為替",
        "首都", "人口",
        "翻訳", "英語で",
        "おすすめ", "ランキング", "レシピ", "作り方",
    )

    private val arithmeticExpression = Regex("""\d+\s*[×x*÷/+\-]\s*\d+""")

    private fun looksOutOfScope(text: String): Boolean =
        outOfScopeSignals.any { text.contains(it) } || arithmeticExpression.containsMatchIn(text)

    private val weatherSignals = listOf("天気", "気温", "降水", "湿度", "台風", "花粉")

    private val outOfScopeReplies = listOf(
        "それは他のAIに聞けにゃ",
        "知らんにゃ。専門外だにゃ",
        "そこまで働かせるなにゃ",
        "できるとは言ってないにゃ",
        "それ、うちの仕事じゃないにゃ",
    )

    /** A weather-specific line when the input names weather, otherwise a random general line. */
    private fun outOfScopeReply(text: String): String {
        if (weatherSignals.any { text.contains(it) }) return "天気予報に聞けにゃ"
        return outOfScopeReplies.random()
    }

    // #BB修正4(ユーザー承認済み): 明示的な保存指示でも予定登録でもない、普通の会話
    // ("今日は暑いね"/"猫かわいい"/"眠い"等)への相槌だけの返事。何も保存しない。
    private val chitchatReplies = listOf(
        "そうにゃ",
        "にゃーん",
        "そうかもにゃ",
        "ふーん、にゃ",
    )

    private fun chitchatReply(): String = chitchatReplies.random()

    private val taskTriggerSuffixes = listOf(
        "の忘れないで", "を忘れないで", "忘れないで",
        "の忘れずに", "を忘れずに", "忘れずに",
        "を忘れるな", "忘れるな",
        "忘れないように",
        // #BB修正2: 「タスクにして」等の明示的なタスク指示。
        "をタスクにして", "のタスクにして", "タスクにして",
        "買わなきゃ", "買わないと",
        "やる", "買う", "送る",
    )

    // Bare dictionary-form verbs that, on their own with no other trigger, still read
    // as "something to do" ("牛乳買う", "見積書送る") rather than a note or a calendar
    // event. Recognizing "送る" as a task verb is separate from whether it counts
    // toward 仕事/プラベ classification (it deliberately doesn't — see workSignals
    // below): this list only decides *that* something is a task, not *which* category.
    private val taskVerbEndings = listOf("買う", "送る")

    /**
     * Recognizes a task/reminder declaration ("牛乳買うの忘れないで", "今日ゴミ出しやる",
     * "9月10日までに書類を出す", "牛乳買う", "牛乳買わなきゃ", "牛乳忘れないように") and
     * returns the content to register, or null. Checked before schedule registration so
     * a date word inside a task sentence (like "今日" above) doesn't get it mistaken for
     * a calendar event.
     */
    private fun extractTaskCommand(text: String): String? {
        for (suffix in taskTriggerSuffixes) {
            if (text.endsWith(suffix) && text.length > suffix.length) {
                // Bare dictionary-form verbs like "買う" describe *what* to do, not just
                // that something is due — keep them in the saved title ("牛乳買う") rather
                // than stripping down to just the noun ("牛乳"), so a Poi list entry still
                // shows the action at a glance.
                if (suffix in taskVerbEndings) return text
                val content = text.removeSuffix(suffix).replace("までに", "").trim()
                    .removeSuffix("を").removeSuffix("の").trim()
                if (content.isNotBlank()) return content
            }
        }
        if (text.contains("までに")) {
            val content = text.replace("までに", "").trim()
            if (content.isNotBlank()) return content
        }
        // #BB修正1(ユーザー承認済み): 以前はここで、内容が"買う"/"送る"等のtaskVerbEndings
        // で終わる場合、メモ/覚えて系のトリガー("牛乳買うの覚えといて")もタスクとして
        // 横取りしていた。これが「牛乳買うのメモして」のように明示的に「メモして」と
        // 指定した発話まで誤ってタスク化してしまう原因だったため、この横取り自体を廃止。
        // 明示的なメモ/覚えて指示は、内容が何で終わっていても必ずextractMemoCommand側で
        // メモとして扱われる。
        return null
    }

    private val workSignals = listOf(
        "見積", "会議", "資料", "提出", "メール", "顧客", "取引先", "契約", "請求", "会社", "案件",
        // Added after real-device review: broader business/admin vocabulary, still
        // nouns only — no generic daily-life verbs (見送る/確認する/連絡する etc. stay
        // out, same reasoning as "送る" below). A few (支払/振込/検査/銀行/役所) were
        // deliberately left out despite being business-flavored, since they're at
        // least as common in private life (electric bill payment, a bank errand, a
        // medical checkup, a city-hall errand) and would misclassify too often alone.
        "発注", "受注", "納品", "納期",
        "打合せ", "打ち合わせ", "商談", "業者", "客先", "現場",
        "稟議", "決裁", "経費", "売上", "仕入", "在庫",
        "図面", "施工", "工程", "行政",
        "地主", "法人", "税務", "税理士", "決算", "プロジェクト", "系統",
        "担当者", "申請", "報告書", "承認", "領収書", "入金",
        "設計", "許可", "電力", "土地", "登記", "融資", "工事",
    )

    /**
     * Classifies a registered task's saved title as work or private — keyword-based like
     * the rest of CatBrain (see [outOfScopeSignals]). A generic verb like "送る" is
     * deliberately absent from [workSignals] so it never decides this on its own
     * ("写真送る" stays private); it only reads as work alongside one of the nouns above
     * ("見積書送る" is work, because of "見積"). Anything without a clear work signal
     * defaults to private. A simple, coarse rule by design — not meant to be precise.
     */
    private fun classifyTaskCategory(title: String): String =
        if (workSignals.any { title.contains(it) }) CatEvent.CATEGORY_WORK else CatEvent.CATEGORY_PRIVATE

    /**
     * #148 フェーズ3a: 予定登録時に「仕事として実行・対応すべき予定」かどうかを
     * 端末内だけで判定した結果。ネットワーク・外部AI(Gemini含む)は一切使わない
     * — [judgeWorkIntent]をこの3値の判定だけを返す独立した関数にしておくことで、
     * 将来Gemini等の意味判定に差し替える場合も、このenumの意味(WORK/NOT_WORK/
     * UNKNOWN)自体はそのまま維持できるようにしている。
     */
    private enum class WorkJudgment { WORK, NOT_WORK, UNKNOWN }

    // #148 フェーズ3a: 明確に私用と分かる語。workSignalsに複数一致しない限り
    // WORKにはしない前提の上で、こちらに一致した場合は明示的にNOT_WORKとする
    // (「病院で検査結果を確認する」のように「確認」等の弱い語だけでは仕事に
    // しない、という誤判定防止の要件に対応)。
    private val privateSignals = listOf(
        "病院", "美容院", "友達", "買い物", "旅行", "家族", "猫", "通院", "検査", "歯医者", "ご飯",
    )

    /**
     * #148 フェーズ3a: 予定のタイトルから、端末内ルールだけで仕事判定を行う。
     * 既存の[classifyTaskCategory](仕事タスク登録時の判定)が使う[workSignals]
     * をそのまま再利用し、新しい重複リストは作らない。ネットワーク・外部AIは
     * 一切使わない、常に即時・無料の一次判定 — [judgeWorkIntent]から見た
     * 「まず試す、確実な場合だけ採用する」判定はこちら。
     *
     * 単一キーワード1個だけでWORKと判定しない — [workSignals]に**2つ以上**
     * 一致した場合だけWORKとする。「確認」のような弱い単語や、「打ち合わせ」
     * のような語1つだけでは私用の可能性を排除できないため(「打ち合わせ」単独
     * では友人との約束等もあり得る)。[workSignals]に1つも一致しない場合、
     * [privateSignals]に一致すれば明示的にNOT_WORK、どちらにも一致しなければ
     * 安全側のUNKNOWNとする — 「仕事か私用か安全に判断できない場合は無理に
     * WORKへ分類しない」という方針をそのまま反映している。
     */
    private fun judgeWorkIntentByRule(title: String): WorkJudgment {
        val workMatches = workSignals.count { title.contains(it) }
        if (workMatches >= 2) return WorkJudgment.WORK
        if (privateSignals.any { title.contains(it) }) return WorkJudgment.NOT_WORK
        return WorkJudgment.UNKNOWN
    }

    /**
     * #148 Phase 3-1: 予定のタイトルから仕事判定を行う、2段構成の入口。
     *
     * 1. [judgeWorkIntentByRule](端末内・無料・即時)を必ず先に試す。WORK/
     *    NOT_WORKのどちらかを確信を持って返した場合は、それをそのまま採用し
     *    Geminiには一切問い合わせない — フェーズ3aで既に実機検証済みの安全な
     *    一次判定を、Phase 3-1でも置き換えずそのまま活かすための構成。
     * 2. ルールベースがUNKNOWN(＝「仕事か私用か安全に判断できない」)だった
     *    場合だけ、[GeminiWorkJudge]による意味判定を二次判定として試す。
     *    [withTimeoutOrNull]で待ち時間の上限を設け、通信失敗・timeout・
     *    quota超過・APIキー未設定・不正/空応答・予期しない例外は
     *    [runCatching]と合わせて全て素通りさせず、最終的に必ずWorkJudgmentの
     *    いずれかの値へ落とし込む(何が起きてもこの関数自体が例外で落ちることは
     *    ない)。Geminiの自由な応答文字列を直接ここでDB操作に使うことはせず、
     *    [parseAiWorkJudgment]で厳密に3値へ強制変換してから返す。
     * 3. 呼び出し元の[rememberScheduleWithWorkJudgment]は、この関数が何を
     *    返しても既に予定の保存(`repository.remember`)を完了させた後に呼ぶ
     *    ため、AI判定がどう失敗しても予定登録そのものには一切影響しない。
     */
    private suspend fun judgeWorkIntent(title: String): WorkJudgment {
        val ruleResult = judgeWorkIntentByRule(title)
        if (ruleResult != WorkJudgment.UNKNOWN) return ruleResult

        val aiOutcome = runCatching {
            withTimeoutOrNull(8_000) { GeminiWorkJudge.judge(title).getOrNull() }
        }.getOrNull()

        val answer = aiOutcome as? GeminiOutcome.Answer ?: return WorkJudgment.UNKNOWN
        return parseAiWorkJudgment(answer.text)
    }

    /**
     * #148 Phase 3-1: [GeminiWorkJudge]の生テキスト応答を、DB操作に使う前に
     * 必ずWorkJudgmentの3値のいずれかへ強制変換する — Geminiの自由な出力を
     * そのまま`alsoShowAsTask`等のDB操作へ使わないための唯一の変換経路。
     * 完全一致を優先し、それ以外は部分一致にフォールバックする(system_
     * instructionで1語だけ返すよう指示済みだが、余分な語が混ざった応答にも
     * 耐えるため)。"NOT_WORK"は"WORK"を部分文字列として含むため、部分一致は
     * 必ずNOT_WORKを先に判定する。判定できない応答は全て安全側のUNKNOWNに
     * 倒す。
     */
    private fun parseAiWorkJudgment(raw: String): WorkJudgment {
        val normalized = raw.trim().uppercase()
        return when {
            normalized == "WORK" -> WorkJudgment.WORK
            normalized == "NOT_WORK" -> WorkJudgment.NOT_WORK
            normalized.contains("NOT_WORK") -> WorkJudgment.NOT_WORK
            normalized.contains("WORK") -> WorkJudgment.WORK
            else -> WorkJudgment.UNKNOWN
        }
    }

    /**
     * #148 フェーズ3a: 予定を保存した直後に、正本の同じCatEventに対して仕事判定を
     * 適用する。予定の保存([CatEventRepository.remember])自体は判定より必ず先に
     * 完了しており、判定結果に関わらず予定は残る — 判定に失敗しても(＝UNKNOWNに
     * なっても)予定登録そのものは既に成功済みなので影響しない。判定がWORKの
     * 場合だけ、既存の[CatEventRepository.setAlsoShowAsTask]
     * (#148フェーズ1/2で実装済み、#143のroomEventId再取得保護をそのまま受け継ぐ)
     * を呼んで同じCatEventをタスクビューにも表示する。新しいCatEventのinsertは
     * 一切行わない。
     */
    private suspend fun rememberScheduleWithWorkJudgment(
        title: String,
        dateTime: Long,
        assignee: String? = null,
    ): Pair<CatEvent, WorkJudgment> {
        val saved = repository.remember(title, dateTime, assignee)
        val judgment = judgeWorkIntent(saved.title)
        if (judgment == WorkJudgment.WORK) {
            repository.setAlsoShowAsTask(saved, true)
        }
        return saved to judgment
    }

    /** #148 フェーズ3a: WORK判定された予定だけ、登録完了に加えて「仕事にも
     * 出しとく」ことが分かる短い返答にする。NOT_WORK/UNKNOWNは従来通り
     * 「覚えたにゃ」のまま変更しない。 */
    private fun scheduleRegisteredReply(event: CatEvent, judgment: WorkJudgment, now: LocalDateTime): String {
        if (judgment != WorkJudgment.WORK) return "覚えたにゃ"
        val dateTime = event.dateTime
        val whenPrefix = if (dateTime != null) {
            val local = dateTime.toLocalDateTime()
            val day = DateTimeParser.formatWhen(local.toLocalDate(), now.toLocalDate())
            val timeText = if (local.minute == 0) "${local.hour}時" else "${local.hour}時${local.minute}分"
            "${day}${timeText}に"
        } else {
            ""
        }
        return "${whenPrefix}『${event.title}』入れたにゃ。仕事にも出しとく。"
    }

    // #148 Phase 3-2: registerScheduleIfRecognized専用のローカル一次判定が
    // 「予定意図が高い」シグナルとして使う、時間帯を表す語。あくまで
    // 「予定として登録してよいか」の判断材料であり、実際の保存時刻を
    // ここから生成することはしない — CatEventのdateTimeは従来通り
    // DateTimeParser側の明示的な"N時"抽出(無ければデフォルト9:00)のまま
    // ([調査5]で確認済みの通り、CatEvent/DateTimeParserの現在の構造では
    // 「午後」等の時間帯だけを正確な時刻として保存することはできない —
    // 既知の制約として最終報告で明記する)。
    private val timeOfDaySignals = listOf(
        "午前", "午後", "朝", "早朝", "昼", "夕方", "夕", "夜", "深夜", "未明",
    )

    private val explicitClockTimePattern = Regex("""\d{1,2}時""")

    // #148 Phase 3-2: 明白な雑談の可能性が高い、文末の口語的表現だけを対象と
    // する狭いリスト。「今日は暑いね」「明日は雨かな」「明日は寒そう」のように、
    // 日付語を1つ含むだけの感想・推量文の多くがこれらのいずれかで終わる。
    // 判定は[DateTimeParser.cleanTitle]が日付語や助詞を取り除く前の、発話
    // そのものの末尾に対して行う — cleanTitle後のタイトルは既にこれらの語尾
    // を削ってしまっていることが多く(例:「今日は暑いね」→タイトル「暑い」)、
    // その残骸だけで判定しようとすると「い」「た」のような1文字の語尾に頼る
    // ことになり、「明日、支払い」「明日、確認した」のような正常な予定表現
    // まで誤って弾いてしまう危険があるため、あえて避けている。
    private val chitchatEndingSignals = listOf(
        "かな", "だろう", "でしょう", "かも", "らしい", "そう", "ね", "よ", "わ",
    )

    /**
     * #148 Phase 3-2: [registerScheduleIfRecognized]専用のローカル一次判定。
     * 「明白なケースを安く処理する」ためだけに使い、大量のキーワード辞書で
     * 日本語全体を判定しようとはしない — 判断できない場合は必ずUNKNOWNを
     * 返し、呼び出し元([judgeRegistrationIntent])がGeminiへ委ねる。
     *
     * - 明示的な時刻または時間帯シグナルを含む → SCHEDULE
     *   (「今日15時に打ち合わせ」「明日の午後、美容院」等)
     * - [chitchatEndingSignals]のいずれかで終わる → NOT_SCHEDULE
     *   (「今日は暑いね」「明日は雨かな」等)
     * - どちらにも該当しない(「明日、銀行」のような体言止めの用件文を含む)
     *   → UNKNOWN
     */
    private fun judgeRegistrationIntentByRule(rawInput: String): RegistrationIntent {
        if (explicitClockTimePattern.containsMatchIn(rawInput) || timeOfDaySignals.any { rawInput.contains(it) }) {
            return RegistrationIntent.SCHEDULE
        }
        if (chitchatEndingSignals.any { rawInput.endsWith(it) }) {
            return RegistrationIntent.NOT_SCHEDULE
        }
        return RegistrationIntent.UNKNOWN
    }

    /**
     * #148 Phase 3-2: マリたんの発話が「そもそも予定登録の意図を持つ発話か」
     * どうかの判定結果。[CatEvent]のDBへ保存する値ではなく、
     * [registerScheduleIfRecognized]内部だけで使う一時的な判定。予定として
     * 登録することが確定した*後*にのみ意味を持つ[WorkJudgment](仕事かどうか)
     * とは完全に独立した、別の問い — 1回のAI判定に混ぜない。
     */
    private enum class RegistrationIntent { SCHEDULE, NOT_SCHEDULE, UNKNOWN }

    /**
     * #148 Phase 3-2: 予定登録の意図があるかどうかを判定する、2段構成の入口。
     * [WorkJudgment]用の[judgeWorkIntent]と全く同じパターン:
     *
     * 1. [judgeRegistrationIntentByRule](端末内・無料・即時)を必ず先に試す。
     *    SCHEDULE/NOT_SCHEDULEのどちらかを確信を持って返した場合は、それを
     *    そのまま採用しGeminiには一切問い合わせない。
     * 2. ルールベースがUNKNOWNだった場合だけ、[GeminiRegistrationIntent]に
     *    よる意味判定を二次判定として試す。[withTimeoutOrNull]で待ち時間の
     *    上限を設け、通信失敗・timeout・quota超過・APIキー未設定・不正/空
     *    応答・予期しない例外は全て素通りさせず、最終的に必ず
     *    RegistrationIntentのいずれかの値へ落とし込む(誤登録防止を優先し、
     *    判定できなければ必ずUNKNOWN＝未登録)。Geminiの自由な応答文字列を
     *    直接ここでDB操作に使うことはせず、[parseAiRegistrationIntent]で
     *    厳密に3値へ強制変換してから返す。
     *
     * [GeminiRegistrationIntent]へ送信するのは[rawInput](今回発話された
     * 文章そのもの)だけ — POI内部の予定一覧/タスク一覧/メモ/アルバム/
     * ファイル/Firestore/Room情報は一切含めない。
     */
    private suspend fun judgeRegistrationIntent(rawInput: String): RegistrationIntent {
        val ruleResult = judgeRegistrationIntentByRule(rawInput)
        if (ruleResult != RegistrationIntent.UNKNOWN) return ruleResult

        val aiOutcome = runCatching {
            withTimeoutOrNull(8_000) { GeminiRegistrationIntent.judge(rawInput).getOrNull() }
        }.getOrNull()

        val answer = aiOutcome as? GeminiOutcome.Answer ?: return RegistrationIntent.UNKNOWN
        return parseAiRegistrationIntent(answer.text)
    }

    /**
     * #148 Phase 3-2: [GeminiRegistrationIntent]の生テキスト応答を、判定に
     * 使う前に必ずRegistrationIntentの3値のいずれかへ強制変換する —
     * [parseAiWorkJudgment]と同じ考え方。"NOT_SCHEDULE"は"SCHEDULE"を部分
     * 文字列として含むため、部分一致は必ずNOT_SCHEDULEを先に判定する。
     * 判定できない応答は全て安全側のUNKNOWNに倒す。
     */
    private fun parseAiRegistrationIntent(raw: String): RegistrationIntent {
        val normalized = raw.trim().uppercase()
        return when {
            normalized == "SCHEDULE" -> RegistrationIntent.SCHEDULE
            normalized == "NOT_SCHEDULE" -> RegistrationIntent.NOT_SCHEDULE
            normalized.contains("NOT_SCHEDULE") -> RegistrationIntent.NOT_SCHEDULE
            normalized.contains("SCHEDULE") -> RegistrationIntent.SCHEDULE
            else -> RegistrationIntent.UNKNOWN
        }
    }

    // #148 Maps-1A: マリたん専用の地図/ナビ命令を検出するための末尾トリガー。
    // 大量の場所キーワード辞書は作らない — 目的地(場所名)自体は判定せず
    // そのまま抽出するだけで、判定するのは「案内して」「ナビして」「地図で
    // 見せて」等の命令表現(動詞側)だけに絞る。ナビと検索で語彙が重ならない
    // よう別リストにしておく。
    private val mapNavigationSuffixes = listOf(
        "まで案内して", "までナビして", "への道を案内して", "へ案内して", "へナビして",
        "に行って", "へ行って",
    )
    private val mapSearchSuffixes = listOf(
        "をGoogleマップで開いて", "を地図で見せて", "を地図で開いて", "をマップで見せて", "をマップで開いて",
    )

    /**
     * #148 Maps-1A: マリたん専用の、地図/ナビ命令の検出。AI・DB・ネットワーク
     * はいずれも使わない純粋な文字列判定で、[CatBrain]自身はAndroid Intentを
     * 一切起動しない — 判定結果の[MapCommand]を呼び出し元(MariTanRow)へ返す
     * だけで、実際に地図を開くのは[com.mspg.poicat.maps.MapsLauncher]の責務。
     *
     * 「明日、銀行まで案内して」のように日付語を含む発話でも、この関数が
     * [mapNavigationSuffixes]/[mapSearchSuffixes]の末尾一致で高確信度に判定
     * するため、呼び出し元がこの関数を[registerScheduleIfRecognized]より
     * *先に*試す限り、「明日、銀行」(予定登録)との誤認は起きない — 地図命令の
     * 末尾表現と予定登録・雑談の既存トリガーは語彙が重ならないため。
     *
     * 目的地が空/空白だけの場合は[MapCommand]を返さない(呼び出し元が
     * [com.mspg.poicat.maps.MapsLauncher]を呼ばずに済む)。
     */
    fun detectMapCommand(input: String): MapCommand? {
        val trimmed = input.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return null

        for (suffix in mapNavigationSuffixes) {
            if (trimmed.endsWith(suffix) && trimmed.length > suffix.length) {
                val destination = trimmed.removeSuffix(suffix).trim()
                if (destination.isNotBlank()) return MapCommand(destination, MapCommandMode.NAVIGATION)
            }
        }
        for (suffix in mapSearchSuffixes) {
            if (trimmed.endsWith(suffix) && trimmed.length > suffix.length) {
                val destination = trimmed.removeSuffix(suffix).trim()
                if (destination.isNotBlank()) return MapCommand(destination, MapCommandMode.SEARCH)
            }
        }
        return null
    }

    // #POI マリたん秘書性能② Stage 1: 目的地なしの単純アプリ起動トリガー。
    // [mapNavigationSuffixes]/[mapSearchSuffixes](目的地付き)とは語彙が重ならない
    // ため、[detectMapCommand]の判定を横取りすることはない。「ドライブ開いて」
    // 等の明確なアプリ起動表現だけに限定し、「ドライブしたい」「ドライブ行こう」
    // のような自動車のドライブとの誤認を避けるため、必ず末尾が「開いて」で
    // 終わる場合だけを対象にする(単語単体の「マップ」「ドライブ」だけでは
    // 判定しない)。
    private val simpleMapsLaunchSuffixes = listOf("googleマップ開いて", "グーグルマップ開いて", "マップ開いて", "地図開いて")
    private val simpleDriveLaunchSuffixes = listOf("googleドライブ開いて", "グーグルドライブ開いて", "ドライブ開いて")

    /**
     * #POI マリたん秘書性能② Stage 1: 「Googleマップ開いて」「ドライブ開いて」の
     * ような、目的地を伴わない単純なアプリ起動命令の検出。[detectMapCommand]
     * (目的地付きの地図検索・経路案内)とは完全に独立した判定で、あちらの
     * ロジック・戻り値([MapCommand])には一切触れない。AI・DB・ネットワークは
     * いずれも使わない純粋な文字列判定 — 実際にアプリを起動するのは
     * [com.mspg.poicat.AppLauncher]の責務。
     */
    fun detectSimpleAppLaunch(input: String): SimpleAppTarget? {
        val trimmed = input.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed.lowercase()

        if (simpleMapsLaunchSuffixes.any { normalized.endsWith(it) }) return SimpleAppTarget.GOOGLE_MAPS
        if (simpleDriveLaunchSuffixes.any { normalized.endsWith(it) }) return SimpleAppTarget.GOOGLE_DRIVE
        return null
    }

    // ============ #POI マリたん秘書性能② Stage 2: 場所明示の天気質問 ============

    /** #POI 秘書性能② Stage 2: [answerWeatherQueryOrNull]が「何を聞かれたか」を
     * 区別するための、天気質問の焦点。返答の文面(気温を出すか、降水確率+傘の
     * 目安を出すか等)を切り替えるためだけに使う内部区分。 */
    private enum class WeatherFocus { GENERAL, RAIN, TEMPERATURE, UMBRELLA }

    private data class WeatherQuery(
        val place: String,
        val focus: WeatherFocus,
        val date: LocalDate?,
        val dateLabel: String?,
    )

    private val weatherTriggerWords = listOf("天気", "雨", "降る", "降水", "気温", "暑い", "寒い", "傘")

    private val weatherOfKeywordFocus = listOf(
        "天気" to WeatherFocus.GENERAL,
        "気温" to WeatherFocus.TEMPERATURE,
        "降水確率" to WeatherFocus.RAIN,
        "降水" to WeatherFocus.RAIN,
    )

    /**
     * #POI 秘書性能② Stage 2: 「甲府の天気は？」のような、場所が発話内に明示
     * された天気質問だけを高い確信度で判定する。場所が明示されていない発話
     * (「明日の天気は？」等)や、天気語を含むだけの一般会話(「雨の日って
     * 眠いね」等)は必ずnullを返す — 現在地・[ConversationContext]・予定の
     * locationTextからの補完はここでは一切行わない(Stage 2の対象外)。
     *
     * 判定は2段階: ①天気語([weatherTriggerWords])を含むこと(緩い一次フィルタ)、
     * ②発話全体が「場所＋天気表現」だけで完結する決まった形([extractPlaceAndFocus]、
     * 文字列全体の一致のみを許可)であること。②を満たさない場合は場所を確定
     * できないとみなし、余った文字列を無理に場所として採用することはしない。
     */
    private fun parseWeatherQuery(text: String, now: LocalDateTime): WeatherQuery? {
        val trimmed = text.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return null

        var remaining = trimmed
        var date: LocalDate? = null
        var dateLabel: String? = null
        val dateWords = listOf("明後日" to 2L, "明日" to 1L, "今日" to 0L)
        for ((word, offset) in dateWords) {
            if (remaining.contains(word)) {
                date = now.toLocalDate().plusDays(offset)
                dateLabel = word
                remaining = remaining.replace(word, "")
                break
            }
        }
        // 「明日の甲府...」から「明日」を除去すると先頭に助詞「の」だけが残る
        // ("の甲府...")ため、日付語の直後の1個だけ剥がす。「甲府、明日雨？」の
        // ように日付語が文中にある場合はこの助詞は残らないため影響しない。
        remaining = remaining.removePrefix("の").trim()

        if (weatherTriggerWords.none { remaining.contains(it) }) return null

        val (place, focus) = extractPlaceAndFocus(remaining) ?: return null
        if (place.isBlank() || place.length > 20) return null
        return WeatherQuery(place, focus, date, dateLabel)
    }

    /**
     * #POI 秘書性能② Stage 2: 天気語を含むと確認済みの残り文字列から、
     * 「場所＋決まった天気表現」の形にちょうど一致する場合にだけ場所を抽出する。
     * [Regex.matchEntire]で文字列全体の一致だけを許可しているため、
     * 「雨の日って眠いね」のように天気語を含むだけの一般会話が余りを場所として
     * 誤採用することはない。
     */
    private fun extractPlaceAndFocus(text: String): Pair<String, WeatherFocus>? {
        for ((keyword, focus) in weatherOfKeywordFocus) {
            Regex("^(.+?)の" + keyword + "(?:は)?[?？]?$").matchEntire(text)?.let { m ->
                val place = m.groupValues[1].trim()
                if (place.isNotBlank()) return place to focus
            }
        }
        for (keyword in listOf("雨", "降る")) {
            Regex("^(.+?)、?" + keyword + "[?？]?$").matchEntire(text)?.let { m ->
                val place = m.groupValues[1].trim().trim('、')
                if (place.isNotBlank()) return place to WeatherFocus.RAIN
            }
        }
        Regex("^(.+?)は(?:寒い|暑い)[?？]?$").matchEntire(text)?.let { m ->
            val place = m.groupValues[1].trim()
            if (place.isNotBlank()) return place to WeatherFocus.TEMPERATURE
        }
        Regex("^(.+?)、?傘(?:いる)?[?？]?$").matchEntire(text)?.let { m ->
            val place = m.groupValues[1].trim().trim('、')
            if (place.isNotBlank()) return place to WeatherFocus.UMBRELLA
        }
        return null
    }

    /**
     * #POI 秘書性能② Stage 2: 場所が明示された天気質問への、唯一の読み取り
     * 専用エントリポイント。[parseWeatherQuery]が場所を確定できた場合だけ
     * Open-Meteo(APIキー不要・無料)へ問い合わせる。場所が確定できない発話は
     * 必ずnullを返し、呼び出し元(MariTanRow)は既存のPOI質問判定
     * ([answerPoiQueryOrNull]、天気語をトリビアとして弾く既存のlooksOutOfScope
     * を含む)・Gemini雑談へそのまま進む — このメソッドはそれらより前に呼ばれる
     * ことで、既存のoutOfScope判定より先に安全な天気質問だけを処理できる。
     *
     * 通信エラー・タイムアウト・Open-Meteo側の障害は「おネムにゃ。」、無料枠
     * 超過(HTTP 429)だと明確に判定できた場合だけ「課金しなきゃ答えたく無いニャ
     * 💢」を返し、どちらの場合も自動的に有料APIへ切り替える処理は一切行わない。
     * 場所が地名として解決できない場合は天気を捏造せず「場所が分からないにゃ。」
     * を返す。
     */
    suspend fun answerWeatherQueryOrNull(input: String): CatReply? {
        val query = parseWeatherQuery(input, LocalDateTime.now()) ?: return null
        return fetchWeatherReply(query.place, query.date, query.dateLabel, query.focus)
    }

    /**
     * #POI 秘書性能② Stage 3: Stage 2の地名解決(GeoCoder)＋天気取得(WeatherService)
     * ＋エラー整形ロジックを、場所文字列さえ渡せば呼べる共通処理として切り出した
     * もの。[answerWeatherQueryOrNull](場所明示型)と[answerContextualWeatherQueryOrNull]
     * (「そこ雨？」型)の両方から呼ばれる — 天気取得の実装は1つだけで、Stage 3用に
     * 別実装は作らない。
     */
    private suspend fun fetchWeatherReply(place: String, date: LocalDate?, dateLabel: String?, focus: WeatherFocus): CatReply {
        val geoResult = withTimeoutOrNull(10_000) { GeoCoder.resolve(place) }
        val located = when (val geoOutcome = geoResult?.getOrNull()) {
            is GeoCoder.Outcome.Found -> geoOutcome.location
            GeoCoder.Outcome.NotFound -> return CatReply("場所が分からないにゃ。")
            GeoCoder.Outcome.QuotaExceeded -> return CatReply("課金しなきゃ答えたく無いニャ💢")
            null -> return CatReply("おネムにゃ。")
        }

        val weatherResult = withTimeoutOrNull(10_000) { WeatherService.fetch(located.latitude, located.longitude, date) }
        val answer = when (val weatherOutcome = weatherResult?.getOrNull()) {
            is WeatherService.Outcome.Success -> weatherOutcome.answer
            WeatherService.Outcome.QuotaExceeded -> return CatReply("課金しなきゃ答えたく無いニャ💢")
            null -> return CatReply("おネムにゃ。")
        }

        return CatReply(formatWeatherReply(place, dateLabel, focus, answer))
    }

    /** #POI 秘書性能② Stage 2: [WeatherService.Answer]をマリたんの短い一言へ整形する。
     * Open-Meteoが返していない値(nullの項目)を推測で埋めることはしない。 */
    private fun formatWeatherReply(place: String, dateLabel: String?, focus: WeatherFocus, answer: WeatherService.Answer): String {
        val prefix = if (dateLabel != null) "${dateLabel}の${place}" else place
        return when (focus) {
            WeatherFocus.GENERAL -> {
                val temp = answer.temperature ?: answer.temperatureMax
                if (temp != null) "${prefix}は${answer.description}、${temp.toInt()}℃くらいにゃ。" else "${prefix}は${answer.description}にゃ。"
            }
            WeatherFocus.RAIN, WeatherFocus.UMBRELLA -> {
                val precip = answer.precipitationProbability
                val base = if (precip != null) {
                    "${prefix}は${answer.description}予報。降水確率${precip}％にゃ。"
                } else {
                    "${prefix}は${answer.description}にゃ。"
                }
                val needsUmbrella = (precip != null && precip >= 50) || answer.description.contains("雨") || answer.description.contains("雪")
                base + if (needsUmbrella) "傘いるにゃ。" else "傘いらなそうにゃ。"
            }
            WeatherFocus.TEMPERATURE -> {
                val temp = answer.temperature ?: answer.temperatureMin ?: answer.temperatureMax
                if (temp == null) {
                    "${prefix}は${answer.description}にゃ。"
                } else {
                    val rounded = temp.toInt()
                    val comment = when {
                        rounded <= 5 -> "かなり寒いにゃ。"
                        rounded <= 12 -> "ちょっと寒いにゃ。"
                        rounded >= 32 -> "かなり暑いにゃ。"
                        rounded >= 28 -> "ちょっと暑いにゃ。"
                        else -> "過ごしやすいにゃ。"
                    }
                    "${prefix}は${rounded}℃くらい。$comment"
                }
            }
        }
    }

    /**
     * #POI 秘書性能② Stage 3: 「そこ雨？」のような、直前に確定した予定の場所を
     * 指す天気継続質問だけを認識する。[Regex.matchEntire]で発話全体の一致だけを
     * 許可しているため、Stage 2の[extractPlaceAndFocus]と同じ理由で、一般会話を
     * 誤って天気質問として拾うことはない。この判定自体は[ConversationContext]の
     * 有無を問わない(文字列だけの判定) — 実際に「そこ」を使えるかどうかは、
     * 呼び出し元の[answerContextualWeatherQueryOrNull]が
     * [ConversationContext.lastAnsweredEvent]の有無で別途ガードする。
     */
    private fun detectHereWeatherFocus(text: String): WeatherFocus? {
        val core = text.trim().replace(Regex("[「」『』]"), "").trim().trimEnd('？', '?', '。')
        return when (core) {
            "そこ雨" -> WeatherFocus.RAIN
            "そこ天気どう" -> WeatherFocus.GENERAL
            "そこ天気は" -> WeatherFocus.GENERAL
            "そこ寒い" -> WeatherFocus.TEMPERATURE
            "そこ暑い" -> WeatherFocus.TEMPERATURE
            "傘いる" -> WeatherFocus.UMBRELLA
            "そこ傘いる" -> WeatherFocus.UMBRELLA
            else -> null
        }
    }

    /**
     * #POI 秘書性能② Stage 3: 「そこ雨？」等、直前にマリたんが答えた予定
     * ([ConversationContext.lastAnsweredEvent])を指す天気継続質問への、唯一の
     * 読み取り専用エントリポイント。[context]に予定が1件も確定していない
     * (nullの)場合は必ずnullを返す — この場合は天気検索へ一切進まない
     * (呼び出し元は既存のPOI質問判定・Gemini雑談へそのまま進む)。
     *
     * 場所([AnsweredEventSnapshot.locationText])が無い/空白の場合は、現在地や
     * 別の予定の場所を代わりに使ったりGeminiに推測させたりせず、必ず
     * 「その予定、場所が入ってないにゃ。」を返す。地名/施設名/Google Maps URLが
     * 混在した生テキストは、既存の[LocationDisplayName.extractDisplayName]
     * (Maps共有機能が既に使っている、URLを安全に取り除くだけの既存ロジック)で
     * 場所名だけを取り出す — 短縮URLの展開や座標抽出等の新しい処理は追加しない。
     * それでも安全に場所名を取り出せない(URLだけで文字が残らない等)場合は
     * 「場所が分からないにゃ。」を返す。
     *
     * 天気の取得自体は[fetchWeatherReply]、つまりStage 2と全く同じ
     * GeoCoder/WeatherServiceを再利用する — 別の天気取得実装は作らない。
     * 予定の日時([AnsweredEventSnapshot.dateTime])の日付だけを使う日単位予報
     * ([WeatherService.fetch]の既存仕様通り)で、hourly化のような大きな変更は
     * 今回は行わない。
     */
    suspend fun answerContextualWeatherQueryOrNull(input: String, context: ConversationContext?): CatReply? {
        val snapshot = context?.lastAnsweredEvent ?: return null
        val focus = detectHereWeatherFocus(input) ?: return null

        val locationText = snapshot.locationText
        if (locationText.isNullOrBlank()) return CatReply("その予定、場所が入ってないにゃ。")
        val place = LocationDisplayName.extractDisplayName(locationText) ?: return CatReply("場所が分からないにゃ。")

        val eventDate = snapshot.dateTime.toLocalDate()
        val dateLabel = DateTimeParser.formatWhen(eventDate, LocalDate.now())
        return fetchWeatherReply(place, eventDate, dateLabel, focus)
    }

    /**
     * #148 Phase 3-1/3-2: マリたん(Gemini経由の音声アシスタント)専用の、
     * 書き込みを伴う唯一の安全な予定登録エントリポイント。[answerPoiQueryOrNull]
     * とは違い、ここでは実際にCatEventを1件保存する — しかし[respond]と違って
     * 「解釈できなかった入力を何であれメモとして保存する」という最終
     * フォールバックは持たない。呼び出し元(MariTanRow)はnullの場合、これまで
     * 通り[answerPoiQueryOrNull]やGemini雑談へ進む。
     *
     * 4段階の判定:
     * 1. [DateTimeParser.isQuery]が真ならnull — [respond]自身も、質問判定を
     *    予定登録より必ず先に行っている(「今日の予定は？」のような質問文の
     *    中に偶然"今日"という日付語が含まれていても、それを予定として誤登録
     *    しないための、既存コードと同じ安全順序)。
     * 2. [DateTimeParser.parseRegistration]がnullならnull(日付語自体が
     *    見つからない)。
     * 3. [judgeRegistrationIntent]が[RegistrationIntent.SCHEDULE]以外を
     *    返せばnull — ローカル一次判定・Gemini二次判定のどちらも通過
     *    しなかった入力(NOT_SCHEDULE/UNKNOWN)について[GeminiWorkJudge]が
     *    呼ばれることは無い(3を通過して初めて4のrememberScheduleWithWorkJudgment
     *    へ進むため、登録意図判定と仕事判定は独立したまま)。
     * 4. 1〜3を全て通過した場合だけ[rememberScheduleWithWorkJudgment]を呼ぶ
     *    — 予定の保存([CatEventRepository.remember])自体は仕事判定(Gemini
     *    呼び出しを含み得る)より必ず先に完了しており、判定が失敗しても予定
     *    登録は既に成功済みで影響しない。新しいCatEventのinsertは1件だけ、
     *    既存の[rememberScheduleWithWorkJudgment]と全く同じ経路をそのまま
     *    再利用する — マリたん専用の別の保存ロジックは作らない。
     */
    suspend fun registerScheduleIfRecognized(input: String): CatReply? {
        val now = LocalDateTime.now()
        val (saved, judgment) = registerScheduleCore(input, now) ?: return null
        return CatReply(scheduleRegisteredReply(saved, judgment, now))
    }

    /**
     * #148 Maps-2C: [registerScheduleIfRecognized]の1〜4の判定・保存経路を
     * そのまま抽出した共有コア。[CatReply](マリたんの音声応答用テキスト)を
     * 組み立てる責務は呼び出し元に残し、ここでは実際に作成/特定された
     * [CatEvent]と[WorkJudgment]の組だけを返す — 動作は元の
     * [registerScheduleIfRecognized]と完全に同一(単純な抽出のみ、判定順序・
     * 条件は一切変更していない)。
     */
    private suspend fun registerScheduleCore(input: String, now: LocalDateTime): Pair<CatEvent, WorkJudgment>? {
        val trimmed = input.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return null
        if (DateTimeParser.isQuery(trimmed)) return null

        val registration = DateTimeParser.parseRegistration(trimmed, now) ?: return null
        if (judgeRegistrationIntent(trimmed) != RegistrationIntent.SCHEDULE) return null

        // #POI 場所抽出: 「東京でテスト」のようなタイトルから、安全に地名だと
        // 確定できた場合だけ場所を分離する(詳細は[splitLocationFromTitle])。
        val (title, locationCandidate) = splitLocationFromTitle(registration.title)

        // #POI 仕様変更: 指定なし＝2人(共有)をデフォルト担当とする。
        val result = rememberScheduleWithWorkJudgment(
            title,
            registration.dateTime.toEpochMilli(),
            resolveRegistrationAssignee(trimmed, currentDisplayName()),
        )
        if (locationCandidate != null) {
            // #148 Maps-2A/2Cの[CatEventRepository.setLocation]と全く同じ
            // 経路を再利用する(新しい保存経路は作らない)。予定の保存自体は
            // 既に完了済みのため、ここが失敗しても予定登録そのものには
            // 影響しない。
            repository.setLocation(result.first, locationCandidate)
        }
        return result
    }

    // #POI 場所抽出: 「<場所>で<内容>」の「で」を場所の区切りだと誤認しやすい、
    // 手段/話題を表す代表的な語。GeoCoderへ問い合わせる前の高速な足切りとして
    // 使う(ネットワーク呼び出しを減らすためだけの最適化であり、安全性の根拠は
    // 下のGeoCoder確認そのもの — このリストが不完全でも、地名として実際に
    // 解決できない語はどのみち分離されない)。
    private val nonLocationReasonWords = setOf(
        "会議", "電話", "仕事", "メール", "LINE", "チャット", "オンライン",
        "リモート", "テレワーク", "在宅", "打ち合わせ", "面談", "面接", "相談",
        "メッセージ", "資料", "研修", "会社",
    )

    /**
     * #POI 場所抽出: 予定タイトルから「<場所>で<内容>」の形を安全に分離する。
     * 「明日10時、東京でテスト」→ title「テスト」+ locationText「東京」のように、
     * 場所として実際に確定できた場合だけ分離する。判定は完全にルールベース
     * (Gemini等による曖昧な場所推測は一切行わない) — 最初の「で」の前の
     * 部分を候補として取り出し、[nonLocationReasonWords]に該当しない場合
     * だけ、既存のStage 2/3で使っている[GeoCoder](Open-Meteo Geocoding API、
     * APIキー不要・無料)へ問い合わせ、実際に日本国内の地名として解決できた
     * 場合だけ分離を確定する。GeoCoderが解決できない(=「会議」「電話」
     * 「仕事」等、場所ではない語だった)場合やタイムアウト等の通信エラーの
     * 場合は、分離せず元のタイトルをそのまま返す — 安全に確定できない場合は
     * 従来通りタイトルへ残す、という仕様上のデフォルト。
     */
    private suspend fun splitLocationFromTitle(rawTitle: String): Pair<String, String?> {
        val match = Regex("^(.{1,12}?)で(.+)$").find(rawTitle) ?: return rawTitle to null
        val candidate = match.groupValues[1].trim()
        val rest = match.groupValues[2].trim()
        if (candidate.isBlank() || rest.isBlank()) return rawTitle to null
        if (candidate in nonLocationReasonWords) return rawTitle to null

        val geoResult = withTimeoutOrNull(6_000) { GeoCoder.resolve(candidate) }
        val isRealPlace = geoResult?.getOrNull() is GeoCoder.Outcome.Found
        if (!isRealPlace) return rawTitle to null

        return rest to candidate
    }

    /**
     * #148 Maps-2C: Google Maps/Gemini等から共有された場所を予定に紐付ける
     * 「予定に追加」フロー専用のエントリポイント。[registerScheduleIfRecognized]
     * と全く同じ判定・保存経路([registerScheduleCore])を再利用するが、
     * マリたんの音声応答用[CatReply]の代わりに、実際に作成/特定された
     * [CatEvent]そのものを返す。
     *
     * 呼び出し元(Maps-2Cの「予定に追加」ダイアログ)は、これが非nullを返した
     * 場合にだけ — つまり[DateTimeParser.parseRegistration]が明確な予定として
     * 解析でき、[RegistrationIntent.SCHEDULE]と判定され、実際に
     * [CatEventRepository.remember]でCatEventの保存(または既存の重複行の
     * 再利用)が完了した場合にだけ — その返り値のCatEventへ
     * [CatEventRepository.setLocation]で場所を紐付ける。「最新の予定を検索
     * して推測する」「タイトル・時刻の一致で後から推測する」といった曖昧な
     * 特定方法は一切使わない(この関数の戻り値自体が、登録処理が直接返した
     * 正確な参照そのもの)。
     *
     * 予定として解析できない、または雑談/曖昧と判定された場合は
     * [registerScheduleIfRecognized]と同じくnullを返す — この場合、呼び出し元は
     * 何も保存せず、ユーザーに再入力を促す。
     */
    suspend fun registerScheduleAndReturnEvent(input: String): CatEvent? {
        return registerScheduleCore(input, LocalDateTime.now())?.first
    }

    private val taskCompletionSuffixes = listOf(
        "のタスク終わった", "のタスクが終わった", "タスクは終わった", "タスク終わった",
        "は完了したよ", "は完了", "完了したよ", "完了",
        "終わったよ", "終わった",
        "できたよ", "できた",
        "買ったよ", "買った",
        "やったよ", "やった",
        "済んだよ", "済んだ",
    )

    // "買ったよ"/"買った" report finishing a "買う" task ("牛乳買ったよ" reports "牛乳買う"
    // is done) — extractTaskCommand() now keeps "買う" in the saved title, so completion
    // lookup tries the reconstructed "買う" title first ("牛乳買う"), falling back to the
    // bare noun ("牛乳") for tasks saved without it (e.g. via the "買わなきゃ" trigger).
    private val buyCompletionSuffixes = setOf("買ったよ", "買った")

    /** Recognizes "牛乳買ったよ"/"薬終わった" style completion and returns the search
     * keyword(s) — most specific first — to look up the matching task by, or an empty
     * list if [text] isn't a completion report. */
    private fun extractTaskCompletionKeywords(text: String): List<String> {
        for (suffix in taskCompletionSuffixes) {
            if (text.endsWith(suffix) && text.length > suffix.length) {
                val keyword = text.removeSuffix(suffix).trim()
                if (keyword.isBlank()) continue
                return if (suffix in buyCompletionSuffixes) listOf("${keyword}買う", keyword) else listOf(keyword)
            }
        }
        return emptyList()
    }

    private val memoVerbs = listOf(
        "メモしておいてください", "メモしておいて", "メモしといて", "メモしてください", "メモして",
        "覚えておいてください", "覚えておいて", "覚えといて", "覚えてください", "覚えて",
        // Bare "メモ" last (lowest priority) so it only fires once none of the longer,
        // more specific verb forms above have already matched — covers "1234ってメモ".
        "メモ",
    )

    /** Recognizes an explicit "○○をメモして"/"○○覚えておいて" style command and returns just ○○, or null. */
    private fun extractMemoCommand(text: String): String? {
        for (verb in memoVerbs) {
            for (suffix in listOf("って$verb", "を$verb", verb)) {
                if (text.endsWith(suffix)) {
                    val content = text.removeSuffix(suffix).trim().removeSuffix("の").removeSuffix("を").trim()
                    if (content.isNotBlank()) return content
                }
            }
        }
        return null
    }

    // #POI マリたん性能アップ Phase 1: 「指定なし＝2人(共有)」仕様に合わせ、既存の
    // assignee=null(未設定)行は検索時には"2人"と同じ「共有」として扱う。この関数は
    // 検索(読み取り)専用 — 既存のnull行を書き換えることは一切しない。
    private fun assigneeMatchesShared(assignee: String?): Boolean =
        assignee == null || assignee == CatEvent.ASSIGNEE_BOTH

    /**
     * #POI マリたん性能アップ Phase 1: [ConversationContext.person]で表した人物指定
     * (null=指定なし、[CatEvent.ASSIGNEE_BOTH]=明示共有、みゆたん/かっちゃん=個人)を、
     * 1件のCatEventの[assignee]に対して判定する共通ロジック。[personIsSelf]がtrueの
     * 場合だけ、個人名指定でも"2人"/未設定(null)を追加で一致対象に含める — 既存の
     * 「私の仕事」「私の予定」判定(2人は自分の責任でもある)と同じ考え方で、他者を
     * 名前で明示的に尋ねた場合(例:「かっちゃんの予定」)には適用しない。
     */
    private fun eventMatchesPersonFilter(assignee: String?, person: String?, personIsSelf: Boolean): Boolean = when {
        person == null -> true
        person == CatEvent.ASSIGNEE_BOTH -> assigneeMatchesShared(assignee)
        personIsSelf -> assignee == person || assignee == CatEvent.ASSIGNEE_BOTH
        else -> assignee == person
    }

    /**
     * #POI マリたん性能アップ Phase 1: 発話中に明示された人物指定(みゆたん/かっちゃん/
     * 2人/二人)だけを判定する — 「私/自分」の解決は呼び出し元がcurrentDisplayName()と
     * 組み合わせて別途行う(この関数は話者情報を持たない)。新規登録時のデフォルト
     * 担当判定([resolveRegistrationAssignee])と質問側の人物判定([answerPoiQueryOrNull])
     * の両方から共有する、唯一の「人物指定検出」ロジック — 重複した判定を持たない。
     */
    private fun explicitPersonInText(text: String): String? = when {
        text.contains(CatEvent.ASSIGNEE_KATCHAN) -> CatEvent.ASSIGNEE_KATCHAN
        text.contains(CatEvent.ASSIGNEE_MIYU) -> CatEvent.ASSIGNEE_MIYU
        text.contains(CatEvent.ASSIGNEE_BOTH) || text.contains("二人") -> CatEvent.ASSIGNEE_BOTH
        else -> null
    }

    /**
     * #POI 仕様変更: POIの担当者デフォルトを「2人」に統一。予定/タスク/メモの新規
     * 登録時、発話に明示的な人物指定(みゆたん/かっちゃん/2人/二人/私/自分)があれば
     * その人物、無ければ[CatEvent.ASSIGNEE_BOTH]("2人")をデフォルトの担当として返す
     * — 常に非null。呼び出し元(BB=[respond]/[respondToPhoto]、マリたん=
     * [registerScheduleCore]等)がこの値を新規[CatEvent]のassigneeとして渡す。
     *
     * 既存データへの影響は一切無い — この関数は新規登録の瞬間にだけ使われ、既存の
     * assignee=null行を書き換える処理はどこにも無い([CatEventRepository.remember]が
     * 重複行を見つけた場合はこの値を使わずその既存行をそのまま返す設計、同ファイル
     * 参照)。
     */
    private fun resolveRegistrationAssignee(text: String, myDisplayName: String?): String {
        explicitPersonInText(text)?.let { return it }
        if ((text.contains("私") || text.contains("自分")) && myDisplayName != null) return myDisplayName
        return CatEvent.ASSIGNEE_BOTH
    }

    private val bareFollowupTrailers = listOf("は？", "は", "？", "?")

    private fun stripBareFollowupTrailer(text: String): String {
        for (trailer in bareFollowupTrailers) {
            if (text.endsWith(trailer) && text.length > trailer.length) return text.removeSuffix(trailer)
        }
        return text
    }

    /**
     * #POI マリたん性能アップ Phase 1: 「かっちゃんは？」「私は？」「二人は？」
     * 「みゆたんは？」のように、発話全体が人物参照＋素朴な疑問表現だけで構成されて
     * いる場合にだけ(person, personIsSelf)を返す。それ以外(「かっちゃんは元気？」等、
     * 他の内容が続く発話)はnull。呼び出し元は[ConversationContext]が存在する場合に
     * のみこの関数を試す — 文脈が無ければ通常の雑談としてGeminiへ回る。
     */
    private fun bareFollowupPerson(text: String, myDisplayName: String?): Pair<String, Boolean>? {
        val core = stripBareFollowupTrailer(text)
        return when (core) {
            CatEvent.ASSIGNEE_KATCHAN -> CatEvent.ASSIGNEE_KATCHAN to false
            CatEvent.ASSIGNEE_MIYU -> CatEvent.ASSIGNEE_MIYU to false
            CatEvent.ASSIGNEE_BOTH, "二人" -> CatEvent.ASSIGNEE_BOTH to false
            "私", "自分" -> myDisplayName?.let { it to true }
            else -> null
        }
    }

    /**
     * #POI 実機不具合修正: [bareFollowupDateRange]の結果に加え、この一致が担当者
     * 文脈を引き継ぐべきか([inheritsPerson])を表す。「今日」「明日」「明後日」は
     * 「今この瞬間からの絶対的な1日」を指す独立した表現であり、「かっちゃんの予定は？」
     * →「今日は？」のように直前に別の話題(他者名指定)があった直後でも、単独の
     * 「今日は？」は「(その話題の続きではなく)今日全体はどうか」という新規の
     * 単発質問として読まれるのが自然 — このためinheritsPerson=falseとし、
     * 呼び出し元は担当者文脈をnull(指定なし)へ戻す。一方「金曜は？」（曜日名）・
     * 「来週は？」（週/月相対語）・「翌日は？」（直前の日付そのものに依存する語）は、
     * 元々の会話の続き(例:「来週のかっちゃんの予定は？」→「金曜は？」)として使われる
     * ことが前提の表現のため、inheritsPerson=trueのまま既存の担当者文脈を維持する
     * (Phase1で明示的に要求された継続質問の仕様はそのまま壊さない)。
     */
    private data class BareDateFollowup(val range: DateTimeParser.DateRangeMatch, val inheritsPerson: Boolean)

    // 「今日」「明日」「明後日」は絶対的な1日を指す独立した表現なので、担当者文脈を
    // 引き継がない(BareDateFollowup.inheritsPerson=false)。
    private val absoluteBareDayWords = setOf("今日", "明日", "明後日")

    /**
     * #POI マリたん性能アップ Phase 1: 「じゃあ金曜は？」「来週は？」「翌日は？」の
     * ように、発話全体が(任意の「じゃあ/じゃ」＋)日付表現＋素朴な疑問表現だけで
     * 構成されている場合にだけ範囲を返す。「翌日」は今日から見た明日ではなく、
     * 直前の[ConversationContext.dateRange]の開始日([previousStart])の翌日として
     * 解決する — 文脈が無ければ(呼び出し元がpreviousStart=nullを渡す)「翌日」は
     * 解決できずnullを返す。
     */
    private fun bareFollowupDateRange(
        text: String,
        now: LocalDateTime,
        previousStart: LocalDate?,
    ): BareDateFollowup? {
        val stripped = stripBareFollowupTrailer(text)
        val core = stripped.removePrefix("じゃあ").removePrefix("じゃ").trim()
        if (core.isBlank()) return null

        if (core == "翌日") {
            val base = previousStart ?: return null
            val d = base.plusDays(1)
            val range = DateTimeParser.DateRangeMatch(d, d, DateTimeParser.formatWhen(d, now.toLocalDate()), "")
            return BareDateFollowup(range, inheritsPerson = true)
        }
        val match = DateTimeParser.extractDateRange(core, now) ?: return null
        if (match.remainingText.isNotBlank()) return null
        return BareDateFollowup(match, inheritsPerson = core !in absoluteBareDayWords)
    }

    /**
     * #POI マリたん性能アップ Phase 1: [ConversationContext]駆動の予定回答。既存の
     * [answerScheduleQueryByPerson](BB/[respond]と共有、日付には未対応)とは別の、
     * マリたんの[answerPoiQueryOrNull]専用の新しい経路 — 既存のBB向け関数は一切
     * 変更しない。[ConversationContext.dateRange]が無ければ[CatEventRepository.upcoming]
     * (現在時刻以降の全予定)、あれば[CatEventRepository.between]で期間を絞り込む
     * (既存の[CatEventRepository.between]をそのまま使うだけで新規DAOクエリは追加
     * しない)。isTaskによる絞り込みは行わない — 既存の[answerScheduleQueryByPerson]/
     * 旧[answerQuery]のdayFilter分岐もisTaskを区別していない、既存の前提を維持する
     * ためあえて揃えている。
     */
    /**
     * #POI 秘書性能② Stage 3: 戻り値を`Pair<String, AnsweredEventSnapshot?>`へ
     * 拡張した — 応答テキストに加え、「そこ雨？」継続質問が参照できる
     * [AnsweredEventSnapshot]を返す。この結果が安全に1件へ確定した場合
     * ([matched.size]==1)だけスナップショットを返し、0件または複数件の場合は
     * 必ずnull(呼び出し元は[ConversationContext.lastAnsweredEvent]をnullへ
     * 戻す) — 複数件からAI判断で「たぶんこれ」と選ぶことは一切しない。
     */
    private suspend fun answerScheduleForContext(ctx: ConversationContext, now: LocalDateTime): Pair<String, AnsweredEventSnapshot?> {
        val today = now.toLocalDate()
        val range = ctx.dateRange
        val keyword = ctx.keyword
        val person = ctx.person
        val personIsSelf = ctx.personIsSelf
        val pool = if (range != null) {
            repository.between(range.first.toEpochMilli(), range.second.plusDays(1).toEpochMilli() - 1)
        } else {
            repository.upcoming(now.toEpochMilli())
        }
        val byKeyword = if (keyword != null) pool.filter { it.title.contains(keyword) } else pool
        val matched = byKeyword.filter { it.dateTime != null && eventMatchesPersonFilter(it.assignee, person, personIsSelf) }

        if (matched.isEmpty()) {
            return (if (ctx.dateLabel != null) "${ctx.dateLabel}の予定はまだ無いにゃ" else "予定はまだ無いにゃ") to null
        }
        val titles = matched.joinToString("、") { "${DateTimeParser.formatWhen(it.dateTime!!.toLocalDate(), today)}の${it.title}" }
        val snapshot = matched.singleOrNull()?.let {
            AnsweredEventSnapshot(id = it.id, title = it.title, dateTime = it.dateTime!!, locationText = it.locationText)
        }
        return "予定は${titles}だにゃ" to snapshot
    }

    /**
     * #POI マリたん性能アップ Phase 1: [ConversationContext]駆動のタスク回答。
     * [ConversationContext.workOnly]で仕事タスク(category=WORK、既存の
     * [answerWorkTaskQuery]と同じ対象)かPoiタスク全般(既存の[answerTaskQuery]と
     * 同じ対象)かを切り替える、統一された新しい経路。既存の2つのBB向け関数は
     * どちらも一切変更しない。
     */
    private suspend fun answerTaskForContext(ctx: ConversationContext, now: LocalDateTime): String {
        val range = ctx.dateRange
        val keyword = ctx.keyword
        val person = ctx.person
        val personIsSelf = ctx.personIsSelf
        val pool = repository.incompleteTasks().let { tasks ->
            if (ctx.workOnly) tasks.filter { it.category == CatEvent.CATEGORY_WORK } else tasks
        }
        val byDate = if (range != null) {
            pool.filter { it.dateTime == null || (it.dateTime.toLocalDate() >= range.first && it.dateTime.toLocalDate() <= range.second) }
        } else {
            pool
        }
        val byKeyword = if (keyword != null) byDate.filter { it.title.contains(keyword) } else byDate
        val matched = byKeyword.filter { eventMatchesPersonFilter(it.assignee, person, personIsSelf) }

        val label = if (ctx.workOnly) "仕事" else "タスク"
        if (matched.isEmpty()) {
            return if (ctx.dateLabel != null) "${ctx.dateLabel}の${label}はまだ無いにゃ" else "${label}はまだ無いにゃ"
        }
        val titles = matched.joinToString("、") { it.title }
        return if (ctx.dateLabel != null) "${ctx.dateLabel}の${label}は${titles}だにゃ" else "${label}は${titles}だにゃ"
    }

    /**
     * #POI マリたん性能アップ Phase 1: [ConversationContext]駆動のメモ回答。以前の
     * マリたん専用メモ問い合わせ(今日/明日のみ対応)を、[DateTimeParser.extractDateRange]
     * の広い語彙(来週/今月等)にも対応するようこの関数へ置き換えた。メモには
     * 担当者(assignee)の概念が無いため、[ConversationContext.person]は使わない
     * (呼び出し元がMEMOトピックへの人物継続質問自体を試みない設計)。
     */
    private suspend fun answerMemoForContext(ctx: ConversationContext, now: LocalDateTime): String {
        val range = ctx.dateRange
        val keyword = ctx.keyword
        val all = repository.memos()
        val byDate = if (range != null) {
            all.filter { val d = it.createdAt.toLocalDate(); d >= range.first && d <= range.second }
        } else {
            all
        }
        val matched = if (keyword != null) byDate.filter { it.title.contains(keyword) } else byDate

        if (matched.isEmpty()) {
            return if (ctx.dateLabel != null) "${ctx.dateLabel}のメモは無いにゃ" else "メモはまだ無いにゃ"
        }
        val titles = matched.joinToString("、") { it.title }
        return if (ctx.dateLabel != null) "${ctx.dateLabel}のメモは${titles}だにゃ" else "メモは${titles}だにゃ"
    }

    private suspend fun answerQuery(text: String, now: LocalDateTime): String {
        val query = DateTimeParser.parseQuery(text, now)
        val today = now.toLocalDate()

        val keyword = query.keyword
        if (keyword != null) {
            val event = repository.upcomingMatching(keyword, now.toEpochMilli()).firstOrNull()
            if (event != null) {
                return "${keyword}は${DateTimeParser.formatWhen(event.dateTime!!.toLocalDate(), today)}だにゃ"
            }
            val memo = repository.memosMatching(keyword).firstOrNull()
            if (memo != null) {
                // "駐車場の番号なんだっけ？" against a memo titled "駐車場の番号1234" should
                // answer just "1234だにゃ" rather than echoing the whole memo back.
                val remainder = memo.title.removePrefix(keyword).trim().removePrefix("の").removePrefix("は").trim()
                return if (memo.title.startsWith(keyword) && remainder.isNotBlank()) {
                    "${remainder}だにゃ"
                } else {
                    "${memo.title}って覚えてるにゃ"
                }
            }
            return "${keyword}の予定はまだ入ってないにゃ"
        }

        val dayFilter = query.dayFilter
        if (dayFilter != null) {
            val start = dayFilter.toEpochMilli()
            val end = dayFilter.plusDays(1).toEpochMilli() - 1
            val events = repository.onDay(start, end)
            return if (events.isEmpty()) {
                "${DateTimeParser.formatWhen(dayFilter, today)}の予定はまだ入ってないにゃ"
            } else {
                val titles = events.joinToString("と") { it.title }
                "${DateTimeParser.formatWhen(dayFilter, today)}は${titles}だにゃ"
            }
        }

        val next = repository.upcoming(now.toEpochMilli()).firstOrNull()
        return if (next != null) {
            "次の予定は${DateTimeParser.formatWhen(next.dateTime!!.toLocalDate(), today)}の${next.title}だにゃ"
        } else {
            "予定はまだ入ってないにゃ"
        }
    }

    private fun isTaskQuestion(text: String): Boolean =
        text.contains("やること") || text.contains("タスク") || text.contains("やるべきこと") ||
            text.contains("ポイ") || text.contains("終わってない") || text.contains("残ってる")

    private suspend fun answerTaskQuery(text: String, now: LocalDateTime): String {
        val today = now.toLocalDate()
        val scopeDate = when {
            text.contains("今日") -> today
            text.contains("明日") -> today.plusDays(1)
            else -> null
        }
        // "明日までのタスク" (due by tomorrow) is a range, unlike "今日やること" (due today).
        val isUntilScope = scopeDate != null && text.contains("まで")

        val tasks = when {
            isUntilScope -> repository.incompleteTasksDueBy(scopeDate!!.plusDays(1).toEpochMilli() - 1)
            scopeDate != null -> repository.incompleteTasksDueOrUndated(scopeDate.toEpochMilli(), scopeDate.plusDays(1).toEpochMilli() - 1)
            else -> repository.incompleteTasks()
        }

        if (tasks.isEmpty()) {
            return when {
                scopeDate == today && !isUntilScope -> "今日やることはないにゃ"
                scopeDate != null -> "${DateTimeParser.formatWhen(scopeDate, today)}までのタスクはないにゃ"
                else -> "残ってるタスクはないにゃ"
            }
        }
        val titles = tasks.joinToString("、") { it.title }
        return when {
            scopeDate == today && !isUntilScope -> "今日は${titles}だにゃ"
            scopeDate != null -> "${DateTimeParser.formatWhen(scopeDate, today)}までは${titles}だにゃ"
            else -> "残ってるのは${titles}だにゃ"
        }
    }

    // #145: 音声認識では「？」等の句読点が付かないことが多い(「私の仕事は？」が
    // 「私の仕事は」として届く等)。ここで剥がすのは仕事の問い合わせ特有の
    // 末尾表現だけ — taskCompletionSuffixes(「終わった」「完了」等)や
    // taskTriggerSuffixes/memoVerbs(「追加して」「登録して」等)とは重ならない
    // ものだけを選んでおり、既存の完了報告・登録指示を誤って奪わないようにする。
    // 長い表現ほど先に判定する(「何がある」を「ある」より先に見て、余分な文字を
    // 残さない)。
    private val workQuestionTrailers = listOf(
        "何がある？", "何がある", "何ある？", "何ある",
        "何？", "何",
        "ある？", "ある",
        "は？", "は",
        "？", "?",
    )

    /** 上記の末尾表現を(一致した最初の1つだけ)取り除いた残りの文字列。 */
    private fun stripWorkQuestionTrailer(text: String): String {
        for (trailer in workQuestionTrailers) {
            if (text.endsWith(trailer) && text.length > trailer.length) {
                return text.removeSuffix(trailer)
            }
        }
        return text
    }

    /**
     * #146: [stripWorkQuestionTrailer]で末尾の疑問表現を1段階だけ剥がした残りが
     * 「の仕事」で終わる(「私の仕事」「今日のみゆたんの仕事」等)か、「仕事」
     * そのもの(「仕事は？」→「仕事」)か、「仕事全部」「全部の仕事」を含む、と
     * いう形状だけで判定する厳格版。DateTimeParser.isQuery()による緩い
     * フォールバックを含まないため、「仕事とは何？」「仕事について相談したい」
     * のような一般的な質問には一致しない。[isWorkTaskQuestion](respond()向け)と
     * #146のマリたん向けルーター([answerPoiQueryOrNull])の両方から共有される。
     */
    private fun workTaskQuestionCore(text: String): Boolean {
        if (!text.contains("仕事")) return false
        val core = stripWorkQuestionTrailer(text)
        if (core.endsWith("の仕事") || core == "仕事") return true
        return core.contains("仕事全部") || core.contains("全部の仕事")
    }

    /**
     * #144/#145: 「私の仕事」「今日の仕事」「仕事全部」等、仕事タスクの担当を
     * 尋ねる質問かどうかの判定。単に「仕事」という単語を含むだけでは判定しない
     * — 「明日仕事に行く」(予定登録)や「仕事は完了したよ」「見積書の仕事終わった」
     * (完了報告)のように、文中のどこかに「仕事」が出てくるだけの既存の登録/完了
     * フレーズを誤ってここで横取りしてしまわないようにするため。
     *
     * [workTaskQuestionCore]の形状判定に加えて、それ以外は既存のDateTimeParser.
     * isQuery()による質問判定(「仕事について教えて」等)にも従う —
     * DateTimeParser.kt自体は変更しない。#146のマリたん向けルーターは、この
     * 緩いisQuery()フォールバックを使わない[workTaskQuestionCore]の方を直接
     * 利用する(「仕事とは何？」等の一般的な質問との誤判定を避けるため)。
     */
    private fun isWorkTaskQuestion(text: String): Boolean {
        if (!text.contains("仕事")) return false
        if (workTaskQuestionCore(text)) return true
        return DateTimeParser.isQuery(text)
    }

    /**
     * #144: 仕事タスク(isTask=1 かつ category=CATEGORY_WORK)を、誰の担当かで
     * 絞り込んで答える。担当は「表示を絞る」ためのものではなく(仕事タスク自体は
     * これまで通りみゆたん・かっちゃん双方の端末に全件表示・同期される)、この
     * 質問への「答え方」だけを絞り込むためのもの — ここでも新しいDB問い合わせは
     * 追加せず、既存の[CatEventRepository.incompleteTasks]が返す全件をこの関数の
     * 中でKotlin側でfilterするだけ(PoiScreen.ktの既存のcategoryフィルタと同じやり方)。
     *
     * 判定順序が重要: 「みゆたん」「かっちゃん」「2人」という明示的な指定を、
     * 「私/自分」や「(指定なしの)全部」より必ず先に判定する。指定が無ければ
     * category=WORKの仕事タスクをassigneeに関係なく(null/2人も含め)全件返す。
     *
     * 「私の仕事」「自分の仕事」はRoomStore.displayName(この端末の現在の利用者)
     * を使い、assigneeがその名前、または「2人」と一致するものを対象にする —
     * 「2人」は「担当者不在」ではなく「両者が責任を持つ」タスクなので、自分の
     * 仕事として一緒に見える方が聞き漏れがない、という#144での確定方針。
     * 個人名/「2人」を明示した質問では、その値だけに厳密に絞り込み、nullは
     * 一切含めない(未設定を「2人」やどちらか個人の担当と誤って扱わない)。
     *
     * 表示名が未設定(null)のまま「私の仕事」を聞かれた場合は、DBには一切
     * 問い合わせず、話者を特定できない旨だけを返す。
     */
    private suspend fun answerWorkTaskQuery(text: String, now: LocalDateTime, myDisplayName: String?): String {
        val today = now.toLocalDate()
        val scopeDate = when {
            text.contains("今日") -> today
            text.contains("明日") -> today.plusDays(1)
            else -> null
        }

        val workTasks = repository.incompleteTasks()
            .filter { it.category == CatEvent.CATEGORY_WORK }
            .filter { scopeDate == null || it.dateTime == null || it.dateTime.toLocalDate() == scopeDate }

        val matched = when {
            text.contains("みゆたん") -> workTasks.filter { it.assignee == CatEvent.ASSIGNEE_MIYU }
            text.contains("かっちゃん") -> workTasks.filter { it.assignee == CatEvent.ASSIGNEE_KATCHAN }
            // #POI 仕様変更: 「指定なし＝2人」統一に合わせ、既存のassignee=null(未設定)行も
            // "2人"と同じ共有として扱う(assigneeMatchesShared)。既存データを書き換える
            // 処理ではない — 検索時の一致条件を広げるだけ。
            text.contains(CatEvent.ASSIGNEE_BOTH) || text.contains("二人") -> workTasks.filter { assigneeMatchesShared(it.assignee) }
            text.contains("私の仕事") || text.contains("自分の仕事") -> {
                if (myDisplayName == null) return unknownSpeakerReply()
                workTasks.filter { it.assignee == myDisplayName || assigneeMatchesShared(it.assignee) }
            }
            else -> workTasks
        }

        if (matched.isEmpty()) {
            return when {
                scopeDate == today -> "今日の仕事はまだ無いにゃ"
                scopeDate != null -> "${DateTimeParser.formatWhen(scopeDate, today)}の仕事はまだ無いにゃ"
                else -> "仕事はまだ無いにゃ"
            }
        }
        val titles = matched.joinToString("、") { it.title }
        return when {
            scopeDate == today -> "今日の仕事は${titles}だにゃ"
            scopeDate != null -> "${DateTimeParser.formatWhen(scopeDate, today)}の仕事は${titles}だにゃ"
            else -> "仕事は${titles}だにゃ"
        }
    }

    /** RoomStore.displayNameが未設定のまま「私の仕事」「私の予定」を聞かれた
     * 場合の返答。 */
    private fun unknownSpeakerReply(): String = "今どっちが話してるか分からないにゃ。まず設定で名前を選んでにゃ"

    /**
     * #147: [stripWorkQuestionTrailer]で末尾の疑問表現を1段階だけ剥がした残りが
     * 「の予定」で終わり、かつ「みゆたん」「かっちゃん」「2人」「私の予定」
     * 「自分の予定」のいずれかを含む場合だけ、人物指定の予定問い合わせとみなす。
     * (トレイラー除去のロジック自体は仕事タスク用と全く同じ末尾表現なので
     * [stripWorkQuestionTrailer]をそのまま再利用している。) 日付のみを指定した
     * 「今日の予定は？」等(人物指定なし)は対象外 — 既存のanswerQuery()が
     * 担当未設定を含む全件を返す、これまで通りの動作のままにする。
     */
    private fun isScheduleQuestionWithPerson(text: String): Boolean {
        if (!text.contains("予定")) return false
        val core = stripWorkQuestionTrailer(text)
        if (!core.endsWith("の予定")) return false
        return core.contains("みゆたん") || core.contains("かっちゃん") ||
            core.contains(CatEvent.ASSIGNEE_BOTH) || core.contains("二人") ||
            core.contains("私の予定") || core.contains("自分の予定")
    }

    /**
     * #147: 予定(isTask=false かつdateTimeあり)を、誰の担当かで絞り込んで
     * 答える。仕事タスクのanswerWorkTaskQuery()と同じ設計方針 — 担当は
     * 「表示を絞る」ためのものではなく(予定自体はこれまで通りみゆたん・
     * かっちゃん双方の端末に全件表示・同期される)、この質問への「答え方」
     * だけを絞り込むためのもの。新しいDB問い合わせは追加せず、既存の
     * [CatEventRepository.upcoming]（引数省略時は現在時刻以降の全予定を返す）
     * が返す結果をこの関数の中でKotlin側でfilterするだけ。過去の予定を
     * 際限なく積み上げて答えないよう、「今から先」の予定に絞っている
     * （タスクにおける「未完了のみ」に相当する、予定側の自然な絞り込み）。
     * 日付とのAND指定(「今日のみゆたんの予定」等)は今回未対応 — 該当すれば
     * 日付を問わず担当者の予定を全て返す。
     *
     * 判定順序が重要: 「みゆたん」「かっちゃん」「2人」という明示的な指定を
     * 必ず先に判定する。「私の予定」「自分の予定」はRoomStore.displayName
     * (この端末の現在の利用者)を使い、assigneeがその名前、または「2人」と
     * 一致するものを対象にする — #144の仕事タスクと同じ確定方針(「2人」は
     * 「担当者不在」ではなく「両者が責任を持つ」予定なので、自分の予定として
     * 一緒に見える方が聞き漏れがない)。個人名/「2人」を明示した質問では、
     * その値だけに厳密に絞り込み、nullは一切含めない。
     *
     * 表示名が未設定(null)のまま「私の予定」を聞かれた場合は、DBには一切
     * 問い合わせず、話者を特定できない旨だけを返す。
     */
    private suspend fun answerScheduleQueryByPerson(text: String, now: LocalDateTime, myDisplayName: String?): String {
        val today = now.toLocalDate()
        val schedules = repository.upcoming()

        val matched = when {
            text.contains("みゆたん") -> schedules.filter { it.assignee == CatEvent.ASSIGNEE_MIYU }
            text.contains("かっちゃん") -> schedules.filter { it.assignee == CatEvent.ASSIGNEE_KATCHAN }
            // #POI 仕様変更: 「指定なし＝2人」統一に合わせ、既存のassignee=null(未設定)行も
            // "2人"と同じ共有として扱う(assigneeMatchesShared)。既存データを書き換える
            // 処理ではない — 検索時の一致条件を広げるだけ。
            text.contains(CatEvent.ASSIGNEE_BOTH) || text.contains("二人") -> schedules.filter { assigneeMatchesShared(it.assignee) }
            else -> {
                if (myDisplayName == null) return unknownSpeakerReply()
                schedules.filter { it.assignee == myDisplayName || assigneeMatchesShared(it.assignee) }
            }
        }

        if (matched.isEmpty()) return "予定はまだ無いにゃ"
        val titles = matched.joinToString("、") { "${DateTimeParser.formatWhen(it.dateTime!!.toLocalDate(), today)}の${it.title}" }
        return "予定は${titles}だにゃ"
    }

    // #146: メモの問い合わせ特有の末尾表現。workQuestionTrailersとは別に持つ —
    // 「何」「何がある」はメモの問い合わせ例に含まれていないため、あえて含めず
    // 判定をより厳格にしている。
    private val memoQuestionTrailers = listOf(
        "見せて",
        "ある？", "ある",
        "は？", "は",
        "？", "?",
    )

    private fun stripMemoQuestionTrailer(text: String): String {
        for (trailer in memoQuestionTrailers) {
            if (text.endsWith(trailer) && text.length > trailer.length) {
                return text.removeSuffix(trailer)
            }
        }
        return text
    }

    /**
     * #146: 「メモ」の表示・検索を求める、確信度の高い問い合わせだけを判定する。
     * 単に「メモ」という単語を含むだけでは判定しない —「メモの取り方教えて」
     * 「メモって何？」「おすすめのメモアプリは？」のような一般知識・相談は
     * Geminiへ回すべきため。[stripMemoQuestionTrailer]で末尾の疑問表現を
     * 剥がした残りが「のメモ」で終わる(「今日のメモ」「駐車場のメモ」等)か、
     * 「メモ」そのもの(「メモ見せて」→「メモ」、「メモある?」→「メモ」)で
     * ある場合だけ質問とみなす。「おすすめのメモアプリは？」は「のメモアプリ」
     * であって「のメモ」そのもので終わらないため、正しく除外される。
     * isWorkTaskQuestion()と異なり、DateTimeParser.isQuery()への緩い
     * フォールバックは持たない — respond()からは呼ばれず、#146のマリたん向け
     * ルーター([answerPoiQueryOrNull])専用の、より厳格な判定。
     */
    private fun isMemoQuery(text: String): Boolean {
        if (!text.contains("メモ")) return false
        val core = stripMemoQuestionTrailer(text)
        return core.endsWith("のメモ") || core == "メモ"
    }

    /**
     * #146: マリたん(Gemini経由の音声アシスタント)からPOI内部データへの読み取り
     * 専用の問い合わせだけを、高い確信度で判定できる場合にのみ処理する。
     * respond()とは完全に独立した新しいエントリポイントで、重要な違いがある:
     *
     * 1. 判定できなかった入力を新規メモ/タスク/予定として保存するrespond()の
     *    最終フォールバックはここには存在しない — 一般会話がPOIデータとして
     *    誤って書き込まれることは無い。予定登録・タスク登録・メモ登録・編集・
     *    削除・完了処理は一切行わない、正真正銘の読み取り専用。
     * 2. 判定に確信が持てない場合は必ずnullを返す。呼び出し元(MariTanRow)は
     *    nullの場合、従来通りGeminiSearchService.ask()へフォールバックする。
     * 3. 仕事タスクの判定は[workTaskQuestionCore](形状一致のみ)を使う —
     *    respond()が使う[isWorkTaskQuestion]の緩いDateTimeParser.isQuery()
     *    フォールバックは使わない。「仕事とは何？」等の一般的な質問を誤って
     *    仕事タスク問い合わせと判定しない。
     * 4. 予定問い合わせは今日/明日/明後日の日付が明確に取れた場合だけを対象にし
     *    (dayFilter != null かつ keyword == null)、自由なキーワード検索
     *    (answerQueryのkeyword分岐)は対象にしない — 「富士山の高さは？」の
     *    ような一般トリビアがメモ/予定検索に化けてしまうことを避けるため。
     * 5. メモ問い合わせは[isMemoQuery]による厳格な判定のみを使う。
     * 6. #147: 予定の担当者(assignee)判定は[isScheduleQuestionWithPerson]
     *    (respond()と共通、形状一致のみ)を使う。人物指定の無い「今日の予定は？」
     *    等はこれまで通り4番の日付限定answerQuery()側で処理する(query.keywordが
     *    nullでない限りそちらもnullを返す点は変更していない)。
     *
     * #POI マリたん性能アップ Phase 1: 戻り値を[CatReply]から`Pair<CatReply,
     * ConversationContext?>`へ拡張した。呼び出し元(MariTanRow)はこの[ConversationContext]
     * を次の発話まで保持し、直前に成立したPOI質問の続きとして「かっちゃんは？」
     * 「じゃあ金曜は？」のような単独では情報不足の継続質問を[context]引数として
     * 渡し戻す。[context]がnull(直前の発話がPOI質問として成立していない)場合は
     * 継続質問の判定を一切行わない — respond()と同様、判定に確信が持てない発話を
     * 誤ってPOIデータの検索/登録に結び付けないための安全側の設計をそのまま維持する。
     * このメソッドは今回も読み取り専用のまま — 予定登録・タスク登録・メモ登録・
     * 編集・削除は一切行わない。
     */
    suspend fun answerPoiQueryOrNull(
        input: String,
        context: ConversationContext? = null,
    ): Pair<CatReply, ConversationContext?>? {
        val trimmed = input.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return null
        if (looksOutOfScope(trimmed)) return null

        val now = LocalDateTime.now()

        if (isPhotoQuery(trimmed)) {
            // Phase 1: アルバム/写真は今回の会話文脈の対象外 — 応答は返すが、
            // 次の発話へ引き継ぐ文脈はここで打ち切る(null)。
            return answerPhotoQuery(trimmed, now) to null
        }

        // #POI マリたん性能アップ Phase 1: 直前に成立したPOI質問の文脈がある場合
        // だけ、「かっちゃんは？」「じゃあ金曜は？」のような単独では情報不足の
        // 継続質問を許可する。文脈が無い発話はここを素通りし、以下の既存判定へ
        // そのまま流れる — 通常の雑談を継続質問と誤判定することはない。
        if (context != null) {
            if (context.topic != ConversationTopic.MEMO) {
                bareFollowupPerson(trimmed, currentDisplayName())?.let { (person, isSelf) ->
                    val base = context.copy(person = person, personIsSelf = isSelf)
                    if (base.topic == ConversationTopic.SCHEDULE) {
                        val (text, snapshot) = answerScheduleForContext(base, now)
                        val updated = base.copy(lastAnsweredEvent = snapshot)
                        return CatReply(text) to updated
                    } else {
                        val text = answerTaskForContext(base, now)
                        val updated = base.copy(lastAnsweredEvent = null)
                        return CatReply(text) to updated
                    }
                }
            }
            bareFollowupDateRange(trimmed, now, context.dateRange?.first)?.let { (range, inheritsPerson) ->
                // #POI 実機不具合修正: 「今日」「明日」「明後日」の単独継続質問は
                // 担当者文脈を引き継がず、指定なし(POI全体)へ戻す。曜日名/週相対語/
                // 「翌日」はinheritsPerson=trueのまま既存の担当者文脈を維持する。
                val base = if (inheritsPerson) {
                    context.copy(dateRange = range.start to range.end, dateLabel = range.label)
                } else {
                    context.copy(dateRange = range.start to range.end, dateLabel = range.label, person = null, personIsSelf = false)
                }
                return when (base.topic) {
                    ConversationTopic.SCHEDULE -> {
                        val (text, snapshot) = answerScheduleForContext(base, now)
                        CatReply(text) to base.copy(lastAnsweredEvent = snapshot)
                    }
                    ConversationTopic.TASK -> {
                        val text = answerTaskForContext(base, now)
                        CatReply(text) to base.copy(lastAnsweredEvent = null)
                    }
                    ConversationTopic.MEMO -> {
                        val text = answerMemoForContext(base, now)
                        CatReply(text) to base.copy(lastAnsweredEvent = null)
                    }
                }
            }
        }

        if (workTaskQuestionCore(trimmed)) {
            val explicit = explicitPersonInText(trimmed)
            val wantsSelf = explicit == null && (trimmed.contains("私の仕事") || trimmed.contains("自分の仕事"))
            if (wantsSelf && currentDisplayName() == null) return CatReply(unknownSpeakerReply()) to null
            val person = explicit ?: if (wantsSelf) currentDisplayName() else null
            val range = DateTimeParser.extractDateRange(trimmed, now)
            val keyword = range?.let { DateTimeParser.parseQuery(it.remainingText, now).keyword }
            val ctx = ConversationContext(
                ConversationTopic.TASK,
                workOnly = true,
                person = person,
                personIsSelf = wantsSelf,
                dateRange = range?.let { it.start to it.end },
                dateLabel = range?.label,
                keyword = keyword,
            )
            return CatReply(answerTaskForContext(ctx, now)) to ctx
        }

        if (isScheduleQuestionWithPerson(trimmed)) {
            val explicit = explicitPersonInText(trimmed)
            val wantsSelf = explicit == null && (trimmed.contains("私の予定") || trimmed.contains("自分の予定"))
            if (wantsSelf && currentDisplayName() == null) return CatReply(unknownSpeakerReply()) to null
            val person = explicit ?: if (wantsSelf) currentDisplayName() else null
            val range = DateTimeParser.extractDateRange(trimmed, now)
            val keyword = range?.let { DateTimeParser.parseQuery(it.remainingText, now).keyword }
            val ctx = ConversationContext(
                ConversationTopic.SCHEDULE,
                person = person,
                personIsSelf = wantsSelf,
                dateRange = range?.let { it.start to it.end },
                dateLabel = range?.label,
                keyword = keyword,
            )
            val (text, snapshot) = answerScheduleForContext(ctx, now)
            return CatReply(text) to ctx.copy(lastAnsweredEvent = snapshot)
        }

        if (isTaskQuestion(trimmed) && DateTimeParser.isQuery(trimmed)) {
            val person = explicitPersonInText(trimmed)
            val range = DateTimeParser.extractDateRange(trimmed, now)
            val keyword = range?.let { DateTimeParser.parseQuery(it.remainingText, now).keyword }
            val ctx = ConversationContext(
                ConversationTopic.TASK,
                workOnly = false,
                person = person,
                dateRange = range?.let { it.start to it.end },
                dateLabel = range?.label,
                keyword = keyword,
            )
            return CatReply(answerTaskForContext(ctx, now)) to ctx
        }

        if (isMemoQuery(trimmed)) {
            val core = stripMemoQuestionTrailer(trimmed)
            val withoutMemoWord = if (core == "メモ") "" else core.removeSuffix("のメモ").trim()
            val range = if (withoutMemoWord.isBlank()) null else DateTimeParser.extractDateRange(withoutMemoWord, now)
            val keyword = when {
                withoutMemoWord.isBlank() -> null
                range != null -> range.remainingText.trim().ifBlank { null }
                else -> withoutMemoWord.ifBlank { null }
            }
            val ctx = ConversationContext(
                ConversationTopic.MEMO,
                dateRange = range?.let { it.start to it.end },
                dateLabel = range?.label,
                keyword = keyword,
            )
            return CatReply(answerMemoForContext(ctx, now)) to ctx
        }

        // #POI マリたん性能アップ Phase 1: 上のどの形状にも一致しなかった、
        // 「来週忙しい？」「今月病院あった？」「金曜なんかある？」のような、
        // 「の予定」等の決まった言い回しを含まない自由な期間質問。日付/期間の
        // 語(来週/今週/再来週/今月/来月/曜日名/今日/明日/明後日)が実際に見つかった
        // 場合にだけ処理する — 日付語が全く無いキーワードだけの質問(「駐車場の
        // 番号なんだっけ？」等)は、一般トリビアの誤爆を避けるため従来通り対象外
        // (何も返さずnull、呼び出し元はGeminiへフォールバックする)のまま。
        if (DateTimeParser.isQuery(trimmed)) {
            DateTimeParser.extractDateRange(trimmed, now)?.let { range ->
                val keyword = DateTimeParser.parseQuery(range.remainingText, now).keyword
                val ctx = ConversationContext(
                    ConversationTopic.SCHEDULE,
                    dateRange = range.start to range.end,
                    dateLabel = range.label,
                    keyword = keyword,
                )
                val (text, snapshot) = answerScheduleForContext(ctx, now)
                return CatReply(text) to ctx.copy(lastAnsweredEvent = snapshot)
            }
        }

        return null
    }

    /** "今日の写真見せて"/"病院の写真見せて" style — requires the literal word "写真",
     * which never appears in a schedule/memo/task sentence, so this can't misfire on them. */
    private fun isPhotoQuery(text: String): Boolean =
        text.contains("写真") && (text.endsWith("見せて") || DateTimeParser.isQuery(text))

    private val photoQueryScaffolding = listOf(
        "見せて",
        "の写真", "写真",
        "この前の", "前の", "先日の",
        "は", "の", "を",
        "？", "?", "、", "。",
    )

    /** Strips the "見せて"/"写真" scaffolding to leave just the search term
     * ("この前の旅行の写真見せて" -> "旅行"), or null if nothing is left. */
    private fun extractPhotoKeyword(text: String): String? {
        var remaining = text
        for (token in photoQueryScaffolding) {
            remaining = remaining.replace(token, "")
        }
        return remaining.trim().ifBlank { null }
    }

    /** Resolves a date reference in a photo question ("今日"/"昨日"/"9月8日"/...), or null
     * if the question is keyword-based instead ("病院の写真見せて"). */
    private fun photoQueryDate(text: String, now: LocalDateTime): LocalDate? {
        val today = now.toLocalDate()
        return when {
            text.contains("一昨日") -> today.minusDays(2)
            text.contains("昨日") -> today.minusDays(1)
            text.contains("明後日") -> today.plusDays(2)
            text.contains("明日") -> today.plusDays(1)
            text.contains("今日") -> today
            else -> Regex("(\\d{1,2})月(\\d{1,2})日").find(text)?.let { m ->
                LocalDate.of(now.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            }
        }
    }

    private suspend fun searchPhotosByDate(start: Long, end: Long): List<Photo> {
        val byAdded = photoRepository.byAddedAtRange(start, end)
        val byLinkedDate = photoRepository.byLinkedDate(start, end)
        return (byAdded + byLinkedDate).distinctBy { it.id }
    }

    /** Matches photos by their own caption/album, and photos linked to a memo or schedule
     * whose title matches — covers "病院の写真見せて" finding a photo linked to a "病院"
     * memo just as much as one whose own caption/album says "病院". */
    private suspend fun searchPhotosByKeyword(keyword: String): List<Photo> {
        val direct = photoRepository.searchByCaptionOrAlbum(keyword)
        val matchingEvents = repository.allMatching(keyword)
        val viaEvents = matchingEvents.flatMap { photoRepository.photosForMemo(it.id) }
        return (direct + viaEvents).distinctBy { it.id }
    }

    private suspend fun answerPhotoQuery(text: String, now: LocalDateTime): CatReply {
        val today = now.toLocalDate()
        val date = photoQueryDate(text, now)
        val (photos, label) = if (date != null) {
            val start = date.toEpochMilli()
            val end = date.plusDays(1).toEpochMilli() - 1
            searchPhotosByDate(start, end) to DateTimeParser.formatWhen(date, today)
        } else {
            val keyword = extractPhotoKeyword(text)
            if (keyword == null) emptyList<Photo>() to null else searchPhotosByKeyword(keyword) to keyword
        }

        return if (photos.isEmpty()) {
            CatReply("その写真はまだないにゃ")
        } else {
            val prefix = label?.let { "${it}の" }.orEmpty()
            CatReply("${prefix}写真はこれだにゃ", photos.map { it.id })
        }
    }
}
