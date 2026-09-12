package com.mspg.poicat.room

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** 表示名設定処理の診断専用ログタグ。PIN/認証トークン/個人情報は一切出力しない —
 * roomId/deviceIdもtake(8)で先頭のみに留める。原因特定後に削除予定の一時的なもの。 */
private const val DISPLAY_NAME_DEBUG_TAG = "DisplayNameDebug"

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
     * [onDiagnostic]は各チェックポイント(D, E, F1〜F6, G, H, I)に到達するたびに、その
     * ラベル文字だけを通知するオプションのコールバック(既定値は何もしない空ラムダ)。
     * adb/Android Studioが使えない環境でも、呼び出し元(RoomShareScreen)がこれを画面
     * 表示に使えるようにするための診断専用のフックで、動作そのものには一切影響しない。
     * 秘密情報は一切渡さない(渡すのは"D"等の1文字/短いラベルのみ)。
     */
    suspend fun setDisplayName(
        roomId: String,
        deviceId: String,
        displayName: String,
        onDiagnostic: (String) -> Unit = {},
    ): Result<Unit> {
        // D: この関数(RoomManager.setDisplayName)内部へ実際に到達したか。
        Log.d(DISPLAY_NAME_DEBUG_TAG, "D: setDisplayName() entered")
        onDiagnostic("D")
        return try {
            // E: roomId/deviceIdを実際に受け取れているか(先頭8文字のみ、秘密情報ではない)。
            Log.d(
                DISPLAY_NAME_DEBUG_TAG,
                "E: roomId=${roomId.take(8)}… deviceId=${deviceId.take(8)}…",
            )
            onDiagnostic("E")
            withTimeout(FIRESTORE_TASK_TIMEOUT_MS) {
                // F1〜F6: 元々1つだった"F"チェックポイントを、update()呼び出しの各段階
                // (参照取得→update()呼び出し→Task取得→await()直前→await()完了)へ細分化。
                // これによりFの内側の「どの同期/非同期ステップで止まっているか」を
                // スマホの画面だけで切り分けられるようにする(診断専用、動作は変えない)。
                Log.d(DISPLAY_NAME_DEBUG_TAG, "F1: about to get collection/document reference")
                onDiagnostic("F1")
                val docRef = db.collection(COLLECTION_ROOMS).document(roomId)
                Log.d(DISPLAY_NAME_DEBUG_TAG, "F2: document reference obtained")
                onDiagnostic("F2")

                Log.d(DISPLAY_NAME_DEBUG_TAG, "F3: about to call update(...)")
                onDiagnostic("F3")
                val task = docRef.update("members.$deviceId.displayName", displayName)
                Log.d(DISPLAY_NAME_DEBUG_TAG, "F4: update(...) returned a Task")
                onDiagnostic("F4")

                Log.d(DISPLAY_NAME_DEBUG_TAG, "F5: about to call Task.await()")
                onDiagnostic("F5")
                task.await()
                Log.d(DISPLAY_NAME_DEBUG_TAG, "F6: Task.await() completed")
                onDiagnostic("F6")

                // G: update().await()が例外無く完了(=成功)。
                Log.d(DISPLAY_NAME_DEBUG_TAG, "G: update().await() completed successfully")
                onDiagnostic("G")
            }
            Result.success(Unit)
        } catch (e: TimeoutCancellationException) {
            // H: 20秒のタイムアウトが実際に発火した(=ハングしていたことの証拠)。
            Log.d(DISPLAY_NAME_DEBUG_TAG, "H: timed out after ${FIRESTORE_TASK_TIMEOUT_MS}ms")
            onDiagnostic("H")
            Result.failure(e)
        } catch (e: Throwable) {
            // I: タイムアウト以外の例外(権限エラー・ネットワークエラー等)。
            Log.d(DISPLAY_NAME_DEBUG_TAG, "I: failed with ${e.javaClass.simpleName}: ${e.message}")
            onDiagnostic("I: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    private const val COLLECTION_PIN_ROOMS = "pinRooms"
    private const val COLLECTION_ROOMS = "rooms"
    private const val MAX_MEMBERS = 2
    private const val FIRESTORE_TASK_TIMEOUT_MS = 20_000L
}
