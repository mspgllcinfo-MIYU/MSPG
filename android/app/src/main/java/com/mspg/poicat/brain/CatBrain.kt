package com.mspg.poicat.brain

import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import java.time.LocalDate
import java.time.LocalDateTime

/** A cat AI reply: the にゃ-voiced text, plus any photos found for a photo-search question
 * (empty for every other kind of reply). */
data class CatReply(val text: String, val photoIds: List<Long> = emptyList())

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
                repository.remember(scheduleFromMemo.title, scheduleFromMemo.dateTime.toEpochMilli())
                return CatReply("覚えたにゃ")
            }
            repository.remember(memoContent, null)
            return CatReply("メモしたにゃ")
        }

        val registration = DateTimeParser.parseRegistration(trimmed, now)
        if (registration != null) {
            repository.remember(registration.title, registration.dateTime.toEpochMilli())
            return CatReply("覚えたにゃ")
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
     * #144/#145: 「私の仕事」「今日の仕事」「仕事全部」等、仕事タスクの担当を
     * 尋ねる質問かどうかの判定。単に「仕事」という単語を含むだけでは判定しない
     * — 「明日仕事に行く」(予定登録)や「仕事は完了したよ」「見積書の仕事終わった」
     * (完了報告)のように、文中のどこかに「仕事」が出てくるだけの既存の登録/完了
     * フレーズを誤ってここで横取りしてしまわないようにするため。
     *
     * [stripWorkQuestionTrailer]で末尾の疑問表現(「は」「何」「ある」「？」等、
     * 音声認識で句読点が欠けた場合も含む)を1段階だけ剥がした残り([core])が
     * 「の仕事」で終わる(「私の仕事」「今日のみゆたんの仕事」等)か、「仕事」
     * そのもの(「仕事は？」→「仕事」)であれば質問とみなす。「仕事全部」
     * 「全部の仕事」も別途対象にする。それ以外は既存のDateTimeParser.isQuery()
     * による質問判定(「仕事について教えて」等)にだけ従う —
     * DateTimeParser.kt自体は変更しない。
     */
    private fun isWorkTaskQuestion(text: String): Boolean {
        if (!text.contains("仕事")) return false
        val core = stripWorkQuestionTrailer(text)
        if (core.endsWith("の仕事") || core == "仕事") return true
        if (core.contains("仕事全部") || core.contains("全部の仕事")) return true
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

    /** RoomStore.displayNameが未設定のまま「私の仕事」を聞かれた場合の返答。 */
    private fun unknownSpeakerReply(): String = "今どっちが話してるか分からないにゃ。まず設定で名前を選んでにゃ"

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
