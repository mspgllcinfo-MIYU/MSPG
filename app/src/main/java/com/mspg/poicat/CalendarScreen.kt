package com.mspg.poicat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarScreen() {
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val date = selectedDate

    if (date != null) {
        DayDetailScreen(date = date, onBack = { selectedDate = null })
    } else {
        MonthGridScreen(
            displayedMonth = displayedMonth,
            onPrevMonth = { displayedMonth = displayedMonth.minusMonths(1) },
            onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
            onSelectDate = { selectedDate = it },
        )
    }
}

@Composable
private fun MonthGridScreen(
    displayedMonth: YearMonth,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text(text = "カレンダー", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "前の月")
            }
            Text(text = "${displayedMonth.year}年${displayedMonth.monthValue}月", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onNextMonth) {
                Icon(Icons.Default.ChevronRight, contentDescription = "次の月")
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("日", "月", "火", "水", "木", "金", "土").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
        }

        val today = LocalDate.now()
        val firstDay = displayedMonth.atDay(1)
        val leadingBlanks = firstDay.dayOfWeek.value % 7

        val cells = mutableListOf<LocalDate?>()
        repeat(leadingBlanks) { cells.add(null) }
        for (day in 1..displayedMonth.lengthOfMonth()) cells.add(displayedMonth.atDay(day))
        while (cells.size % 7 != 0) cells.add(null)

        Column(modifier = Modifier.padding(top = 4.dp)) {
            cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { cellDate ->
                        DayCell(
                            date = cellDate,
                            isToday = cellDate == today,
                            hasEntries = cellDate != null &&
                                (CalendarRepository.schedulesOn(cellDate).isNotEmpty() || MemoRepository.memosOn(cellDate).isNotEmpty()),
                            onClick = { cellDate?.let(onSelectDate) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.DayCell(
    date: LocalDate?,
    isToday: Boolean,
    hasEntries: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isToday) Color(0xFFD98A9C) else Color(0xFFEDE6E0))
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = date.dayOfMonth.toString(), fontSize = 14.sp)
                if (hasEntries) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF8A6D5C)),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayDetailScreen(date: LocalDate, onBack: () -> Unit) {
    var showAddSchedule by remember { mutableStateOf(false) }
    var showAddMemo by remember { mutableStateOf(false) }

    val schedules = CalendarRepository.schedulesOn(date)
    val memos = MemoRepository.memosOn(date)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
            }
            Text(text = "${date.monthValue}月${date.dayOfMonth}日", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item { Text(text = "予定", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            if (schedules.isEmpty()) {
                item { Text(text = "予定はまだありません", fontSize = 14.sp, color = Color.Gray) }
            } else {
                items(schedules, key = { it.id }) { ScheduleRow(it) }
            }
            item { TextButton(onClick = { showAddSchedule = true }) { Text("＋ 予定を追加") } }

            item {
                Text(
                    text = "プチメモ",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (memos.isEmpty()) {
                item { Text(text = "プチメモはまだありません", fontSize = 14.sp, color = Color.Gray) }
            } else {
                items(memos, key = { it.id }) { MemoRow(it) }
            }
            item { TextButton(onClick = { showAddMemo = true }) { Text("＋ プチメモを追加") } }
        }
    }

    if (showAddSchedule) {
        AddScheduleDialog(date = date, onDismiss = { showAddSchedule = false })
    }
    if (showAddMemo) {
        AddMemoDialog(date = date, onDismiss = { showAddMemo = false })
    }
}

@Composable
private fun ScheduleRow(schedule: ScheduleEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFEDE6E0))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = schedule.title, fontSize = 15.sp)
            if (schedule.time != null) {
                Text(text = schedule.time, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun MemoRow(memo: PetitMemo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFEDE6E0))
            .padding(12.dp),
    ) {
        if (memo.text.isNotBlank()) {
            Text(text = memo.text, fontSize = 15.sp)
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

@Composable
private fun AddScheduleDialog(date: LocalDate, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("予定を追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("時刻（任意・例:18:30）") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        CalendarRepository.addSchedule(date, time, title)
                        onDismiss()
                    }
                },
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
fun AddMemoDialog(date: LocalDate, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var photoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> photoUris = uris }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プチメモを追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("内容（任意）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) {
                    Text(if (photoUris.isEmpty()) "写真を選ぶ（複数可）" else "写真：${photoUris.size}枚選択中")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank() || photoUris.isNotEmpty()) {
                        MemoRepository.addMemo(date, text, photoUris)
                        onDismiss()
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
