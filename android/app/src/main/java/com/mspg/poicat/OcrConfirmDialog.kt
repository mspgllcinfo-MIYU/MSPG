package com.mspg.poicat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDateTime
import com.mspg.poicat.ocr.OcrIntakeResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * #POI画像OCR: 画像OCRから読み取った内容をCatEventとして登録する前の、必須の
 * ユーザー確認画面。[PendingSharedLocation]用の確認ダイアログと同じ「まだ何も
 * 保存しない、OKした場合だけ[onConfirm]経由で呼び出し元(AppRoot)が保存処理を行う」
 * という設計。タイトル/日付/時刻/場所はここで自由に修正できる — OCR/AI解析の
 * 誤りをここで正すことが主目的。[OcrIntakeResult.ocrText](元画像から読み取った
 * 生テキスト)は照合用に折りたたみ表示する。
 */
@Composable
fun OcrConfirmDialog(
    result: OcrIntakeResult,
    onCancel: () -> Unit,
    onConfirm: (title: String, dateTime: Long, locationText: String?, alsoShowAsTask: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val initialDateTime = (result.suggestedDateTime ?: System.currentTimeMillis()).toLocalDateTime()
    var title by remember { mutableStateOf(result.suggestedTitle ?: "") }
    var date by remember { mutableStateOf(initialDateTime.toLocalDate()) }
    var time by remember { mutableStateOf(initialDateTime.toLocalTime()) }
    var locationText by remember { mutableStateOf(result.suggestedLocationText ?: "") }
    var alsoShowAsTask by remember { mutableStateOf(false) }
    var showOcrText by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("画像から予定を登録") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("読み取った内容を確認してください。間違っていれば修正できます。")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("タイトル") },
                    modifier = Modifier,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth -> date = LocalDate.of(year, month + 1, dayOfMonth) },
                            date.year,
                            date.monthValue - 1,
                            date.dayOfMonth,
                        ).show()
                    },
                ) { Text("日付: ${date.monthValue}月${date.dayOfMonth}日") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> time = LocalTime.of(hour, minute) },
                            time.hour,
                            time.minute,
                            true,
                        ).show()
                    },
                ) { Text("時刻: %02d:%02d".format(time.hour, time.minute)) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text("場所(任意)") },
                    modifier = Modifier,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoShowAsTask, onCheckedChange = { alsoShowAsTask = it })
                    Text("タスクにも表示する")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showOcrText = !showOcrText }) {
                    Text(if (showOcrText) "OCR原文を閉じる" else "OCR原文を見る(照合用)")
                }
                if (showOcrText) {
                    Text(result.ocrText.ifBlank { "(文字を読み取れませんでした)" })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    val dateTime = LocalDateTime.of(date, time).toEpochMilli()
                    onConfirm(title.trim(), dateTime, locationText.trim().ifBlank { null }, alsoShowAsTask)
                },
            ) { Text("登録") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("キャンセル") }
        },
    )
}
