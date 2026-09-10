package com.mspg.poicat

import android.app.Activity
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
import com.mspg.poicat.drive.DriveFolderRepository
import kotlinx.coroutines.launch

// Connection画面専用の色 — 既存の各画面と同じトーン("大人かわいい×ちょっと高級×
// 無愛想な黒猫")をこのファイル内だけで再現。
private val ConnInk = Color(0xFF201E1D)
private val ConnCard = Color(0xFFEFE7DE)
private val ConnGold = Color(0xFFC9A66B)
private val ConnPink = Color(0xFFD98A9C)

private enum class FolderTarget(val label: String) {
    ALBUM("アルバム"),
    FILE("ファイル"),
}

/**
 * Phase 3: Googleサインイン + Driveフォルダ接続。
 *
 * 当初はGoogle Picker（WebView埋め込み）で既存の手動作成フォルダを選ばせる設計
 * だったが、実機テストでGoogleのWebViewセキュリティ制約（Cookieアクセス拒否→
 * 修正後は403）に阻まれ続けたため廃止した。Googleは2026年時点でWebView内での
 * Picker利用を非推奨としており、公式な代替は実際のWebバックエンド経由でのフル
 * ブラウザ遷移＋ディープリンク復帰という、新たなホスティング環境が要る構成のみ。
 *
 * 代わりに、drive.fileスコープが常に許可している「アプリが作成したファイルには
 * アプリ自身がその後もアクセスできる」性質だけを使うDriveFolderRepositoryへ
 * 切り替えた — WebView/Pickerは一切使わない。ボタン一つで「POI用/アルバム」
 * 「POI用/ファイル」フォルダを初回作成・以降は再利用する。ユーザーがGoogle
 * Drive側で何かを操作する必要は無い。
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
    var pendingTarget by remember { mutableStateOf<FolderTarget?>(null) }

    // 「POI用」ルートフォルダの中に、選んだ対象名（アルバム/ファイル）の子フォルダを
    // 見つけるか無ければ作る。Picker/WebViewは一切使わない、純粋なREST呼び出し。
    fun ensureFolderFor(target: FolderTarget, accessToken: String) {
        scope.launch {
            isBusy = true
            try {
                val poiRoot = DriveFolderRepository.ensureFolder(accessToken, "POI用", null).getOrThrow()
                val folder = DriveFolderRepository.ensureFolder(accessToken, target.label, poiRoot.id).getOrThrow()
                val displayName = "POI用/${folder.name}"
                when (target) {
                    FolderTarget.ALBUM -> { store.albumFolderId = folder.id; store.albumFolderName = displayName; albumFolderName = displayName }
                    FolderTarget.FILE -> { store.fileFolderId = folder.id; store.fileFolderName = displayName; fileFolderName = displayName }
                }
                statusText = "「$displayName」を接続したにゃ"
            } catch (e: Exception) {
                statusText = "フォルダの準備に失敗したにゃ：${e.message ?: e.javaClass.simpleName}"
            } finally {
                isBusy = false
            }
        }
    }

    val authResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data = result.data
        val target = pendingTarget
        pendingTarget = null
        if (data != null && target != null) {
            scope.launch {
                GoogleAuthManager.resumeAfterResolution(activity, data)
                    .onSuccess { token ->
                        cachedAccessToken = token
                        ensureFolderFor(target, token)
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

    fun authorizeThenEnsureFolder(target: FolderTarget) {
        val cached = cachedAccessToken
        if (cached != null) {
            ensureFolderFor(target, cached)
            return
        }
        isBusy = true
        scope.launch {
            GoogleAuthManager.requestDriveAuthorization(activity)
                .onSuccess { outcome ->
                    when (outcome) {
                        is GoogleAuthManager.AuthorizationOutcome.Granted -> {
                            cachedAccessToken = outcome.accessToken
                            ensureFolderFor(target, outcome.accessToken)
                        }
                        is GoogleAuthManager.AuthorizationOutcome.ResolutionNeeded -> {
                            pendingTarget = target
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

        ConnectionCard(title = "アルバム用フォルダ（POI用/アルバム）") {
            FolderRow(
                folderName = albumFolderName,
                enabled = signedInEmail != null && !isBusy,
                onSelect = { authorizeThenEnsureFolder(FolderTarget.ALBUM) },
            )
        }

        Spacer(Modifier.height(16.dp))

        ConnectionCard(title = "ファイル用フォルダ（POI用/ファイル）") {
            FolderRow(
                folderName = fileFolderName,
                enabled = signedInEmail != null && !isBusy,
                onSelect = { authorizeThenEnsureFolder(FolderTarget.FILE) },
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
        Text("まだ接続していないにゃ", color = ConnInk.copy(alpha = 0.5f))
        Spacer(Modifier.height(8.dp))
    }
    Button(
        onClick = onSelect,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = ConnPink.copy(alpha = 0.25f), contentColor = ConnInk),
        shape = RoundedCornerShape(percent = 50),
    ) { Text(if (folderName != null) "フォルダを確認しなおす" else "フォルダを接続する") }
}
