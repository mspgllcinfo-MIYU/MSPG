package com.mspg.poicat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mspg.poicat.notify.ReminderWorker
import com.mspg.poicat.notify.ensureNotificationChannel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ChatRepository.init(applicationContext)
        ensureNotificationChannel(applicationContext)
        ReminderWorker.schedule(applicationContext)
        handleIncomingIntent(intent)

        setContent {
            PoiCatTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Shared-from-another-app entry point (SEND intent). Both onCreate (cold
     * start) and onNewIntent (already running, singleTask) funnel here so a
     * share is handled identically either way. The intent's action is
     * cleared immediately after being picked up so a later onCreate seeing
     * the same Intent object again (e.g. after a config change re-reads
     * getIntent()) doesn't reprocess it.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        lifecycleScope.launch {
            ShareIntentHandler.handle(applicationContext, intent)
        }
        intent.action = null
    }
}
