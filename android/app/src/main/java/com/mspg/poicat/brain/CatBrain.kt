package com.mspg.poicat.brain

import com.mspg.poicat.data.CatEventRepository
import java.time.LocalDateTime

/**
 * The "memory cat" brain: everything is on-device pattern matching against
 * the Room database. No AI model, no network call. Given a line of chat
 * input it either remembers a new schedule/memo, answers a question from
 * what it already remembers, or just files the line away as a memo.
 */
class CatBrain(private val repository: CatEventRepository) {

    suspend fun respond(input: String): String {
        // Users often paste example phrases straight out of quoted instructions
        // (e.g. "「明日、病院」"); strip the quote marks so they don't end up
        // stuck in a saved title.
        val trimmed = input.trim().replace(Regex("[「」『』]"), "").trim()
        if (trimmed.isEmpty()) return "なに？にゃ"

        val now = LocalDateTime.now()

        if (DateTimeParser.isQuery(trimmed)) {
            return if (isTaskQuestion(trimmed)) answerTaskQuery(trimmed, now) else answerQuery(trimmed, now)
        }

        // Checked before schedule registration: "今日ゴミ出しやる" contains "今日", a
        // valid schedule date, but the "やる" ending means it's a to-do, not an event.
        val completionKeyword = extractTaskCompletionKeyword(trimmed)
        if (completionKeyword != null) {
            val task = repository.incompleteTasksMatching(completionKeyword).firstOrNull()
            return if (task != null) {
                repository.setTaskCompleted(task, true)
                "${task.title}終わったにゃ"
            } else {
                "そのタスクは見つからなかったにゃ"
            }
        }

        val taskContent = extractTaskCommand(trimmed)
        if (taskContent != null) {
            val (dueDate, titleRaw) = DateTimeParser.parseDueDate(taskContent, now)
            val title = DateTimeParser.cleanTitle(titleRaw, fallback = "タスク")
            repository.addTask(title, dueDate?.toEpochMilli())
            return "ポイに入れたにゃ"
        }

        val memoContent = extractMemoCommand(trimmed)
        if (memoContent != null) {
            // "明日病院だから覚えといて" uses a memo-style "覚えといて" trigger, but the
            // content itself names a date — that makes it a schedule, not a memo.
            val scheduleFromMemo = DateTimeParser.parseRegistration(memoContent, now)
            if (scheduleFromMemo != null) {
                repository.remember(scheduleFromMemo.title, scheduleFromMemo.dateTime.toEpochMilli())
                return "覚えたにゃ"
            }
            repository.remember(memoContent, null)
            return "メモしたにゃ"
        }

        val registration = DateTimeParser.parseRegistration(trimmed, now)
        if (registration != null) {
            repository.remember(registration.title, registration.dateTime.toEpochMilli())
        } else {
            repository.remember(DateTimeParser.cleanTitle(trimmed, fallback = trimmed), null)
        }
        return "覚えたにゃ"
    }

    private val taskTriggerSuffixes = listOf(
        "の忘れないで", "を忘れないで", "忘れないで",
        "の忘れずに", "を忘れずに", "忘れずに",
        "を忘れるな", "忘れるな",
        "忘れないように",
        "買わなきゃ", "買わないと",
        "やる", "買う",
    )

    // Bare dictionary-form verbs that, on their own with no other trigger, still read
    // as "something to do" ("牛乳買う") rather than a note or a calendar event.
    private val taskVerbEndings = listOf("買う")

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
        for (verb in memoVerbs) {
            for (memoSuffix in listOf("って$verb", "を$verb", verb)) {
                if (text.endsWith(memoSuffix)) {
                    val inner = text.removeSuffix(memoSuffix).trim().removeSuffix("の").removeSuffix("を").trim()
                    val matchedVerb = taskVerbEndings.firstOrNull { inner.endsWith(it) } ?: continue
                    return inner.removeSuffix(matchedVerb).trim().ifBlank { inner }
                }
            }
        }
        return null
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

    /** Recognizes "牛乳買ったよ"/"薬終わった" style completion and returns the search
     * keyword ("牛乳"/"薬") to look up the matching task by, or null. */
    private fun extractTaskCompletionKeyword(text: String): String? {
        for (suffix in taskCompletionSuffixes) {
            if (text.endsWith(suffix) && text.length > suffix.length) {
                val keyword = text.removeSuffix(suffix).trim()
                if (keyword.isNotBlank()) return keyword
            }
        }
        return null
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
}
