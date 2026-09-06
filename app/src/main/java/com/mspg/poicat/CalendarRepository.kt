package com.mspg.poicat

import androidx.compose.runtime.mutableStateListOf
import java.time.LocalDate
import java.util.UUID

/** Ver.1では「自分だけ / 2人共有」は選択項目のみ。実際の共有同期はFirebase導入後。 */
data class ScheduleEntry(
    val id: String,
    val date: LocalDate,
    val time: String?,
    val title: String,
    val shared: Boolean,
)

object CalendarRepository {
    private val _schedules = mutableStateListOf<ScheduleEntry>()
    val schedules: List<ScheduleEntry> get() = _schedules

    fun addSchedule(date: LocalDate, time: String, title: String, shared: Boolean) {
        _schedules.add(
            ScheduleEntry(
                id = UUID.randomUUID().toString(),
                date = date,
                time = time.trim().takeIf { it.isNotBlank() },
                title = title.trim(),
                shared = shared,
            ),
        )
    }

    fun schedulesOn(date: LocalDate): List<ScheduleEntry> =
        _schedules.filter { it.date == date }.sortedBy { it.time ?: "" }
}
