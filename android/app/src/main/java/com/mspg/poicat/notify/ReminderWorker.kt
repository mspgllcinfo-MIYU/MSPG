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
 *
 * #POI通知修正: 通知可否の判定は[CatEventRepository.due1DayLocally]/
 * [due1HourLocally](端末ローカル専用のlocal_notification_stateベース)で行う —
 * reminded1Day/reminded1Hour(Firestore共有フィールド)はこの判定には一切使わない。
 * パートナー端末が先に通知しても、この端末の通知は抑制されない。
 *
 * 担当時間帯は境界(予定まで2時間)で完全に排他的に分かれ、同一予定が同一tickで
 * 1日前通知と1時間前通知の両方に該当することはない:
 * - 1日前通知: 予定まで2時間より長く24時間以内(`(dateTime-now) in (2h, 24h]`)。
 * - 1時間前通知: 予定まで2時間以内、または予定を過ぎて3時間以内
 *   (`(dateTime-now) in (-3h, 2h]`)。
 *
 * 上記の「2時間まで」「過ぎて3時間まで」という余裕は、通常運用時の本来の
 * タイミング(24時間前・1時間前)そのものではなく、WorkManagerの遅延・端末オフ/
 * オフライン等で判定tickを取りこぼした場合のcatch-up用の保険 — 未通知
 * (notified1Day/1Hourがfalse)である限り毎tick再判定されるため、遅延しても
 * 範囲内に復帰すれば拾われる。範囲外(24時間より先、または3時間より前に経過済み)の
 * 予定は対象にならず、無制限な過去の追いかけにはならない。
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = CatEventRepository(applicationContext)
        val now = System.currentTimeMillis()
        val hour = TimeUnit.HOURS.toMillis(1)
        val handoff = now + 2 * hour

        repository.due1DayLocally(lowerBoundExclusive = handoff, upperBoundInclusive = now + 24 * hour)
            .forEach { event ->
                postCatNotification(applicationContext, notificationId(event.id, 1), "明日だにゃ。${event.title}")
                repository.markNotified1DayLocally(event.id)
                // 旧かっちゃん版がまだ参照している共有フィールドへも、互換性のため
                // 引き続き書き込む(この端末自身の判定にはもう使わない)。
                repository.markReminded1Day(event)
            }

        repository.due1HourLocally(lowerBoundExclusive = now - 3 * hour, upperBoundInclusive = handoff)
            .forEach { event ->
                postCatNotification(applicationContext, notificationId(event.id, 2), "あと1時間だにゃ。${event.title}")
                repository.markNotified1HourLocally(event.id)
                repository.markReminded1Hour(event)
            }

        // 毎朝9時台の「今日の予定」まとめ通知。新しい定期ジョブは追加せず、この
        // 既存チェックに便乗している — 詳細はMorningDigest参照。
        MorningDigest.checkAndSend(applicationContext)

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
