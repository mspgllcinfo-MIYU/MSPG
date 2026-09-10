package com.mspg.poicat.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mspg.poicat.data.CatEventRepository
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes (the shortest interval WorkManager allows for
 * periodic work) and fires local notifications for events entering their
 * "day before" or "1 hour before" reminder window. Purely on-device — no
 * network, no exact-alarm permission needed.
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = CatEventRepository(applicationContext)
        val now = System.currentTimeMillis()

        val dayWindowStart = now + TimeUnit.HOURS.toMillis(20)
        val dayWindowEnd = now + TimeUnit.HOURS.toMillis(28)
        repository.dueFor1DayReminder(dayWindowStart, dayWindowEnd).forEach { event ->
            postCatNotification(applicationContext, notificationId(event.id, 1), "明日だにゃ。${event.title}")
            repository.markReminded1Day(event)
        }

        val hourWindowEnd = now + TimeUnit.MINUTES.toMillis(65)
        repository.dueFor1HourReminder(now, hourWindowEnd).forEach { event ->
            postCatNotification(applicationContext, notificationId(event.id, 2), "あと1時間だにゃ。${event.title}")
            repository.markReminded1Hour(event)
        }

        return Result.success()
    }

    private fun notificationId(eventId: Long, slot: Int): Int = (eventId.toInt() * 10) + slot

    companion object {
        private const val UNIQUE_WORK_NAME = "cat_reminder_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
