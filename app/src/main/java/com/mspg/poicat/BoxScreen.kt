package com.mspg.poicat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BoxFilter(val label: String) {
    ALL("全部"),
    PHOTO("写真"),
    MEMO("メモ"),
    LINK("リンク"),
    FILE("ファイル"),
}

private fun PoiDraft.matchesFilter(filter: BoxFilter): Boolean = when (filter) {
    BoxFilter.ALL -> true
    BoxFilter.PHOTO -> this is PoiDraft.Photo
    BoxFilter.MEMO -> this is PoiDraft.MemoText
    BoxFilter.LINK -> this is PoiDraft.Link
    BoxFilter.FILE -> this is PoiDraft.FileDoc
}

private fun PoiDraft.categoryIcon(): ImageVector = when (this) {
    is PoiDraft.Photo -> Icons.Default.Photo
    is PoiDraft.MemoText -> Icons.Default.Notes
    is PoiDraft.Link -> Icons.Default.Link
    is PoiDraft.FileDoc -> Icons.Default.InsertDriveFile
}

private fun PoiDraft.previewText(): String = when (this) {
    is PoiDraft.Photo -> "写真"
    is PoiDraft.MemoText -> text
    is PoiDraft.Link -> url
    is PoiDraft.FileDoc -> name
}

@Composable
fun BoxScreen(items: List<PoiItem>, onBack: () -> Unit, onNotReady: (String) -> Unit) {
    var filter by remember { mutableStateOf(BoxFilter.ALL) }
    var expandedItemId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
            }
            Text(text = "届いたものBOX", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BoxFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) },
                )
            }
        }

        val filtered = items.filter { it.draft.matchesFilter(filter) }

        if (filtered.isEmpty()) {
            Text(
                text = "まだ何もありません",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(filtered, key = { it.id }) { item ->
                    BoxItemCard(
                        item = item,
                        isMenuExpanded = expandedItemId == item.id,
                        onOpenMenu = { expandedItemId = item.id },
                        onDismissMenu = { expandedItemId = null },
                        onOpen = { PoiRepository.markRead(item.id) },
                        onMoveToMemo = { onNotReady("プチメモへの移動") },
                        onMoveToCalendar = { onNotReady("カレンダーへの登録") },
                        onDelete = { PoiRepository.delete(item.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxItemCard(
    item: PoiItem,
    isMenuExpanded: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpen: () -> Unit,
    onMoveToMemo: () -> Unit,
    onMoveToCalendar: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeText = remember(item.timestamp) {
        SimpleDateFormat("M月d日 HH:mm", Locale.JAPANESE).format(Date(item.timestamp))
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFEDE6E0))
                .combinedClickable(onClick = onOpen, onLongClick = onOpenMenu)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val photoDraft = item.draft as? PoiDraft.Photo
            if (photoDraft != null) {
                val preview = rememberPhotoPreview(photoDraft.uri)
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFDCD2C8)),
                    )
                }
            } else {
                Icon(item.draft.categoryIcon(), contentDescription = null)
            }

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            ) {
                Text(text = item.senderLabel, fontSize = 12.sp, color = Color.Gray)
                Text(text = item.draft.previewText(), fontSize = 16.sp, maxLines = 1)
                Text(text = timeText, fontSize = 12.sp, color = Color.Gray)
            }

            if (item.isRead) {
                Icon(Icons.Default.Check, contentDescription = "既読", tint = MaterialTheme.colorScheme.primary)
            }
        }

        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(text = { Text("プチメモへ") }, onClick = { onDismissMenu(); onMoveToMemo() })
            DropdownMenuItem(text = { Text("カレンダーへ") }, onClick = { onDismissMenu(); onMoveToCalendar() })
            DropdownMenuItem(text = { Text("削除") }, onClick = { onDismissMenu(); onDelete() })
        }
    }
}
