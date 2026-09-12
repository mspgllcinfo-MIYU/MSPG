package com.mspg.poicat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDateTime
import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import kotlinx.coroutines.launch

// Step4-3: Memo-only design tokens, matching Home (Step1) / bottom nav
// (Step2) / AI chat (Step3) / Poi (Step4-1) / Calendar (Step4-2) by value
// ("大人かわいい×ちょっと高級×無愛想な黒猫"). Scoped to this file
// deliberately — Theme.kt stays untouched until this look is promoted (Step0).
private val MemoInk = Color(0xFF201E1D) // 墨色
private val MemoCream = Color(0xFFF7F3EF) // 生成り — matches Theme.kt's page background
private val MemoCard = Color(0xFFEFE7DE) // a shade deeper than the page, for memo rows
private val MemoGold = Color(0xFFC9A66B) // restrained accent, never a fill color
private val MemoPink = Color(0xFFD98A9C) // the app's existing pink, kept rare

/**
 * Memo tab: lists the date-less items in the shared `cat_events` table (a
 * memo registered by chatting with the cat has a null dateTime, same table
 * the calendar reads for dated events). Add/edit/delete here is visible to
 * the cat AI's keyword search immediately since it's the same data.
 */
@Composable
fun MemoScreen(embedded: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CatEventRepository(context.applicationContext) }
    val photoRepository = remember { PhotoRepository(context.applicationContext) }

    var memos by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var editingMemo by remember { mutableStateOf<CatEvent?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    // Hoisted out of MemoEditDialog (rather than a plain `remember` inside it) so an
    // in-progress, unsaved edit survives the dialog being closed and reopened while
    // picking/viewing a photo — it's only reset when a *different* memo starts editing.
    var editText by remember { mutableStateOf("") }
    var linkedPhotos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var detailPhoto by remember { mutableStateOf<Photo?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        memos = repository.memos()
    }

    LaunchedEffect(editingMemo, refreshTick) {
        linkedPhotos = editingMemo?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
    }

    LaunchedEffect(editingMemo) {
        editText = editingMemo?.title ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Design pass: embedded (inside Poi's own メモ tab) skips this screen's own
            // outer padding entirely — Poi's own padding(20.dp) around the whole tab
            // area already provides it, so adding it again here would double the margin.
            .padding(if (embedded) 0.dp else 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Design pass: embedded drops the redundant "メモ" heading (Poi's own "ポイ"
            // heading and selected タブ already say where you are) but keeps the ＋追加
            // button right-aligned via the same weight(1f) spacer.
            if (embedded) {
                Spacer(Modifier.weight(1f))
            } else {
                Text("メモ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MemoInk, modifier = Modifier.weight(1f))
            }
            // Step4-3: same pink-pill family as Poi/Calendar's "＋ 追加".
            Button(
                onClick = { editingMemo = null; editText = ""; showDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MemoPink.copy(alpha = 0.25f), contentColor = MemoInk),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Text("＋ 追加")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Step4-3: capped at 640dp and centered so the list doesn't stretch
        // edge-to-edge on a Fold's unfolded, much wider screen; a normal
        // phone stays fillMaxWidth as before.
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (memos.isEmpty()) {
                item {
                    Text(
                        text = "メモはまだ入ってないにゃ",
                        color = MemoInk.copy(alpha = 0.5f),
                    )
                }
            }
            items(memos, key = { it.id }) { memo ->
                MemoRow(
                    memo = memo,
                    onClick = { editingMemo = memo; showDialog = true },
                    onDelete = {
                        scope.launch {
                            // Drop the photo links before the memo itself is gone, so no
                            // link is left pointing at a now-nonexistent memo id. The
                            // photos/album are never touched by this.
                            photoRepository.unlinkAllForMemo(memo.id)
                            repository.delete(memo)
                            refreshTick++
                        }
                    },
                )
            }
        }
    }

    if (showDialog) {
        val current = editingMemo
        MemoEditDialog(
            text = editText,
            onTextChange = { editText = it },
            isEditing = current != null,
            linkedPhotos = linkedPhotos,
            // Closes this AlertDialog before opening the picker/detail Dialog rather than
            // stacking a second dialog window on top of it — simpler and more predictable.
            onPickPhoto = { showDialog = false; showPhotoPicker = true },
            onUnlinkPhoto = { photo ->
                scope.launch {
                    current?.let { photoRepository.unlinkFromMemo(photo, it.id) }
                    linkedPhotos = current?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                }
            },
            onPhotoClick = { showDialog = false; detailPhoto = it },
            onDismiss = { showDialog = false; editingMemo = null },
            onDelete = current?.let { memo ->
                {
                    scope.launch {
                        photoRepository.unlinkAllForMemo(memo.id)
                        repository.delete(memo)
                        showDialog = false
                        editingMemo = null
                        refreshTick++
                    }
                }
            },
            onSave = {
                scope.launch {
                    val trimmed = editText.trim()
                    if (current != null) {
                        repository.edit(current, trimmed, null)
                    } else {
                        repository.remember(trimmed, null)
                    }
                    showDialog = false
                    editingMemo = null
                    refreshTick++
                }
            },
        )
    }

    if (showPhotoPicker) {
        AlbumPhotoPickerDialog(
            onDismiss = { showPhotoPicker = false; showDialog = true },
            onPick = { photo ->
                scope.launch {
                    editingMemo?.let { photoRepository.linkToMemo(photo, it.id) }
                    linkedPhotos = editingMemo?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    showPhotoPicker = false
                    showDialog = true
                }
            },
        )
    }

    detailPhoto?.let { photo ->
        PhotoDetailDialog(
            photo = photo,
            onDismiss = { detailPhoto = null; showDialog = true },
            onSave = { caption, album, linkedDate ->
                scope.launch {
                    photoRepository.updateDetails(photo, caption, album, linkedDate?.toEpochMilli())
                    linkedPhotos = editingMemo?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    detailPhoto = null
                    showDialog = true
                }
            },
            onDelete = {
                scope.launch {
                    photoRepository.softDelete(photo)
                    linkedPhotos = editingMemo?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    detailPhoto = null
                    showDialog = true
                }
            },
        )
    }
}

@Composable
private fun MemoRow(memo: CatEvent, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MemoCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Step4-3: normal weight, not bold — a memo is quieter than a
            // Poi task or Calendar event, and stays that way here.
            Text(memo.title, color = MemoInk)
            val created = memo.createdAt.toLocalDateTime()
            Text(
                text = "${created.monthValue}月${created.dayOfMonth}日に記録",
                fontSize = 12.sp,
                color = MemoGold,
            )
        }
        IconButton(onClick = onDelete) {
            Text("✕", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MemoEditDialog(
    text: String,
    onTextChange: (String) -> Unit,
    isEditing: Boolean,
    linkedPhotos: List<Photo>,
    onPickPhoto: () -> Unit,
    onUnlinkPhoto: (Photo) -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MemoCard,
        titleContentColor = MemoInk,
        textContentColor = MemoInk,
        title = { Text(if (isEditing) "メモを編集" else "メモを追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MemoInk, unfocusedTextColor = MemoInk),
                )

                // Photos can only be linked once the memo exists (needs an id), so this
                // section is hidden while adding a brand-new memo.
                if (isEditing) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("写真", fontWeight = FontWeight.Bold, color = MemoInk, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = onPickPhoto,
                            colors = ButtonDefaults.textButtonColors(contentColor = MemoPink),
                        ) { Text("＋ 写真を選ぶ") }
                    }
                    if (linkedPhotos.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            linkedPhotos.forEach { photo ->
                                Box(modifier = Modifier.size(64.dp)) {
                                    PhotoThumbnail(photo = photo, onClick = { onPhotoClick(photo) })
                                    IconButton(
                                        onClick = { onUnlinkPhoto(photo) },
                                        modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                                    ) {
                                        Text("✕", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onSave() },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MemoPink, contentColor = Color.White),
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}
