package com.mspg.poicat.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * この端末(Android実機)で、指定した[CatEvent]の1日前/1時間前リマインダー通知を
 * 実際に表示済みかどうかだけを記録する、端末ローカル専用テーブル。
 *
 * [CatEvent.reminded1Day]/[CatEvent.reminded1Hour]は4桁PINルーム共有(Firestore
 * 同期)のフィールドで、「どちらかの端末が通知した」ことしか意味しない —
 * パートナー端末が先に通知すると、その値がこちらにも同期され、この端末自身の
 * 通知が抑制されてしまう実機不具合があった。このテーブルは
 * [com.mspg.poicat.room.RoomEventSync]のどこからも一切参照されない(pushの
 * toMap()にも、受信マージ処理にも含まれない) — Firestoreへは絶対に同期されず、
 * 「このAndroid端末で実際に通知を表示したか」という、物理的に端末単位でしか
 * 成立しない情報だけを保持する。新版端末における通知済み判定の正本はこの
 * テーブルのみで、reminded1Day/reminded1Hourは一切参照しない。
 *
 * [catEventId]は[CatEvent.id](ローカルの主キー、端末ごとに独立した値)を参照する
 * 外部キー。CatEvent行が削除されれば(ローカル削除・Firestore経由の削除どちらの
 * 経路でも)ON DELETE CASCADEでこの行も自動的に削除される — 削除経路ごとに
 * 個別のクリーンアップコードを書く必要はない。
 */
@Entity(
    tableName = "local_notification_state",
    foreignKeys = [
        ForeignKey(
            entity = CatEvent::class,
            parentColumns = ["id"],
            childColumns = ["catEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LocalNotificationState(
    @PrimaryKey val catEventId: Long,
    val notified1Day: Boolean = false,
    val notified1Hour: Boolean = false,
)
