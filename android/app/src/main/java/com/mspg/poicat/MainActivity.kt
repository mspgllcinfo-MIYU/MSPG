package com.mspg.poicat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mspg.poicat.notify.ReminderWorker
import com.mspg.poicat.notify.ensureNotificationChannel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ChatRepository.init(applicationContext)
        ensureNotificationChannel(applicationContext)
        ReminderWorker.schedule(applicationContext)

        setContent {
            PoiCatTheme {
                AppRoot()
            }
        }
    }
}
