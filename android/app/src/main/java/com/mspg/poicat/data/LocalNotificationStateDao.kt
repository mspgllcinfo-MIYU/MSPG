package com.mspg.poicat.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface LocalNotificationStateDao {
    /**
     * 1日前通知を表示済みとして端末ローカルにだけ記録する(Firestoreへは送らない)。
     * まだ行が無ければnotified1Hour=falseで新規作成し、既にあればnotified1Hourの
     * 現在値をサブクエリで引き継いだ上でnotified1Dayだけをtrueにする — 単純な
     * INSERT OR REPLACEで行全体を書き換えると、既にtrueだったnotified1Hourを
     * falseへ巻き戻してしまうため。
     */
    @Query(
        "INSERT OR REPLACE INTO local_notification_state (catEventId, notified1Day, notified1Hour) " +
            "VALUES (:catEventId, 1, COALESCE((SELECT notified1Hour FROM local_notification_state WHERE catEventId = :catEventId), 0))",
    )
    suspend fun markNotified1Day(catEventId: Long)

    /** [markNotified1Day]と対称。notified1Dayの現在値を引き継いだ上でnotified1Hourだけをtrueにする。 */
    @Query(
        "INSERT OR REPLACE INTO local_notification_state (catEventId, notified1Day, notified1Hour) " +
            "VALUES (:catEventId, COALESCE((SELECT notified1Day FROM local_notification_state WHERE catEventId = :catEventId), 0), 1)",
    )
    suspend fun markNotified1Hour(catEventId: Long)

    /** 予定の日時が変わった際に、通知済み状態を最初からやり直すためのクリア。
     * 行が無ければ何もしない。 */
    @Query("DELETE FROM local_notification_state WHERE catEventId = :catEventId")
    suspend fun clear(catEventId: Long)
}
