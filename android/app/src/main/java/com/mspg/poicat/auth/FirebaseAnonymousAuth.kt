package com.mspg.poicat.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * マリたんの安全なバックエンド中継(Cloudflare Worker、/v1/gemini/generate)を
 * 呼ぶためだけに必要な、Firebase匿名認証の窓口。[GoogleAuthManager.signIn]
 * (Googleアカウント本人でのサインイン、Google連携画面用)とは別物 — マリたんは
 * Google連携(現在ホーム画面から非表示)を一切経由せず、アプリ起動時に自動で
 * こちらだけ済ませておけば使える。ユーザー操作は不要。
 *
 * 既にサインイン済み(匿名でも、Google連携で本人アカウントでサインイン済みでも)
 * なら何もしない — 本人アカウントでサインイン済みの場合はそのIDトークンが
 * そのまま使われる(Cloudflare Worker側はuidが本人かどうかを区別しない)。
 */
object FirebaseAnonymousAuth {
    suspend fun ensureSignedIn() {
        if (FirebaseAuth.getInstance().currentUser != null) return
        FirebaseAuth.getInstance().signInAnonymously().await()
    }

    /** マリたんのGemini中継呼び出し直前に使う。未サインインならここで匿名サインイン
     * してからIDトークンを取得する。 */
    suspend fun currentIdToken(): String? {
        ensureSignedIn()
        return FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
    }
}
