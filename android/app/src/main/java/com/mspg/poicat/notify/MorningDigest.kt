package com.mspg.poicat.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mspg.poicat.MainActivity
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDateTime
import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val PREFS_NAME = "morning_digest"
private const val KEY_LAST_SENT_DATE = "last_sent_date" // ISO LocalDate文字列("2026-09-11")
private const val MORNING_HOUR = 9
private const val NOTIFICATION_ID = 999_999

/**
 * 毎朝、今日の予定・タスクをまとめて通知する「朝の通知」。
 *
 * 新しい定期ジョブは追加せず、既存の[ReminderWorker]の15分ごとのチェックに
 * 便乗する形にしている — WorkManagerの定期ジョブ枠を増やさず、端末再起動後の
 * 継続も既存の仕組み(WorkManagerが自身で再スケジュール)にそのまま乗る。
 * そのぶん、通知が実際に届くのは「9:00を過ぎて最初にチェックが走ったタイミング」
 * (最大で9:00〜9:15の間のどこか)になる — AlarmManagerの正確アラームは使わない
 * 方針のため、秒単位での9:00固定にはしていない。
 *
 * 「今日はもう送った」の判定はSharedPreferencesに日付文字列を1つ保存するだけの
 * 単純な方式 — 既存のRoom DB(cat_events等)には一切触れない。
 */
object MorningDigest {
    /** 送るべきタイミングなら送信して記録する。既に今日分を送っていれば何もしない。 */
    suspend fun checkAndSend(context: Context) {
        val now = LocalDateTime.now()
        if (now.hour < MORNING_HOUR) return

        val today = now.toLocalDate()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_SENT_DATE, null) == today.toString()) return

        val repository = CatEventRepository(context.applicationContext)
        val startOfDay = today.toEpochMilli()
        val endOfDay = today.plusDays(1).toEpochMilli() - 1
        val events = repository.onDay(startOfDay, endOfDay)
        val tasks = repository.incompleteTasksDueOrUndated(startOfDay, endOfDay)

        postCatNotification(
            context,
            NOTIFICATION_ID,
            buildDigestText(events, tasks),
            contentIntent = openAppPendingIntent(context),
        )

        prefs.edit().putString(KEY_LAST_SENT_DATE, today.toString()).apply()
    }

    private fun buildDigestText(events: List<CatEvent>, tasks: List<CatEvent>): String {
        if (events.isEmpty() && tasks.isEmpty()) {
            return "今日は予定ないにゃ〜 のんびりするにゃ🐱"
        }
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        val lines = mutableListOf("今日の予定だにゃ🐱")
        events.forEach { event ->
            val time = event.dateTime?.toLocalDateTime()?.format(timeFormat)
            lines += if (time != null) "$time ${event.title}" else event.title
        }
        tasks.forEach { task -> lines += "・${task.title}" }
        return lines.joinToString("\n")
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
