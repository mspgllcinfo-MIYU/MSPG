package com.mspg.poicat.data

import android.content.Context

class CatEventRepository(context: Context) {
    private val dao = AppDatabase.get(context).catEventDao()

    /** Inserts a new dated/date-less item, unless a dated one with the same title+time already exists. */
    suspend fun remember(title: String, dateTime: Long?): CatEvent {
        if (dateTime != null) {
            dao.findDuplicate(title, dateTime)?.let { return it }
        }
        val event = CatEvent(title = title, dateTime = dateTime)
        val id = dao.insert(event)
        return event.copy(id = id)
    }

    /** Full edit of an existing event; resets both reminder flags so a changed time can notify again. */
    suspend fun edit(event: CatEvent, title: String, dateTime: Long?) {
        dao.update(event.copy(title = title, dateTime = dateTime, reminded1Day = false, reminded1Hour = false))
    }

    suspend fun delete(event: CatEvent) = dao.delete(event)

    suspend fun upcoming(from: Long = System.currentTimeMillis()) = dao.upcoming(from)

    suspend fun upcomingMatching(keyword: String, from: Long = System.currentTimeMillis()) =
        dao.upcomingMatching(keyword, from)

    suspend fun onDay(startOfDay: Long, endOfDay: Long) = dao.onDay(startOfDay, endOfDay)

    suspend fun between(start: Long, end: Long) = dao.between(start, end)

    suspend fun memos() = dao.memos()

    suspend fun memosMatching(keyword: String) = dao.memosMatching(keyword)

    suspend fun allMatching(keyword: String) = dao.allMatching(keyword)

    suspend fun addTask(title: String, dueDateTime: Long?): CatEvent {
        val event = CatEvent(title = title, dateTime = dueDateTime, isTask = true)
        val id = dao.insert(event)
        return event.copy(id = id)
    }

    suspend fun tasks() = dao.tasks()

    suspend fun incompleteTasks() = dao.incompleteTasks()

    suspend fun incompleteTasksDueOrUndated(start: Long, end: Long) = dao.incompleteTasksDueOrUndated(start, end)

    suspend fun incompleteTasksDueBy(end: Long) = dao.incompleteTasksDueBy(end)

    suspend fun incompleteTasksMatching(keyword: String) = dao.incompleteTasksMatching(keyword)

    suspend fun setTaskCompleted(task: CatEvent, completed: Boolean) {
        dao.update(task.copy(completed = completed))
    }

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
