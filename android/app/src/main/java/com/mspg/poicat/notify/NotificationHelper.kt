package com.mspg.poicat.notify

import android.app.NotificationChannel
import android.app.NotificationManager
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

fun postCatNotification(context: Context, notificationId: Int, text: String) {
    ensureNotificationChannel(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("猫AI")
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()

    runCatching {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
