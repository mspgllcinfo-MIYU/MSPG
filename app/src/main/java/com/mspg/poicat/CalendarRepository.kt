package com.mspg.poicat

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
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
    private const val FILE_NAME = "schedules.json"

    private val _schedules = mutableStateListOf<ScheduleEntry>()
    val schedules: List<ScheduleEntry> get() = _schedules

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    fun addSchedule(date: LocalDate, time: String, title: String) {
        _schedules.add(
            ScheduleEntry(
                id = UUID.randomUUID().toString(),
                date = date,
                time = time.trim().takeIf { it.isNotBlank() },
                title = title.trim(),
            ),
        )
        persist()
    }

    fun schedulesOn(date: LocalDate): List<ScheduleEntry> =
        _schedules.filter { it.date == date }.sortedBy { it.time ?: "" }

    private fun persist() {
        val context = appContext ?: return
        val array = JSONArray()
        _schedules.forEach { entry ->
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("date", entry.date.toString())
            obj.put("time", entry.time)
            obj.put("title", entry.title)
            array.put(obj)
        }
        LocalJsonStore.write(context, FILE_NAME, array.toString())
    }

    private fun load() {
        val context = appContext ?: return
        val json = LocalJsonStore.read(context, FILE_NAME) ?: return
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                _schedules.add(
                    ScheduleEntry(
                        id = obj.getString("id"),
                        date = LocalDate.parse(obj.getString("date")),
                        time = if (obj.isNull("time")) null else obj.getString("time"),
                        title = obj.getString("title"),
                    ),
                )
            }
        }
    }
}
