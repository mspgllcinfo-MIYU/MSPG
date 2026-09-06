package com.mspg.poicat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MemoScreen() {
    var showAddMemo by remember { mutableStateOf(false) }
    val memos = MemoRepository.memos.sortedByDescending { it.createdAt }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "プチメモ", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { showAddMemo = true }) {
                Icon(Icons.Default.Add, contentDescription = "プチメモを追加")
            }
        }

        if (memos.isEmpty()) {
            Text(
                text = "まだプチメモがありません",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(memos, key = { it.id }) { MemoListRow(it) }
            }
        }
    }

    if (showAddMemo) {
        AddMemoDialog(date = LocalDate.now(), onDismiss = { showAddMemo = false })
    }
}

@Composable
private fun MemoListRow(memo: PetitMemo) {
    val dateLabel = remember(memo.date) {
        memo.date.format(DateTimeFormatter.ofPattern("M月d日", Locale.JAPANESE))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFEDE6E0))
            .padding(12.dp),
    ) {
        Text(text = dateLabel, fontSize = 12.sp, color = Color.Gray)
        if (memo.text.isNotBlank()) {
            Text(text = memo.text, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (memo.photoUris.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                memo.photoUris.take(3).forEach { uri ->
                    val preview = rememberPhotoPreview(uri)
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
    }
}
