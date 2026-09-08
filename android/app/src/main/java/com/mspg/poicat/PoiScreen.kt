package com.mspg.poicat

import android.app.DatePickerDialog
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDate
import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * Poi tab: a plain to-do list, stored as `isTask = true` rows in the same
 * shared `cat_events` table. The cat AI answers "今日やることは？" /
 * "残ってるタスクは？" from the same not-yet-done rows this screen manages.
 */
@Composable
fun PoiScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CatEventRepository(context.applicationContext) }
    val photoRepository = remember { PhotoRepository(context.applicationContext) }

    var tasks by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var editingTask by remember { mutableStateOf<CatEvent?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var linkedPhotos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var detailPhoto by remember { mutableStateOf<Photo?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        tasks = repository.tasks()
    }

    // Photos a chat-attached photo got linked to this task with (Phase C) — same
    // photo<->event link MemoScreen already reads for a memo's photo strip.
    LaunchedEffect(editingTask, refreshTick) {
        linkedPhotos = editingTask?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ポイ", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { editingTask = null; showDialog = true }) {
                Text("＋ 追加")
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tasks.isEmpty()) {
                item {
                    Text(
                        text = "タスクはまだ入ってないにゃ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    onToggleCompleted = {
                        scope.launch {
                            repository.setTaskCompleted(task, !task.completed)
                            refreshTick++
                        }
                    },
                    onClick = { editingTask = task; showDialog = true },
                    onDelete = {
                        scope.launch {
                            // Drop the photo links before the task itself is gone, so no
                            // link is left pointing at a now-nonexistent task id. The
                            // photos/album are never touched by this.
                            photoRepository.unlinkAllForMemo(task.id)
                            repository.delete(task)
                            refreshTick++
                        }
                    },
                )
            }
        }
    }

    if (showDialog) {
        val current = editingTask
        TaskEditDialog(
            initialTitle = current?.title ?: "",
            initialDueDate = current?.dateTime?.toLocalDate(),
            isEditing = current != null,
            linkedPhotos = linkedPhotos,
            // Closes this AlertDialog before opening the detail Dialog rather than
            // stacking a second dialog window on top of it — same approach MemoScreen
            // already uses for its own photo strip.
            onPhotoClick = { showDialog = false; detailPhoto = it },
            onDismiss = { showDialog = false; editingTask = null },
            onDelete = current?.let { task ->
                {
                    scope.launch {
                        photoRepository.unlinkAllForMemo(task.id)
                        repository.delete(task)
                        showDialog = false
                        editingTask = null
                        refreshTick++
                    }
                }
            },
            onSave = { title, dueDate ->
                scope.launch {
                    val dueMillis = dueDate?.toEpochMilli()
                    if (current != null) {
                        repository.edit(current, title, dueMillis)
                    } else {
                        repository.addTask(title, dueMillis)
                    }
                    showDialog = false
                    editingTask = null
                    refreshTick++
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
                    linkedPhotos = editingTask?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    detailPhoto = null
                    showDialog = true
                }
            },
            onDelete = {
                scope.launch {
                    photoRepository.delete(photo)
                    linkedPhotos = editingTask?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    detailPhoto = null
                    showDialog = true
                }
            },
        )
    }
}

@Composable
private fun TaskRow(
    task: CatEvent,
    onToggleCompleted: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.completed, onCheckedChange = { onToggleCompleted() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                color = if (task.completed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            task.dateTime?.let {
                val due = it.toLocalDate()
                Text(
                    text = "期限: ${due.monthValue}月${due.dayOfMonth}日",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Text("✕", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TaskEditDialog(
    initialTitle: String,
    initialDueDate: LocalDate?,
    isEditing: Boolean,
    linkedPhotos: List<Photo>,
    onPhotoClick: (Photo) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (title: String, dueDate: LocalDate?) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle) }
    var dueDate by remember { mutableStateOf(initialDueDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "タスクを編集" else "タスクを追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("やること") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val base = dueDate ?: LocalDate.now()
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth -> dueDate = LocalDate.of(year, month + 1, dayOfMonth) },
                            base.year,
                            base.monthValue - 1,
                            base.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(dueDate?.let { "期限: ${it.monthValue}月${it.dayOfMonth}日" } ?: "期限を設定（任意）")
                }
                if (dueDate != null) {
                    TextButton(onClick = { dueDate = null }) {
                        Text("期限をなしにする")
                    }
                }

                // Read-only: linked here by the cat AI (Phase C) when a photo was sent
                // together with a Poi-registering caption — no add/unlink UI in this
                // screen, just viewing what's already linked.
                if (isEditing && linkedPhotos.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("写真", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        linkedPhotos.forEach { photo ->
                            Box(modifier = Modifier.size(64.dp)) {
                                PhotoThumbnail(photo = photo, onClick = { onPhotoClick(photo) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onSave(title.trim(), dueDate) },
                enabled = title.isNotBlank(),
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
