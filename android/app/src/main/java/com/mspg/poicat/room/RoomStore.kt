package com.mspg.poicat.room

import android.content.Context
import java.util.UUID

/**
 * ルーム共有の小さな永続状態 — [com.mspg.poicat.drive.DriveConnectionStore]と同じ形の
 * SharedPreferences。既存のRoom DB（AppDatabase/PhotoDatabase/FileDatabase）とは
 * 完全に独立している。
 *
 * deviceId: この端末（アプリのインストール）を一意に識別するランダムなID。初回アクセス時
 * に一度だけ生成され、以後同じ値を使い続ける。夫婦が同じGoogleアカウントを共有していても
 * （＝同じFirebase UIDになっても）2台をきちんと区別できるようにするため、Firebase Auth
 * のuidではなく端末ごとに独立したこの値でルームの2人（2台）制限を管理する。
 *
 * roomId: 参加済みルームの内部ID（4桁PINそのものではない、ランダムな値）。未参加ならnull。
 */
class RoomStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    var roomId: String?
        get() = prefs.getString(KEY_ROOM_ID, null)
        set(value) { prefs.edit().putString(KEY_ROOM_ID, value).apply() }

    /** この端末の利用者の表示名(「みゆたん」「かっちゃん」)。未設定ならnull —
     * 誰かが自分で選ぶまでは推測・自動割り当てを一切行わない。ローカルの表示・
     * 判定用のキャッシュで、正本は[RoomManager.setDisplayName]で書き込む
     * Firestore側の`rooms/{roomId}`の`members.{deviceId}.displayName`。 */
    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) { prefs.edit().putString(KEY_DISPLAY_NAME, value).apply() }

    companion object {
        private const val PREFS_NAME = "room_share"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_DISPLAY_NAME = "display_name"
    }
}
