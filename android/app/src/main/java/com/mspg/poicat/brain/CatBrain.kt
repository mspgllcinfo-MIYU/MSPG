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

        val registration = DateTimeParser.parseRegistration(trimmed, now)
        if (registration != null) {
            repository.remember(registration.title, registration.dateTime.toEpochMilli())
        } else {
            repository.remember(trimmed, null)
        }
        return "覚えたにゃ"
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
                return "${memo.title}って覚えてるにゃ"
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
        text.contains("やること") || text.contains("タスク") || text.contains("やるべきこと")

    private suspend fun answerTaskQuery(text: String, now: LocalDateTime): String {
        val today = now.toLocalDate()
        val scopedToToday = text.contains("今日")

        val tasks = if (scopedToToday) {
            repository.incompleteTasksDue(today.toEpochMilli(), today.plusDays(1).toEpochMilli() - 1)
        } else {
            repository.incompleteTasks()
        }

        if (tasks.isEmpty()) {
            return if (scopedToToday) "今日やることはないにゃ" else "残ってるタスクはないにゃ"
        }
        val titles = tasks.joinToString("、") { it.title }
        return if (scopedToToday) "今日は${titles}だにゃ" else "残ってるのは${titles}だにゃ"
    }
}
