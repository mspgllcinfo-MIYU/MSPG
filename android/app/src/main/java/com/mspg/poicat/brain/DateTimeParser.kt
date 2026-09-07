package com.mspg.poicat.brain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Very small rule-based parser for Japanese date/time phrases. It only
 * understands a fixed set of common expressions (relative days, "来週の水曜"
 * style weekdays, "N日後", explicit "M月D日", and a trailing "N時"). Anything
 * fancier is intentionally out of scope — this is a memory cat, not an NLU
 * model.
 */
object DateTimeParser {

    data class ParsedRegistration(val dateTime: LocalDateTime, val title: String)
    data class ParsedQuery(val keyword: String?, val dayFilter: LocalDate?)

    private val weekdayChar = mapOf(
        '月' to DayOfWeek.MONDAY,
        '火' to DayOfWeek.TUESDAY,
        '水' to DayOfWeek.WEDNESDAY,
        '木' to DayOfWeek.THURSDAY,
        '金' to DayOfWeek.FRIDAY,
        '土' to DayOfWeek.SATURDAY,
        '日' to DayOfWeek.SUNDAY,
    )

    fun isQuery(text: String): Boolean =
        text.contains("？") || text.contains("?") || text.contains("いつ")

    /**
     * Finds the first recognized date phrase in [text] and returns the resolved
     * date plus whatever's left after removing it, or null if nothing matched.
     * Shared by [parseRegistration] (date required) and [parseDueDate] (date
     * optional — a task without a due date is still a valid task).
     */
    private fun extractDate(text: String, now: LocalDateTime): Pair<LocalDate, String>? {
        var remaining = text
        var date: LocalDate? = null

        fun tryMatch(regex: Regex, resolve: (MatchResult) -> LocalDate): Boolean {
            if (date != null) return false
            val m = regex.find(remaining) ?: return false
            date = resolve(m)
            remaining = remaining.removeRange(m.range)
            return true
        }

        // "再来週の水曜" contains "来週の水曜" as a substring, so the more specific
        // (longer) phrase must be checked first or it never gets a chance to match.
        tryMatch(Regex("再来週の?([月火水木金土日])曜?日?")) { m ->
            thisWeekWeekday(now.toLocalDate(), weekdayChar.getValue(m.groupValues[1][0])).plusWeeks(2)
        }
        tryMatch(Regex("来週の?([月火水木金土日])曜?日?")) { m ->
            nextWeekWeekday(now.toLocalDate(), weekdayChar.getValue(m.groupValues[1][0]))
        }
        tryMatch(Regex("今週の?([月火水木金土日])曜?日?")) { m ->
            thisWeekWeekday(now.toLocalDate(), weekdayChar.getValue(m.groupValues[1][0]))
        }
        tryMatch(Regex("再来週")) { now.toLocalDate().plusWeeks(2) }
        tryMatch(Regex("明後日")) { now.toLocalDate().plusDays(2) }
        tryMatch(Regex("明日")) { now.toLocalDate().plusDays(1) }
        tryMatch(Regex("今日")) { now.toLocalDate() }
        tryMatch(Regex("(\\d{1,3})日後")) { m -> now.toLocalDate().plusDays(m.groupValues[1].toLong()) }
        tryMatch(Regex("(\\d{1,2})週間?後")) { m -> now.toLocalDate().plusWeeks(m.groupValues[1].toLong()) }
        tryMatch(Regex("(\\d{1,2})月(\\d{1,2})日")) { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            var d = LocalDate.of(now.year, month, day)
            if (d.isBefore(now.toLocalDate())) d = d.plusYears(1)
            d
        }
        tryMatch(Regex("([月火水木金土日])曜日?")) { m ->
            nearestWeekday(now.toLocalDate(), weekdayChar.getValue(m.groupValues[1][0]))
        }

        return date?.let { it to remaining }
    }

    fun parseRegistration(text: String, now: LocalDateTime = LocalDateTime.now()): ParsedRegistration? {
        val (resolvedDate, afterDate) = extractDate(text, now) ?: return null
        var remaining = afterDate

        var hour = 9
        var minute = 0
        Regex("(\\d{1,2})時(半)?").find(remaining)?.let { m ->
            hour = m.groupValues[1].toInt().coerceIn(0, 23)
            minute = if (m.groupValues[2] == "半") 30 else 0
            remaining = remaining.removeRange(m.range)
        }

        return ParsedRegistration(LocalDateTime.of(resolvedDate, LocalTime.of(hour, minute)), cleanTitle(remaining))
    }

    /**
     * Like [parseRegistration] but the date is optional, for task due dates —
     * unlike a schedule event, a task without any date is still meaningful.
     * Returns the found date (or null) alongside whatever text is left after
     * removing it (unchanged if no date was found).
     */
    fun parseDueDate(text: String, now: LocalDateTime = LocalDateTime.now()): Pair<LocalDate?, String> {
        val extraction = extractDate(text, now) ?: return null to text
        return extraction.first to extraction.second
    }

    // "再来週の" must be stripped before "来週の" — it contains "来週の" as a
    // substring, so checking the shorter phrase first would leave a stray "再".
    // Same reasoning applies to the memo-question phrases below: "について覚えてる"
    // must come before the bare "覚えてる" it contains, or the trailing "について"
    // would be left stuck to the keyword.
    private val queryScaffolding = listOf(
        "再来週の", "来週の", "今週の", "次の", "次は", "次",
        "の予定は", "の予定", "予定は", "予定",
        "について覚えてる", "について覚えてます", "について",
        "前にメモした", "前に言った", "前に覚えた",
        "なんだっけ", "だっけ", "覚えてますか", "覚えてる",
        "ですか", "です", "いつ", "は",
        "？", "?", "、", "。", " ", "　",
        "「", "」", "『", "』",
    )

    fun parseQuery(text: String, now: LocalDateTime = LocalDateTime.now()): ParsedQuery {
        var remaining = text
        var dayFilter: LocalDate? = null

        when {
            remaining.contains("明後日") -> {
                dayFilter = now.toLocalDate().plusDays(2)
                remaining = remaining.replace("明後日", "")
            }
            remaining.contains("明日") -> {
                dayFilter = now.toLocalDate().plusDays(1)
                remaining = remaining.replace("明日", "")
            }
            remaining.contains("今日") -> {
                dayFilter = now.toLocalDate()
                remaining = remaining.replace("今日", "")
            }
        }

        for (token in queryScaffolding) {
            remaining = remaining.replace(token, "")
        }

        return ParsedQuery(keyword = remaining.trim().ifBlank { null }, dayFilter = dayFilter)
    }

    /** A short, natural way to refer to a date relative to today ("明日", "水曜日", "来週の金曜日", "3月5日"). */
    fun formatWhen(date: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, date)
        return when {
            days == 0L -> "今日"
            days == 1L -> "明日"
            days == 2L -> "明後日"
            days in 3..6 -> weekdayLabel(date.dayOfWeek)
            days in 7..13 -> "来週の" + weekdayLabel(date.dayOfWeek)
            else -> "${date.monthValue}月${date.dayOfMonth}日"
        }
    }

    private fun weekdayLabel(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "月曜日"
        DayOfWeek.TUESDAY -> "火曜日"
        DayOfWeek.WEDNESDAY -> "水曜日"
        DayOfWeek.THURSDAY -> "木曜日"
        DayOfWeek.FRIDAY -> "金曜日"
        DayOfWeek.SATURDAY -> "土曜日"
        DayOfWeek.SUNDAY -> "日曜日"
    }

    private fun thisWeekWeekday(today: LocalDate, wd: DayOfWeek): LocalDate =
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays((wd.value - 1).toLong())

    private fun nextWeekWeekday(today: LocalDate, wd: DayOfWeek): LocalDate =
        thisWeekWeekday(today, wd).plusWeeks(1)

    private fun nearestWeekday(today: LocalDate, wd: DayOfWeek): LocalDate {
        var d = today
        while (d.dayOfWeek != wd) d = d.plusDays(1)
        return d
    }

    /** Strips leading/trailing particles and filler left over after removing a matched
     * date/verb phrase (e.g. "、薬を買う" → "薬を買う"). Reused for task titles too. */
    fun cleanTitle(raw: String, fallback: String = "予定"): String {
        var t = raw.trim()
        t = t.replace(Regex("^[のにはがを、,，]+"), "").trim()
        t = t.replace(Regex("(ね|よ|だよ|です|だ|。|、|！|!|\\.|,)+$"), "").trim()
        return t.ifBlank { fallback }
    }
}
