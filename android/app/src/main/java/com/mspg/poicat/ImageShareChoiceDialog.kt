package com.mspg.poicat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * #POI画像共有3択: image/*が共有された直後に必ず表示する、最小限のダイアログ。
 * ここではまだ何も保存しない — 4つのボタンのうちどれが押されたかを呼び出し元
 * (AppRoot)へそのまま伝えるだけ。1回の共有につき、これらのコールバックのうち
 * 1つだけが呼ばれる想定([AppRoot]側でpendingをnull化してから各処理を実行する
 * ため、二重実行はしない)。
 */
@Composable
fun ImageShareChoiceDialog(
    onReadSchedule: () -> Unit,
    onSaveToAlbum: () -> Unit,
    onSendToChat: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("この画像をどうする？") },
        text = {
            Column {
                TextButton(onClick = onReadSchedule, modifier = Modifier.fillMaxWidth()) {
                    Text("予定を読み取る")
                }
                TextButton(onClick = onSaveToAlbum, modifier = Modifier.fillMaxWidth()) {
                    Text("アルバムに保存")
                }
                TextButton(onClick = onSendToChat, modifier = Modifier.fillMaxWidth()) {
                    Text("猫AIに送る")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("キャンセル") }
        },
    )
}
