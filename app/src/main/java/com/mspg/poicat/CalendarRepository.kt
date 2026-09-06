package com.mspg.poicat

import androidx.compose.runtime.mutableStateListOf
import java.time.LocalDate
import java.util.UUID

/** Ver.1では予定は常に2人共有（「自分だけ」の選択肢はない）。 */
data class ScheduleEntry(
    val id: String,
    val date: LocalDate,
    val time: String?,
    val title: String,
)

object CalendarRepository {
    private val _schedules = mutableStateListOf<ScheduleEntry>()
    val schedules: List<ScheduleEntry> get() = _schedules

    fun addSchedule(date: LocalDate, time: String, title: String) {
        _schedules.add(
            ScheduleEntry(
                id = UUID.randomUUID().toString(),
                date = date,
                time = time.trim().takeIf { it.isNotBlank() },
                title = title.trim(),
            ),
        )
    }

    fun schedulesOn(date: LocalDate): List<ScheduleEntry> =
        _schedules.filter { it.date == date }.sortedBy { it.time ?: "" }
}
