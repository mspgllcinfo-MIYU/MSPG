package com.mspg.poicat.room

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** ルームが既に2台（他デバイス）で満室で、この端末がまだメンバーでない場合に投げる。 */
class RoomFullException : Exception("このルームは既に2台で利用中にゃ")

/** サインインしていない状態でルーム参加を試みた場合に投げる。 */
class NotSignedInException : Exception("先にGoogleサインインが必要にゃ")

/**
 * 4桁PINによるルーム参加/作成の窓口。
 *
 * 【設計】4桁PINそのものをFirestoreの直接IDや暗号鍵として使わない。代わりに:
 * 1. PINをSHA-256でハッシュ化した文字列(pinHash)だけを`pinRooms/{pinHash}`のドキュメントIDに使う
 *    — Firebaseコンソールを覗いても生のPINがそのまま見えることはない。
 * 2. `pinRooms/{pinHash}`は「そのPINに対応する内部roomId(ランダムなUUID)」を指すだけの
 *    小さな参照テーブル。実データは全て`rooms/{roomId}/...`側に置き、PIN自体はどこにも
 *    実データのキーとして使わない。
 * 3. 同じPINを2台目が入力した時点で`pinRooms/{pinHash}`が既に存在するので、同じroomIdを
 *    再利用して合流する。存在しなければ、その端末が「ルームの初代作成者」として新しい
 *    roomIdを生成する。
 *
 * 【2台制限】`rooms/{roomId}`ドキュメントの`members`フィールド（{deviceId: {joinedAt}}の
 * マップ、最大2エントリ）で管理する。Firebase Authのuidではなく[RoomStore.deviceId]
 * （端末ごとに生成されるランダムID）をキーにしている — 夫婦が同じGoogleアカウントを
 * 共有していてもFirebase UIDが同じになり得るため、uidでは2台を区別できないため。
 * サーバー側の関数は使わず（Cloud FunctionsはBlazeプラン=課金が必要）、Firestoreの
 * トランザクション（クライアントSDKのみで動く機能、Sparkの無料枠内）で
 * 「読んで、まだ2人未満か既にメンバーなら書く、それ以外は失敗させる」を保証する。
 * 加えてFirebaseコンソール側のSecurity Rulesでも同じ条件を強制する必要がある
 * （クライアントが送ってくるdeviceId自体はサーバー側で検証できない値なので、これは
 * 悪意ある第三者への防御ではなく、正規のアプリ2台までという運用上の制約）。
 */
object RoomManager {
    private val db by lazy { FirebaseFirestore.getInstance() }

    fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** [pin]は4桁の数字文字列であること（呼び出し側のUIで検証済みを前提とする）。 */
    suspend fun createOrJoinRoom(pin: String, deviceId: String): Result<String> = runCatching {
        if (FirebaseAuth.getInstance().currentUser == null) throw NotSignedInException()
        val pinHash = sha256Hex(pin)
        db.runTransaction { transaction ->
            val pinRef = db.collection(COLLECTION_PIN_ROOMS).document(pinHash)
            val pinSnap = transaction.get(pinRef)
            val roomId = if (pinSnap.exists()) {
                pinSnap.getString("roomId") ?: throw IllegalStateException("pinRooms/$pinHash has no roomId")
            } else {
                UUID.randomUUID().toString()
            }
            val roomRef = db.collection(COLLECTION_ROOMS).document(roomId)
            val roomSnap = transaction.get(roomRef)

            if (!roomSnap.exists()) {
                // このPINで最初のルーム作成（＝この端末が1台目）。pinRooms/roomsの両方を
                // 同じトランザクションで書く。
                transaction.set(
                    roomRef,
                    mapOf(
                        "members" to mapOf(deviceId to mapOf("joinedAt" to FieldValue.serverTimestamp())),
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
                if (!pinSnap.exists()) {
                    transaction.set(pinRef, mapOf("roomId" to roomId, "createdAt" to FieldValue.serverTimestamp()))
                }
            } else {
                @Suppress("UNCHECKED_CAST")
                val members = (roomSnap.get("members") as? Map<String, Any?>) ?: emptyMap()
                if (!members.containsKey(deviceId)) {
                    if (members.size >= MAX_MEMBERS) throw RoomFullException()
                    val updatedMembers = members + (deviceId to mapOf("joinedAt" to FieldValue.serverTimestamp()))
                    transaction.update(roomRef, "members", updatedMembers)
                }
                // 既にメンバーなら何もしない（再参加/再確認は成功として扱う）。
            }
            roomId
        }.await()
    }

    /**
     * この端末(deviceId)の表示名(「みゆたん」「かっちゃん」)をルームへ登録する。
     *
     * 【後方互換性】`members`は元々`{deviceId: {joinedAt}}`という形(表示名を持たない)
     * だった。ここではドット区切りのフィールドパス`members.$deviceId.displayName`
     * だけをFirestoreの`update()`で書く — これは対象の1フィールドだけを追加/上書き
     * するピンポイントな更新で、`members`ドキュメント全体や他デバイスのエントリ
     * (`members.<相手のdeviceId>`)・既存の`joinedAt`には一切触れない。
     *
     * 旧バージョンの端末(この関数自体が無いバージョン)は、`members`をMapとして
     * 読むだけで`displayName`という未知のキーの有無を一切気にしない(RoomManager.
     * createOrJoinRoomの既存の読み取りコード参照)ため、このフィールドが増えても
     * 旧バージョン側の参加/再参加処理には影響しない。
     *
     * ルームの作り直し・PINの変更・再参加は一切発生しない — 既存のroomId/members
     * ドキュメントへの追記のみ。
     *
     * Play Services側のTask.awaitが実機で完了コールバックを一切呼ばないまま
     * 固まることがある既知の問題(GoogleAuthManagerの認証処理で先に確認済み、同じ
     * 対策)への対応として、[FIRESTORE_TASK_TIMEOUT_MS]でタイムアウトさせる。
     */
    suspend fun setDisplayName(roomId: String, deviceId: String, displayName: String): Result<Unit> = runCatching {
        withTimeout(FIRESTORE_TASK_TIMEOUT_MS) {
            db.collection(COLLECTION_ROOMS).document(roomId)
                .update("members.$deviceId.displayName", displayName)
                .await()
        }
    }

    private const val COLLECTION_PIN_ROOMS = "pinRooms"
    private const val COLLECTION_ROOMS = "rooms"
    private const val MAX_MEMBERS = 2
    private const val FIRESTORE_TASK_TIMEOUT_MS = 20_000L
}
