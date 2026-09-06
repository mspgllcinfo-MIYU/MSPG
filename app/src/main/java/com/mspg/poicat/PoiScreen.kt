package com.mspg.poicat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** プレビュー表示用に、長辺が概ね[targetSize]pxになるよう縮小して読み込む。 */
private fun decodeSampledBitmap(context: Context, uri: Uri, targetSize: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

    var sampleSize = 1
    val halfWidth = bounds.outWidth / 2
    val halfHeight = bounds.outHeight / 2
    while (halfWidth / sampleSize >= targetSize && halfHeight / sampleSize >= targetSize) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
}

@Composable
fun rememberPhotoPreview(uri: Uri): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { decodeSampledBitmap(context, uri, 800) }?.asImageBitmap()
    }
    return bitmap
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

    var isSending by remember { mutableStateOf(false) }

    fun sendToHusband(itemDraft: PoiDraft) {
        isSending = true
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "旦那ちゃんにポイしたニャ",
                actionLabel = "取り消す",
                duration = SnackbarDuration.Short,
            )
            isSending = false
            if (result == SnackbarResult.ActionPerformed) {
                // 取り消し：下書きは残し、そのまま編集・再送できるようにする（BOXへは記録しない）。
                snackbarHostState.showSnackbar("送信を取り消したニャ", duration = SnackbarDuration.Short)
            } else {
                PoiRepository.addSent(itemDraft)
                draft = null
            }
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
                onSend = { sendToHusband(currentDraft) },
                isSending = isSending,
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
    isSending: Boolean,
) {
    Column {
        Text(text = "内容を確認してニャ", fontSize = 14.sp, color = Color.Gray)

        Column(modifier = Modifier.padding(top = 8.dp)) {
            when (draft) {
                is PoiDraft.Photo -> {
                    val preview = rememberPhotoPreview(draft.uri)
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = "選択した写真",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShapeCompat)
                                .background(Color(0xFFEDE6E0)),
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .clip(RoundedCornerShapeCompat)
                                .background(Color(0xFFEDE6E0)),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is PoiDraft.FileDoc -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShapeCompat)
                        .background(Color(0xFFEDE6E0))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null)
                    Text(text = draft.name, modifier = Modifier.padding(start = 12.dp), fontSize = 16.sp)
                }
                is PoiDraft.MemoText -> OutlinedTextField(
                    value = draft.text,
                    onValueChange = { onDraftChange(draft.copy(text = it)) },
                    label = { Text("メモ") },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                )
                is PoiDraft.Link -> OutlinedTextField(
                    value = draft.url,
                    onValueChange = { onDraftChange(draft.copy(url = it)) },
                    label = { Text("リンク（URL）") },
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(modifier = Modifier.padding(top = 20.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !isSending) {
                Text("戻る")
            }
            Button(
                onClick = onSend,
                enabled = !isSending && isDraftReadyToSend(draft),
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
