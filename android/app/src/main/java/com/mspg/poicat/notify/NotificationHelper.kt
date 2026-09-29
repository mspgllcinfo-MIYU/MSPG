package com.mspg.poicat.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mspg.poicat.R

const val CHANNEL_ID = "cat_reminders"

fun ensureNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        CHANNEL_ID,
        "猫からのお知らせ",
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = "予定が近づいたときに猫が教えてくれます"
    }
    manager.createNotificationChannel(channel)
}

/** [contentIntent]はタップ時に開く画面(任意) — 既存の呼び出し元は指定しないため、
 * これまで通りタップしても何も起きない(既存の1日前/1時間前リマインダーの動作は
 * 変更していない)。[text]が複数行でも通知を展開すれば全文読めるよう、
 * BigTextStyleを常に付ける(1行の既存テキストには見た目上の影響なし)。 */
fun postCatNotification(context: Context, notificationId: Int, text: String, contentIntent: PendingIntent? = null) {
    ensureNotificationChannel(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("猫AI")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .apply { contentIntent?.let { setContentIntent(it) } }
        .build()

    runCatching {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
