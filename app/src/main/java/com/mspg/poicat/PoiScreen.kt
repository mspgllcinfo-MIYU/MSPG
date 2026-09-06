package com.mspg.poicat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 送信先はVer.1では「旦那ちゃん」1人固定。 */
sealed interface PoiDraft {
    data class Photo(val uri: Uri) : PoiDraft
    data class MemoText(val text: String) : PoiDraft
    data class Link(val url: String) : PoiDraft
    data class FileDoc(val uri: Uri, val name: String) : PoiDraft
}

/** Androidの共有メニューから渡された内容をPOIの下書きに変換する。仕分けできなければnull。 */
fun extractSharedDraft(context: Context, intent: Intent?): PoiDraft? {
    if (intent == null || intent.action != Intent.ACTION_SEND) return null
    val type = intent.type.orEmpty()
    return when {
        type == "text/plain" -> {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text.startsWith("http://") || text.startsWith("https://")) {
                PoiDraft.Link(text)
            } else {
                PoiDraft.MemoText(text)
            }
        }
        type.startsWith("image/") -> {
            val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: return null
            PoiDraft.Photo(uri)
        }
        else -> {
            val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: return null
            PoiDraft.FileDoc(uri, queryDisplayName(context, uri))
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(name: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        getParcelableExtra(name)
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
    return name ?: uri.lastPathSegment ?: "ファイル"
}

@Composable
fun PoiScreen(
    prefill: PoiDraft?,
    onPrefillConsumed: () -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf<PoiDraft?>(null) }

    LaunchedEffect(prefill) {
        if (prefill != null) {
            draft = prefill
            onPrefillConsumed()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) draft = PoiDraft.Photo(uri) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) draft = PoiDraft.FileDoc(uri, queryDisplayName(context, uri)) }

    fun sendToHusband() {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "旦那ちゃんにポイしたニャ",
                actionLabel = "取り消す",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.Dismissed) {
                draft = null
            }
            // 「取り消す」が押された場合は下書きを残し、そのまま編集・再送できるようにする。
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(text = "ポイ", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "旦那ちゃんへ送るものを選んでニャ",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        val currentDraft = draft
        if (currentDraft == null) {
            PoiCategoryList(
                onSelectPhoto = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onSelectMemo = { draft = PoiDraft.MemoText("") },
                onSelectLink = { draft = PoiDraft.Link("") },
                onSelectFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            )
        } else {
            PoiComposer(
                draft = currentDraft,
                onDraftChange = { draft = it },
                onCancel = { draft = null },
                onSend = { sendToHusband() },
            )
        }
    }
}

@Composable
private fun PoiCategoryList(
    onSelectPhoto: () -> Unit,
    onSelectMemo: () -> Unit,
    onSelectLink: () -> Unit,
    onSelectFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PoiCategoryRow("写真", Icons.Default.Photo, onSelectPhoto)
        PoiCategoryRow("メモ", Icons.Default.Notes, onSelectMemo)
        PoiCategoryRow("リンク", Icons.Default.Link, onSelectLink)
        PoiCategoryRow("PDF・ファイル", Icons.Default.InsertDriveFile, onSelectFile)
    }
}

@Composable
private fun PoiCategoryRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShapeCompat)
            .background(Color(0xFFEDE6E0))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(text = label, modifier = Modifier.padding(start = 12.dp), fontSize = 16.sp)
    }
}

private val RoundedCornerShapeCompat = RoundedCornerShape(16.dp)

@Composable
private fun PoiComposer(
    draft: PoiDraft,
    onDraftChange: (PoiDraft) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Column {
        when (draft) {
            is PoiDraft.Photo -> Text(text = "写真を選択しました", fontSize = 16.sp)
            is PoiDraft.FileDoc -> Text(text = "選択したファイル：${draft.name}", fontSize = 16.sp)
            is PoiDraft.MemoText -> OutlinedTextField(
                value = draft.text,
                onValueChange = { onDraftChange(draft.copy(text = it)) },
                label = { Text("メモ") },
                modifier = Modifier.fillMaxWidth(),
            )
            is PoiDraft.Link -> OutlinedTextField(
                value = draft.url,
                onValueChange = { onDraftChange(draft.copy(url = it)) },
                label = { Text("リンク（URL）") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(modifier = Modifier.padding(top = 20.dp)) {
            OutlinedButton(onClick = onCancel) {
                Text("戻る")
            }
            Button(
                onClick = onSend,
                enabled = isDraftReadyToSend(draft),
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text("旦那ちゃんへ送る")
            }
        }
    }
}

private fun isDraftReadyToSend(draft: PoiDraft): Boolean = when (draft) {
    is PoiDraft.MemoText -> draft.text.isNotBlank()
    is PoiDraft.Link -> draft.url.isNotBlank()
    is PoiDraft.Photo, is PoiDraft.FileDoc -> true
}
