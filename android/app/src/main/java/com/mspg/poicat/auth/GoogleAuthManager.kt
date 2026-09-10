package com.mspg.poicat.auth

import android.app.Activity
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * Google認証の窓口。サインインはCredential Manager経由でGoogleのIDトークンを取得し、
 * それをそのままFirebase Authへ渡す（匿名認証ではなくGoogleアカウント本人でサイン
 * インする — 共有Googleアカウントを使う2台のPOIが同じFirebase UIDになるようにする
 * ため、将来のFirestore同期・メンバー数制限の判定がシンプルになる）。
 *
 * Driveへのアクセス権は別枠のAuthorizationClientでdrive.fileスコープのみを要求する
 * （サインインとは独立した追加権限リクエスト）。
 */
object GoogleAuthManager {
    fun currentUserEmail(): String? = FirebaseAuth.getInstance().currentUser?.email

    suspend fun signIn(activity: Activity, webClientId: String): Result<Unit> = runCatching {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val response = CredentialManager.create(activity).getCredential(activity, request)

        val credential = response.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "unexpected credential type"
        }
        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(firebaseCredential).await()
        Unit
    }

    sealed class AuthorizationOutcome {
        data class Granted(val accessToken: String) : AuthorizationOutcome()
        data class ResolutionNeeded(val result: AuthorizationResult) : AuthorizationOutcome()
    }

    /**
     * drive.fileスコープが既に許可済みならアクセストークンをそのまま返す。ユーザーの
     * 同意画面が必要な場合はResolutionNeededを返す — 呼び出し側（Composable）が
     * ActivityResultLauncherでpendingIntentを起動し、その結果を [resumeAfterResolution]
     * に渡して初めてアクセストークンが得られる。
     */
    suspend fun requestDriveAuthorization(activity: Activity): Result<AuthorizationOutcome> = runCatching {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        if (result.hasResolution()) {
            AuthorizationOutcome.ResolutionNeeded(result)
        } else {
            AuthorizationOutcome.Granted(requireNotNull(result.accessToken))
        }
    }

    suspend fun resumeAfterResolution(activity: Activity, data: Intent): Result<String> = runCatching {
        val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        requireNotNull(result.accessToken)
    }
}
