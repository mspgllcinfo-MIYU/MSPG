package com.mspg.poicat

import com.google.firebase.auth.FirebaseAuth
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Anonymous Firebase Authentication. There is no sign-in UI: every app
 * instance is signed in automatically on first launch so it can call the
 * Cloudflare Worker with a Firebase ID token.
 */
object AuthRepository {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    suspend fun ensureSignedIn() {
        if (auth.currentUser != null) return
        suspendCancellableCoroutine<Unit> { cont ->
            auth.signInAnonymously()
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    suspend fun idToken(): String {
        ensureSignedIn()
        val user = auth.currentUser ?: error("サインインに失敗しました")
        return suspendCancellableCoroutine { cont ->
            user.getIdToken(false)
                .addOnSuccessListener { result ->
                    val token = result.token
                    if (token != null) {
                        cont.resume(token)
                    } else {
                        cont.resumeWithException(IllegalStateException("IDトークンを取得できませんでした"))
                    }
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}
