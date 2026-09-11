package com.mspg.poicat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mspg.poicat.notify.ReminderWorker
import com.mspg.poicat.notify.ensureNotificationChannel
import com.mspg.poicat.room.RoomBackfill
import com.mspg.poicat.room.RoomEventSync
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ChatRepository.init(applicationContext)
        ensureNotificationChannel(applicationContext)
        ReminderWorker.schedule(applicationContext)
        // ルーム未参加なら即noop（RoomEventSync.startListening内でチェック済み）。
        // 参加済みならFirestoreのリアルタイムリスナーを起動し、パートナー端末側の
        // 変更をローカルのcat_eventsへ反映する。
        RoomEventSync.startListening(applicationContext)
        // 参加済みでかつ前回の起動時にバックフィル(既存データの共有)が通信失敗等で
        // 途中までしか終わらなかった場合の自然な再試行機会。ルーム未参加、または
        // 前回までに全て送信済みなら実質何もしない(RoomBackfill参照)。
        lifecycleScope.launch { RoomBackfill.pushUnsyncedToRoom(this@MainActivity) }
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
            // Driveアップロード(GoogleAuthManager経由)にはActivityが要るため、ここだけ
            // applicationContextではなくthis(MainActivity)を渡す — importFromUri等の
            // 既存のローカル保存処理自体はContextのままで一切変わらない。
            ShareIntentHandler.handle(this@MainActivity, intent)
        }
        intent.action = null
    }
}
