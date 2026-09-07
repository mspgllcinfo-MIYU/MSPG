package com.mspg.poicat.data

import android.content.Context

class CatEventRepository(context: Context) {
    private val dao = AppDatabase.get(context).catEventDao()

    suspend fun remember(title: String, dateTime: Long?): CatEvent {
        val event = CatEvent(title = title, dateTime = dateTime)
        val id = dao.insert(event)
        return event.copy(id = id)
    }

    suspend fun upcoming(from: Long = System.currentTimeMillis()) = dao.upcoming(from)

    suspend fun upcomingMatching(keyword: String, from: Long = System.currentTimeMillis()) =
        dao.upcomingMatching(keyword, from)

    suspend fun onDay(startOfDay: Long, endOfDay: Long) = dao.onDay(startOfDay, endOfDay)

    suspend fun dueFor1DayReminder(windowStart: Long, windowEnd: Long) =
        dao.dueFor1DayReminder(windowStart, windowEnd)

    suspend fun dueFor1HourReminder(windowStart: Long, windowEnd: Long) =
        dao.dueFor1HourReminder(windowStart, windowEnd)

    suspend fun markReminded1Day(event: CatEvent) {
        dao.update(event.copy(reminded1Day = true))
    }

    suspend fun markReminded1Hour(event: CatEvent) {
        dao.update(event.copy(reminded1Hour = true))
    }
}
