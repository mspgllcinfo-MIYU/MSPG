package com.mspg.poicat

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.drive.DriveConnectionStore
import com.mspg.poicat.drive.DrivePickerActivity
import kotlinx.coroutines.launch

// Connection画面専用の色 — 既存の各画面と同じトーン("大人かわいい×ちょっと高級×
// 無愛想な黒猫")をこのファイル内だけで再現。
private val ConnInk = Color(0xFF201E1D)
private val ConnCard = Color(0xFFEFE7DE)
private val ConnGold = Color(0xFFC9A66B)
private val ConnPink = Color(0xFFD98A9C)

private enum class PickTarget { ALBUM, FILE }

/**
 * Phase 3: Googleサインイン + Driveフォルダ接続（アルバム/ファイル用フォルダを一度
 * だけ選択）。実際の写真/ファイル同期ロジックはまだここには無い — サインインと
 * フォルダの紐付けだけを行う画面。
 */
@Composable
fun ConnectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val store = remember { DriveConnectionStore(context) }

    var signedInEmail by remember { mutableStateOf(GoogleAuthManager.currentUserEmail()) }
    var albumFolderName by remember { mutableStateOf(store.albumFolderName) }
    var fileFolderName by remember { mutableStateOf(store.fileFolderName) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var cachedAccessToken by remember { mutableStateOf<String?>(null) }
    var pendingPickTarget by remember { mutableStateOf<PickTarget?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val target = pendingPickTarget
        pendingPickTarget = null
        isBusy = false
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            val id = result.data?.getStringExtra(DrivePickerActivity.EXTRA_FOLDER_ID)
            val name = result.data?.getStringExtra(DrivePickerActivity.EXTRA_FOLDER_NAME)
            if (id != null && name != null) {
                when (target) {
                    PickTarget.ALBUM -> { store.albumFolderId = id; store.albumFolderName = name; albumFolderName = name }
                    PickTarget.FILE -> { store.fileFolderId = id; store.fileFolderName = name; fileFolderName = name }
                }
                statusText = "「$name」を接続したにゃ"
            }
        } else {
            statusText = "フォルダ選択をやめたにゃ"
        }
    }

    fun launchPickerFor(target: PickTarget, accessToken: String) {
        pendingPickTarget = target
        pickerLauncher.launch(
            Intent(context, DrivePickerActivity::class.java).apply {
                putExtra(DrivePickerActivity.EXTRA_ACCESS_TOKEN, accessToken)
                putExtra(DrivePickerActivity.EXTRA_API_KEY, context.getString(R.string.google_api_key))
            },
        )
    }

    val authResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data = result.data
        val target = pendingPickTarget
        if (data != null && target != null) {
            scope.launch {
                GoogleAuthManager.resumeAfterResolution(activity, data)
                    .onSuccess { token ->
                        cachedAccessToken = token
                        launchPickerFor(target, token)
                    }
                    .onFailure {
                        isBusy = false
                        statusText = "Drive権限の取得に失敗したにゃ"
                    }
            }
        } else {
            isBusy = false
        }
    }

    fun authorizeThenPick(target: PickTarget) {
        val cached = cachedAccessToken
        if (cached != null) {
            launchPickerFor(target, cached)
            return
        }
        isBusy = true
        scope.launch {
            GoogleAuthManager.requestDriveAuthorization(activity)
                .onSuccess { outcome ->
                    when (outcome) {
                        is GoogleAuthManager.AuthorizationOutcome.Granted -> {
                            isBusy = false
                            cachedAccessToken = outcome.accessToken
                            launchPickerFor(target, outcome.accessToken)
                        }
                        is GoogleAuthManager.AuthorizationOutcome.ResolutionNeeded -> {
                            pendingPickTarget = target
                            val pendingIntent = outcome.result.pendingIntent
                            if (pendingIntent != null) {
                                authResolutionLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                                )
                            } else {
                                isBusy = false
                                statusText = "Drive権限リクエストに失敗したにゃ"
                            }
                        }
                    }
                }
                .onFailure {
                    isBusy = false
                    statusText = "Drive権限リクエストに失敗したにゃ"
                }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← もどる", color = ConnInk) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Google連携", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ConnInk)
        Spacer(Modifier.height(20.dp))

        ConnectionCard(title = "サインイン") {
            val email = signedInEmail
            if (email != null) {
                Text("サインイン済み：$email", color = ConnInk.copy(alpha = 0.7f))
            } else {
                Text("まだサインインしていないにゃ", color = ConnInk.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        isBusy = true
                        scope.launch {
                            // finallyでisBusyを必ず戻す — onSuccess/onFailureのどちらかに
                            // 必ず到達する保証が(端末側のPlay Services実装次第で)無いため、
                            // ここで確実にリセットしないと、ボタンが永久にグレーアウトした
                            // まま操作不能になり得る。
                            try {
                                GoogleAuthManager.signIn(activity, context.getString(R.string.default_web_client_id))
                                    .onSuccess {
                                        signedInEmail = GoogleAuthManager.currentUserEmail()
                                        statusText = "サインインしたにゃ"
                                    }
                                    .onFailure {
                                        statusText = "サインインに失敗したにゃ：${it.message ?: it.javaClass.simpleName}"
                                    }
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = ConnPink, contentColor = Color.White),
                    shape = RoundedCornerShape(percent = 50),
                ) { Text("Googleでサインイン") }
            }
        }

        Spacer(Modifier.height(16.dp))

        ConnectionCard(title = "アルバム用フォルダ") {
            FolderRow(
                folderName = albumFolderName,
                enabled = signedInEmail != null && !isBusy,
                onSelect = { authorizeThenPick(PickTarget.ALBUM) },
            )
        }

        Spacer(Modifier.height(16.dp))

        ConnectionCard(title = "ファイル用フォルダ") {
            FolderRow(
                folderName = fileFolderName,
                enabled = signedInEmail != null && !isBusy,
                onSelect = { authorizeThenPick(PickTarget.FILE) },
            )
        }

        statusText?.let { text ->
            Spacer(Modifier.height(16.dp))
            Text(text, color = ConnGold)
        }
    }
}

@Composable
private fun ConnectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ConnCard)
            .padding(14.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ConnInk)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun FolderRow(folderName: String?, enabled: Boolean, onSelect: () -> Unit) {
    if (folderName != null) {
        Text("接続済み：$folderName", color = ConnInk.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
    } else {
        Text("まだ選択していないにゃ", color = ConnInk.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))
    }
    Button(
        onClick = onSelect,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = ConnPink.copy(alpha = 0.25f), contentColor = ConnInk),
        shape = RoundedCornerShape(percent = 50),
    ) { Text(if (folderName != null) "フォルダを選びなおす" else "フォルダを選ぶ") }
}
