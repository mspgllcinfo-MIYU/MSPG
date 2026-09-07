package com.mspg.poicat

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.brain.toLocalDateTime
import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import kotlinx.coroutines.launch

/**
 * Memo tab: lists the date-less items in the shared `cat_events` table (a
 * memo registered by chatting with the cat has a null dateTime, same table
 * the calendar reads for dated events). Add/edit/delete here is visible to
 * the cat AI's keyword search immediately since it's the same data.
 */
@Composable
fun MemoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CatEventRepository(context.applicationContext) }

    var memos by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var editingMemo by remember { mutableStateOf<CatEvent?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        memos = repository.memos()
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
            Text("メモ", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { editingMemo = null; showDialog = true }) {
                Text("＋ 追加")
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (memos.isEmpty()) {
                item {
                    Text(
                        text = "メモはまだ入ってないにゃ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(memos, key = { it.id }) { memo ->
                MemoRow(
                    memo = memo,
                    onClick = { editingMemo = memo; showDialog = true },
                    onDelete = {
                        scope.launch {
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
            initialText = current?.title ?: "",
            isEditing = current != null,
            onDismiss = { showDialog = false; editingMemo = null },
            onDelete = current?.let { memo ->
                {
                    scope.launch {
                        repository.delete(memo)
                        showDialog = false
                        editingMemo = null
                        refreshTick++
                    }
                }
            },
            onSave = { text ->
                scope.launch {
                    if (current != null) {
                        repository.edit(current, text, null)
                    } else {
                        repository.remember(text, null)
                    }
                    showDialog = false
                    editingMemo = null
                    refreshTick++
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(memo.title)
            val created = memo.createdAt.toLocalDateTime()
            Text(
                text = "${created.monthValue}月${created.dayOfMonth}日に記録",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Text("✕", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun MemoEditDialog(
    initialText: String,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "メモを編集" else "メモを追加") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("内容") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onSave(text.trim()) },
                enabled = text.isNotBlank(),
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
