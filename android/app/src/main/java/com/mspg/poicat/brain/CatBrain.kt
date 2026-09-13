package com.mspg.poicat.brain

import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.gemini.GeminiOutcome
import com.mspg.poicat.gemini.GeminiRegistrationIntent
import com.mspg.poicat.gemini.GeminiWorkJudge
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
            repository.addTask(title, dueDate?.toEpochMilli(), classifyTaskCategory(title))
            return CatReply("ポイに入れたにゃ")
        }

        val memoContent = extractMemoCommand(trimmed)
        if (memoContent != null) {
            // "明日病院だから覚えといて" uses a memo-style "覚えといて" trigger, but the
            // content itself names a date — that makes it a schedule, not a memo.
            val scheduleFromMemo = DateTimeParser.parseRegistration(memoContent, now)
            if (scheduleFromMemo != null) {
                val (saved, judgment) = rememberScheduleWithWorkJudgment(
                    scheduleFromMemo.title,
                    scheduleFromMemo.dateTime.toEpochMilli(),
                )
                return CatReply(scheduleRegisteredReply(saved, judgment, now))
            }
            repository.remember(memoContent, null)
            return CatReply("メモしたにゃ")
        }

        val registration = DateTimeParser.parseRegistration(trimmed, now)
        if (registration != null) {
            val (saved, judgment) = rememberScheduleWithWorkJudgment(registration.title, registration.dateTime.toEpochMilli())
            return CatReply(scheduleRegisteredReply(saved, judgment, now))
        }

        // Last resort before this line was "save it as a memo no matter what" — which
        // meant something like "英語に翻訳して" got filed away as a memo titled exactly
        // that. Turn away anything that looks out of scope here instead of guessing at
        // it or hoarding it; anything else genuinely unrecognized still gets remembered
        // as before.
        if (looksOutOfScope(trimmed)) {
            return CatReply(outOfScopeReply(trimmed))
        }
        repository.remember(DateTimeParser.cleanTitle(trimmed, fallback = trimmed), null)
        return CatReply("覚えたにゃ")
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
            val task = repository.addTask(title, dueDate?.toEpochMilli(), classifyTaskCategory(title))
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
                repository.remember(scheduleFromMemo.title, scheduleFromMemo.dateTime.toEpochMilli())
                return CatReply("覚えて写真も残したにゃ")
            }
            val event = repository.remember(memoContent, null)
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
            repository.remember(registration.title, registration.dateTime.toEpochMilli())
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

    private val taskTriggerSuffixes = listOf(
        "の忘れないで", "を忘れないで", "忘れないで",
        "の忘れずに", "を忘れずに", "忘れずに",
        "を忘れるな", "忘れるな",
        "忘れないように",
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
        // "牛乳買うの覚えといて" phrases a task using a memo-style "覚えといて" trigger —
        // still a task, since the content right before it ends in a task verb ("買う").
        // The verb stays in the returned title for the same reason as above.
        for (verb in memoVerbs) {
            for (memoSuffix in listOf("って$verb", "を$verb", verb)) {
                if (text.endsWith(memoSuffix)) {
                    val inner = text.removeSuffix(memoSuffix).trim().removeSuffix("の").removeSuffix("を").trim()
                    if (taskVerbEndings.any { inner.endsWith(it) }) return inner
                }
            }
        }
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
    private suspend fun rememberScheduleWithWorkJudgment(title: String, dateTime: Long): Pair<CatEvent, WorkJudgment> {
        val saved = repository.remember(title, dateTime)
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

        return rememberScheduleWithWorkJudgment(registration.title, registration.dateTime.toEpochMilli())
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
                    val content = text.removeSuffix(suffix).trim()
                    if (content.isNotBlank()) return content
                }
            }
        }
        return null
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
            text.contains(CatEvent.ASSIGNEE_BOTH) -> workTasks.filter { it.assignee == CatEvent.ASSIGNEE_BOTH }
            text.contains("私の仕事") || text.contains("自分の仕事") -> {
                if (myDisplayName == null) return unknownSpeakerReply()
                workTasks.filter { it.assignee == myDisplayName || it.assignee == CatEvent.ASSIGNEE_BOTH }
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
            core.contains(CatEvent.ASSIGNEE_BOTH) || core.contains("私の予定") || core.contains("自分の予定")
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
            text.contains(CatEvent.ASSIGNEE_BOTH) -> schedules.filter { it.assignee == CatEvent.ASSIGNEE_BOTH }
            else -> {
                if (myDisplayName == null) return unknownSpeakerReply()
                schedules.filter { it.assignee == myDisplayName || it.assignee == CatEvent.ASSIGNEE_BOTH }
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
     * #146: [isMemoQuery]が真の場合にのみ呼ばれる、メモの読み取り専用の回答。
     * 新しいDB問い合わせは追加せず、既存の[CatEventRepository.memos]が返す
     * 全件をKotlin側でfilterするだけ。「今日」「明日」は日付ワードとして扱い
     * (メモ自体にdateTimeは無いため、createdAtの日付で絞り込む)、それ以外の
     * 語は既存メモのタイトルに対するキーワード検索として扱う。
     */
    private suspend fun answerMemoQuery(text: String, now: LocalDateTime): String {
        val today = now.toLocalDate()
        val core = stripMemoQuestionTrailer(text)
        val keyword = if (core == "メモ") null else core.removeSuffix("のメモ").trim().ifBlank { null }
        val scopeDate = when (keyword) {
            "今日" -> today
            "明日" -> today.plusDays(1)
            else -> null
        }
        val effectiveKeyword = if (scopeDate != null) null else keyword

        val allMemos = repository.memos()
        val filtered = when {
            scopeDate != null -> allMemos.filter { it.createdAt.toLocalDate() == scopeDate }
            effectiveKeyword != null -> allMemos.filter { it.title.contains(effectiveKeyword) }
            else -> allMemos
        }

        if (filtered.isEmpty()) {
            return when {
                effectiveKeyword != null -> "${effectiveKeyword}のメモは無いにゃ"
                scopeDate != null -> "${DateTimeParser.formatWhen(scopeDate, today)}のメモは無いにゃ"
                else -> "メモはまだ無いにゃ"
            }
        }
        val titles = filtered.joinToString("、") { it.title }
        return when {
            effectiveKeyword != null -> "${effectiveKeyword}のメモは${titles}だにゃ"
            scopeDate != null -> "${DateTimeParser.formatWhen(scopeDate, today)}のメモは${titles}だにゃ"
            else -> "メモは${titles}だにゃ"
        }
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
     */
    suspend fun answerPoiQueryOrNull(input: String): CatReply? {
        val trimmed = input.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return null
        if (looksOutOfScope(trimmed)) return null

        val now = LocalDateTime.now()

        if (isPhotoQuery(trimmed)) {
            return answerPhotoQuery(trimmed, now)
        }

        if (workTaskQuestionCore(trimmed)) {
            return CatReply(answerWorkTaskQuery(trimmed, now, currentDisplayName()))
        }

        if (isScheduleQuestionWithPerson(trimmed)) {
            return CatReply(answerScheduleQueryByPerson(trimmed, now, currentDisplayName()))
        }

        if (isTaskQuestion(trimmed) && DateTimeParser.isQuery(trimmed)) {
            return CatReply(answerTaskQuery(trimmed, now))
        }

        if (isMemoQuery(trimmed)) {
            return CatReply(answerMemoQuery(trimmed, now))
        }

        if (DateTimeParser.isQuery(trimmed)) {
            val query = DateTimeParser.parseQuery(trimmed, now)
            if (query.dayFilter != null && query.keyword == null) {
                return CatReply(answerQuery(trimmed, now))
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
