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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.drive.DriveConnectionStore
import com.mspg.poicat.drive.DriveFolderRepository
import com.mspg.poicat.drive.RoomCatalogSync
import com.mspg.poicat.room.NotSignedInException
import com.mspg.poicat.room.RoomBackfill
import com.mspg.poicat.room.RoomEventSync
import com.mspg.poicat.room.RoomDriveTombstoneSync
import com.mspg.poicat.room.RoomFullException
import com.mspg.poicat.room.RoomManager
import com.mspg.poicat.room.RoomStore
import kotlinx.coroutines.launch

private enum class DriveFolderTarget(val label: String) {
    ALBUM("アルバム"),
    FILE("ファイル"),
}

// ConnectionScreen.kt と同じトーンをこのファイル内だけで再現(共有はしない、意図的な重複)。
private val RoomInk = Color(0xFF201E1D)
private val RoomCard = Color(0xFFEFE7DE)
private val RoomGold = Color(0xFFC9A66B)
private val RoomPink = Color(0xFFD98A9C)

/**
 * 「夫婦でシェア（ルーム）」専用の入口。
 *
 * 背景: 4桁PINによるルーム共有機能自体は[RoomManager]/[RoomStore]/[RoomEventSync]として
 * 実装済み(コミットf59e7ab)だったが、そのUI(ConnectionScreen内の「夫婦でシェア」カード)
 * への唯一の入口だった「Google連携」タイルがホーム画面から非表示化された(コミット
 * 001ed37、ユーザー指示による"完成版"確定の一部)ため、以後どの端末からもPIN入力画面へ
 * 到達できなくなっていた。
 *
 * この画面は「Google連携」タイル自体は復活させず(ユーザー指示)、ルーム共有(4桁PIN)
 * だけに絞った専用入口として新設する。ロジックはConnectionScreen.ktが使っているのと
 * 同じ[RoomManager]/[RoomStore]/[RoomEventSync]/[GoogleAuthManager]をそのまま呼ぶ
 * (処理自体を書き換えていない) — このファイルはUIのみの新規追加。
 * ConnectionScreen.kt自体は一切変更していない(Driveフォルダ接続カード等は引き続き
 * 非表示のまま)。
 *
 * ルーム参加にはGoogleサインインが前提([RoomManager.createOrJoinRoom]が
 * [NotSignedInException]を投げる)ため、最小限のサインイン導線もこの画面内に含む —
 * これは「Drive連携設定」の再露出ではなく、ルーム機能そのものの必須手順。
 */
@Composable
fun RoomShareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val roomStore = remember { RoomStore(context) }

    var signedInEmail by remember { mutableStateOf(GoogleAuthManager.currentUserEmail()) }
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    var roomId by remember { mutableStateOf(roomStore.roomId) }
    var pinInput by remember { mutableStateOf("") }
    var roomStatusText by remember { mutableStateOf<String?>(null) }
    var roomBusy by remember { mutableStateOf(false) }

    // 【Drive接続導線】ユーザーが毎回手動でフォルダを選ぶ設計にはしない — 既存の
    // 「POI用/アルバム」「POI用/ファイル」フォルダをDriveFolderRepository.ensureFolder
    // (findFolder-or-create、既存フォルダがあれば必ずそれを使い、重複作成しない)経由で
    // 自動検出・接続する。albumFolderId/fileFolderIdが既に端末に保存済みの場合はその値を
    // そのまま尊重し、何も変更しない。
    val driveStore = remember { DriveConnectionStore(context) }
    var albumFolderName by remember { mutableStateOf(driveStore.albumFolderName) }
    var fileFolderName by remember { mutableStateOf(driveStore.fileFolderName) }
    var driveBusy by remember { mutableStateOf(false) }
    var driveStatusText by remember { mutableStateOf<String?>(null) }
    var cachedDriveAccessToken by remember { mutableStateOf<String?>(null) }
    var driveAuthorized by remember { mutableStateOf(driveStore.albumFolderId != null || driveStore.fileFolderId != null) }

    suspend fun ensureDriveFolder(target: DriveFolderTarget, accessToken: String) {
        val poiRoot = DriveFolderRepository.ensureFolder(accessToken, "POI用", null).getOrElse {
            driveStatusText = "フォルダの確認に失敗したにゃ：${it.message ?: it.javaClass.simpleName}"
            return
        }
        val folder = DriveFolderRepository.ensureFolder(accessToken, target.label, poiRoot.id).getOrElse {
            driveStatusText = "フォルダの確認に失敗したにゃ：${it.message ?: it.javaClass.simpleName}"
            return
        }
        val displayName = "POI用/${folder.name}"
        when (target) {
            DriveFolderTarget.ALBUM -> {
                driveStore.albumFolderId = folder.id
                driveStore.albumFolderName = displayName
                albumFolderName = displayName
            }
            DriveFolderTarget.FILE -> {
                driveStore.fileFolderId = folder.id
                driveStore.fileFolderName = displayName
                fileFolderName = displayName
            }
        }
    }

    // 未接続のフォルダだけを対象にする — 既に接続済み(albumFolderId/fileFolderIdが
    // 保存済み)の側には一切触れない。接続できた分は、既存の共有アルバム/ファイルを
    // すぐに取り込む(RoomCatalogSync)。取り込みに失敗してもここでのフォルダ接続自体は
    // 成功したまま扱う — ローカルの利用を止めないため。
    suspend fun ensureMissingDriveFolders(accessToken: String) {
        driveAuthorized = true
        if (driveStore.albumFolderId == null) ensureDriveFolder(DriveFolderTarget.ALBUM, accessToken)
        if (driveStore.fileFolderId == null) ensureDriveFolder(DriveFolderTarget.FILE, accessToken)
        runCatching { RoomCatalogSync.refreshAlbumCatalog(activity, PhotoRepository(context.applicationContext)) }
        runCatching { RoomCatalogSync.refreshFileCatalog(activity, FileRepository(context.applicationContext)) }
    }

    val driveAuthResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data = result.data
        if (data != null) {
            scope.launch {
                try {
                    GoogleAuthManager.resumeAfterResolution(activity, data)
                        .onSuccess { token ->
                            cachedDriveAccessToken = token
                            ensureMissingDriveFolders(token)
                        }
                        .onFailure {
                            driveStatusText = "Drive権限の取得に失敗したにゃ：${it.message ?: it.javaClass.simpleName}"
                        }
                } finally {
                    driveBusy = false
                }
            }
        } else {
            driveBusy = false
            driveStatusText = "同意画面から結果を受け取れなかったにゃ"
        }
    }

    fun connectDrive() {
        val cached = cachedDriveAccessToken
        if (cached != null) {
            driveBusy = true
            scope.launch {
                try { ensureMissingDriveFolders(cached) } finally { driveBusy = false }
            }
            return
        }
        driveBusy = true
        scope.launch {
            var awaitingConsent = false
            try {
                GoogleAuthManager.requestDriveAuthorization(activity)
                    .onSuccess { outcome ->
                        when (outcome) {
                            is GoogleAuthManager.AuthorizationOutcome.Granted -> {
                                cachedDriveAccessToken = outcome.accessToken
                                ensureMissingDriveFolders(outcome.accessToken)
                            }
                            is GoogleAuthManager.AuthorizationOutcome.ResolutionNeeded -> {
                                val pendingIntent = outcome.result.pendingIntent
                                if (pendingIntent != null) {
                                    awaitingConsent = true
                                    driveAuthResolutionLauncher.launch(
                                        IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                                    )
                                } else {
                                    driveStatusText = "Drive権限リクエストに失敗したにゃ"
                                }
                            }
                        }
                    }
                    .onFailure {
                        driveStatusText = "Drive権限リクエストに失敗したにゃ：${it.message ?: it.javaClass.simpleName}"
                    }
            } finally {
                if (!awaitingConsent) driveBusy = false
            }
        }
    }

    // サインイン済みで、まだ未接続のフォルダがある場合、まずはユーザー操作なしに
    // "サイレントに"(=同意画面を割り込ませずに)自動検出だけ試みる。既にdrive.file
    // 権限を許可済みであれば、ここで無操作のままアルバム/ファイル双方が自動的に
    // 「接続済み」になる。許可がまだ(ResolutionNeeded)の場合はここでは何もせず、
    // 「Driveを接続」ボタン経由のユーザー操作を待つ。
    LaunchedEffect(signedInEmail) {
        if (signedInEmail == null) return@LaunchedEffect
        if (driveStore.albumFolderId != null && driveStore.fileFolderId != null) return@LaunchedEffect
        val outcome = GoogleAuthManager.requestDriveAuthorization(activity).getOrNull()
        if (outcome is GoogleAuthManager.AuthorizationOutcome.Granted) {
            cachedDriveAccessToken = outcome.accessToken
            ensureMissingDriveFolders(outcome.accessToken)
        }
    }

    fun joinRoom() {
        val pin = pinInput.trim()
        if (pin.length != 4 || pin.any { !it.isDigit() }) {
            roomStatusText = "4桁の数字を入力してにゃ"
            return
        }
        roomBusy = true
        scope.launch {
            try {
                RoomManager.createOrJoinRoom(pin, roomStore.deviceId)
                    .onSuccess { newRoomId ->
                        roomStore.roomId = newRoomId
                        roomId = newRoomId
                        roomStatusText = "ルームに参加したにゃ"
                        RoomEventSync.startListening(context.applicationContext)
                        RoomDriveTombstoneSync.startListening(context.applicationContext)
                        // 参加前からあった自分側の既存データ(まだ誰とも共有していない
                        // 予定/タスク/メモ/写真/ファイル)を一括で送る。ローカルの表示・
                        // データには一切影響しない、後追いのfire-and-forget処理。
                        scope.launch { RoomBackfill.pushUnsyncedToRoom(activity) }
                        // Drive接続済みなら、参加直後にも一度カタログを取り込んでおく —
                        // アルバム/ファイル画面を開くタイミングだけに頼らない。
                        scope.launch {
                            runCatching { RoomCatalogSync.refreshAlbumCatalog(activity, PhotoRepository(context.applicationContext)) }
                            runCatching { RoomCatalogSync.refreshFileCatalog(activity, FileRepository(context.applicationContext)) }
                        }
                    }
                    .onFailure {
                        roomStatusText = when (it) {
                            is RoomFullException -> it.message
                            is NotSignedInException -> it.message
                            else -> "ルーム参加に失敗したにゃ：${it.message ?: it.javaClass.simpleName}"
                        }
                    }
            } finally {
                roomBusy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Drive連携カードが増え縦に長くなったため、ConnectionScreen.ktの
            // 「夫婦でシェア」カードが画面下に見切れた問題と同じ対策(be741f1)を
            // 最初から適用しておく — 機種の画面サイズ/文字サイズによらず末尾まで
            // 到達できるようにする。
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← もどる", color = RoomInk) }
        }
        Spacer(Modifier.height(8.dp))
        Text("夫婦でシェア", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = RoomInk)
        Spacer(Modifier.height(16.dp))

        // PINの役割の誤解(「アプリのロック解除」)を避けるための明示。
        RoomCardBox(title = "この4桁は「合言葉」だにゃ") {
            Text(
                "これはアプリのロック解除PINではないにゃ。ご主人の端末で同じ4桁を入力すると、" +
                    "2台のPOIが同じ「ルーム」としてつながって、予定・タスク・メモ・アルバムを" +
                    "共有できるようになるにゃ（1ルームにつき最大2台まで）。",
                color = RoomInk.copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(16.dp))

        if (signedInEmail == null) {
            RoomCardBox(title = "サインイン") {
                Text("ルームに参加するには、先にGoogleサインインが必要にゃ", color = RoomInk.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        isBusy = true
                        scope.launch {
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
                    colors = ButtonDefaults.buttonColors(containerColor = RoomPink, contentColor = Color.White),
                    shape = RoundedCornerShape(percent = 50),
                ) { Text("Googleでサインイン") }
                statusText?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(text, color = RoomGold)
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Text("サインイン済み：${signedInEmail}", fontSize = 12.sp, color = RoomInk.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
        }

        RoomCardBox(title = "ルームの合言葉") {
            val currentRoomId = roomId
            if (currentRoomId != null) {
                Text("参加済み：このルームでPOI内データを共有中にゃ", color = RoomInk.copy(alpha = 0.7f))
            } else {
                Text(
                    "4桁の番号を決めて、2台とも同じ番号を入力すると同じルームになるにゃ" +
                        "（違う番号なら別のルーム）。",
                    color = RoomInk.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { value -> pinInput = value.filter { it.isDigit() }.take(4) },
                    label = { Text("4桁の番号") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { joinRoom() },
                    enabled = !roomBusy && signedInEmail != null,
                    colors = ButtonDefaults.buttonColors(containerColor = RoomPink.copy(alpha = 0.25f), contentColor = RoomInk),
                    shape = RoundedCornerShape(percent = 50),
                ) { Text("ルームに参加する") }
                if (signedInEmail == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("先に上の「Googleでサインイン」が必要にゃ", color = RoomGold, fontSize = 12.sp)
                }
            }
            roomStatusText?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(text, color = RoomGold)
            }
        }

        Spacer(Modifier.height(16.dp))

        RoomCardBox(title = "Google Drive連携（アルバム/ファイルの共有に必要）") {
            DriveStatusLine("Google Drive", connected = driveAuthorized)
            DriveStatusLine("アルバム", connected = albumFolderName != null)
            DriveStatusLine("ファイル", connected = fileFolderName != null)
            if (albumFolderName == null || fileFolderName == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "既存の「POI用」フォルダがGoogle Driveにあれば自動的に見つけて使うにゃ" +
                        "（新しく作り直したり、中身を消したりはしないにゃ）。",
                    color = RoomInk.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { connectDrive() },
                    enabled = !driveBusy && signedInEmail != null,
                    colors = ButtonDefaults.buttonColors(containerColor = RoomPink.copy(alpha = 0.25f), contentColor = RoomInk),
                    shape = RoundedCornerShape(percent = 50),
                ) { Text("Driveを接続") }
                if (signedInEmail == null) {
                    Spacer(Modifier.height(4.dp))
                    Text("先に上の「Googleでサインイン」が必要にゃ", color = RoomGold, fontSize = 12.sp)
                }
            }
            driveStatusText?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(text, color = RoomGold)
            }
        }
    }
}

@Composable
private fun DriveStatusLine(label: String, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label：", color = RoomInk.copy(alpha = 0.7f))
        Text(
            if (connected) "接続済み" else "未接続",
            color = if (connected) RoomInk.copy(alpha = 0.7f) else RoomGold,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RoomCardBox(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RoomCard)
            .padding(14.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = RoomInk)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}
