package com.mspg.poicat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mspg.poicat.brain.toLocalDateTime
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.StoredFile
import com.mspg.poicat.drive.FileDriveSync
import com.mspg.poicat.drive.RoomCatalogSync
import java.io.File
import kotlinx.coroutines.launch

// Step: File-only design tokens, matching every other Poi sub-tab
// ("大人かわいい×ちょっと高級×無愛想な黒猫"). Scoped to this file deliberately —
// Theme.kt stays untouched until this look is promoted (Step0).
private val FileInk = Color(0xFF201E1D) // 墨色
private val FileCream = Color(0xFFF7F3EF) // 生成り — matches Theme.kt's page background
private val FileCard = Color(0xFFEFE7DE) // a shade deeper than the page, for file rows
private val FileGold = Color(0xFFC9A66B) // restrained accent, never a fill color

/**
 * ファイル tab: lists files received via Android's share sheet (PDF, Word,
 * Excel, PowerPoint, plain text, or any other non-image type — images keep
 * going to アルバム, unchanged). Each row opens the file through whatever app
 * on the device can handle its MIME type, or can be deleted. Files are saved
 * once, in [FileRepository]'s app-private storage, and only ever referenced
 * (never copied) from here — the same shape as AlbumScreen for photos.
 */
@Composable
fun FileScreen(embedded: Boolean = false) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val repository = remember { FileRepository(context.applicationContext) }

    var files by remember { mutableStateOf<List<StoredFile>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<StoredFile?>(null) }

    suspend fun reload() {
        files = repository.all()
    }

    LaunchedEffect(Unit) {
        // ルーム共有(4桁PIN)が有効な場合のみ、パートナー端末が共有Driveフォルダへ追加
        // したファイルをローカルへ取り込む（ルーム未参加なら即noop）。
        runCatching { RoomCatalogSync.refreshFileCatalog(activity, repository) }
        reload()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Embedded (inside Poi's own ファイル タブ) skips this screen's own outer
            // padding entirely — Poi's own padding around the whole tab area already
            // provides it, so adding it again here would double the margin.
            .padding(if (embedded) 0.dp else 20.dp),
    ) {
        if (!embedded) {
            Text("ファイル", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = FileInk)
            Spacer(Modifier.height(16.dp))
        }

        if (files.isEmpty()) {
            Text(
                text = "ファイルはまだないにゃ",
                color = FileInk.copy(alpha = 0.5f),
            )
        } else {
            // Capped at 640dp and centered so the list doesn't stretch edge-to-edge on
            // a Fold's unfolded, much wider screen — same as every other Poi sub-tab.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        onClick = { openStoredFile(context, file) },
                        onSaveToDevice = {
                            scope.launch {
                                val success = exportToDeviceStorage(
                                    context = context,
                                    sourceFile = File(file.filePath),
                                    displayName = file.fileName,
                                    mimeType = file.mimeType,
                                    isImage = false,
                                )
                                Toast.makeText(
                                    context,
                                    if (success) "端末に保存したにゃ" else "保存できなかったにゃ",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onDelete = { pendingDelete = file },
                    )
                }
            }
        }
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = FileCard,
            titleContentColor = FileInk,
            textContentColor = FileInk,
            title = { Text("削除しますか？") },
            text = { Text(file.fileName) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.delete(file)
                        pendingDelete = null
                        reload()
                        // ローカル削除は上で既に完了済み — この先のDrive削除試行が何であれ、
                        // ここまでの結果(画面から消えたファイル)には影響しない。
                        FileDriveSync.syncDeletedFile(activity, file)
                    }
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun FileRow(file: StoredFile, onClick: () -> Unit, onSaveToDevice: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FileCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(file.fileName, color = FileInk)
            val saved = file.savedAt.toLocalDateTime()
            val extension = file.fileName.substringAfterLast('.', "").uppercase().ifBlank { "ファイル" }
            Text(
                text = "$extension ・ ${saved.monthValue}月${saved.dayOfMonth}日 " +
                    "${saved.hour.toString().padStart(2, '0')}:${saved.minute.toString().padStart(2, '0')}",
                fontSize = 12.sp,
                color = FileGold,
            )
        }
        IconButton(onClick = onSaveToDevice) {
            Text("⬇", color = FileInk)
        }
        IconButton(onClick = onDelete) {
            Text("✕", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Opens [file] via whatever app on the device can handle its MIME type. Silently
 * does nothing if no such app is installed, rather than crashing. */
private fun openStoredFile(context: Context, file: StoredFile) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.filePath))
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, file.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
