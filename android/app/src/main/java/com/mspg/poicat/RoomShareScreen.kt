package com.mspg.poicat

import android.app.Activity
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.auth.GoogleAuthManager
import com.mspg.poicat.room.NotSignedInException
import com.mspg.poicat.room.RoomEventSync
import com.mspg.poicat.room.RoomFullException
import com.mspg.poicat.room.RoomManager
import com.mspg.poicat.room.RoomStore
import kotlinx.coroutines.launch

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
