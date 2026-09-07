package com.mspg.poicat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDate
import com.mspg.poicat.brain.toLocalDateTime
import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.launch

/**
 * Calendar tab: a plain on-device month view over the same `cat_events` table
 * the AI chat reads/writes, so a schedule registered by chatting with the cat
 * shows up here automatically, and one added/edited here can be found again
 * by asking the cat. No network, no separate data store.
 */
@Composable
fun CalendarScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CatEventRepository(context.applicationContext) }

    var yearMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var eventsInMonth by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var eventsOnSelectedDay by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var editingEvent by remember { mutableStateOf<CatEvent?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(yearMonth, refreshTick) {
        val start = yearMonth.atDay(1).toEpochMilli()
        val end = yearMonth.atEndOfMonth().plusDays(1).toEpochMilli() - 1
        eventsInMonth = repository.between(start, end)
    }

    LaunchedEffect(selectedDate, refreshTick) {
        val start = selectedDate.toEpochMilli()
        val end = selectedDate.plusDays(1).toEpochMilli() - 1
        eventsOnSelectedDay = repository.onDay(start, end)
    }

    val datesWithEvents = remember(eventsInMonth) {
        eventsInMonth.mapNotNull { it.dateTime?.toLocalDate() }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text("カレンダー", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(16.dp))

        MonthHeader(
            yearMonth = yearMonth,
            onPrev = { yearMonth = yearMonth.minusMonths(1) },
            onNext = { yearMonth = yearMonth.plusMonths(1) },
        )

        Spacer(Modifier.height(8.dp))

        MonthGrid(
            yearMonth = yearMonth,
            selectedDate = selectedDate,
            datesWithEvents = datesWithEvents,
            onSelectDate = { selectedDate = it },
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日の予定",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { editingEvent = null; showDialog = true }) {
                Text("＋ 追加")
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (eventsOnSelectedDay.isEmpty()) {
                item {
                    Text(
                        text = "予定はまだ入ってないにゃ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(eventsOnSelectedDay, key = { it.id }) { event ->
                EventRow(
                    event = event,
                    onClick = { editingEvent = event; showDialog = true },
                    onDelete = {
                        scope.launch {
                            repository.delete(event)
                            refreshTick++
                        }
                    },
                )
            }
        }
    }

    if (showDialog) {
        val current = editingEvent
        EventEditDialog(
            initialTitle = current?.title ?: "",
            initialDate = current?.dateTime?.toLocalDate() ?: selectedDate,
            initialTime = current?.dateTime?.toLocalDateTime()?.toLocalTime() ?: LocalTime.of(9, 0),
            isEditing = current != null,
            onDismiss = { showDialog = false; editingEvent = null },
            onDelete = current?.let { event ->
                {
                    scope.launch {
                        repository.delete(event)
                        showDialog = false
                        editingEvent = null
                        refreshTick++
                    }
                }
            },
            onSave = { title, date, time ->
                scope.launch {
                    val dateTimeMillis = LocalDateTime.of(date, time).toEpochMilli()
                    if (current != null) {
                        repository.edit(current, title, dateTimeMillis)
                    } else {
                        repository.remember(title, dateTimeMillis)
                    }
                    showDialog = false
                    editingEvent = null
                    selectedDate = date
                    if (YearMonth.from(date) != yearMonth) yearMonth = YearMonth.from(date)
                    refreshTick++
                }
            },
        )
    }
}

@Composable
private fun MonthHeader(yearMonth: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) { Text("‹", fontSize = 22.sp) }
        Text("${yearMonth.year}年${yearMonth.monthValue}月", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = onNext) { Text("›", fontSize = 22.sp) }
    }
}

private val weekdayLabels = listOf("日", "月", "火", "水", "木", "金", "土")

@Composable
private fun MonthGrid(
    yearMonth: YearMonth,
    selectedDate: LocalDate,
    datesWithEvents: Set<LocalDate>,
    onSelectDate: (LocalDate) -> Unit,
) {
    val firstOfMonth = yearMonth.atDay(1)
    // DayOfWeek.SUNDAY.value == 7 in java.time; mod 7 turns that into column 0 for a Sun-start grid.
    val firstDayOffset = firstOfMonth.dayOfWeek.value % 7
    val daysInMonth = yearMonth.lengthOfMonth()
    val rows = (firstDayOffset + daysInMonth + 6) / 7

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        var dayCounter = 1
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val cellIndex = row * 7 + col
                    if (cellIndex < firstDayOffset || dayCounter > daysInMonth) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = yearMonth.atDay(dayCounter)
                        DayCell(
                            date = date,
                            isSelected = date == selectedDate,
                            isToday = date == LocalDate.now(),
                            hasEvent = date in datesWithEvents,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        )
                        dayCounter++
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                isToday -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    if (hasEvent) {
                        if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ),
        )
    }
}

@Composable
private fun EventRow(event: CatEvent, onClick: () -> Unit, onDelete: () -> Unit) {
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
            Text(event.title, fontWeight = FontWeight.Bold)
            event.dateTime?.let {
                val time = it.toLocalDateTime()
                Text(
                    text = "%02d:%02d".format(time.hour, time.minute),
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
private fun EventEditDialog(
    initialTitle: String,
    initialDate: LocalDate,
    initialTime: LocalTime,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (title: String, date: LocalDate, time: LocalTime) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle) }
    var date by remember { mutableStateOf(initialDate) }
    var time by remember { mutableStateOf(initialTime) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "予定を編集" else "予定を追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("予定の内容") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("日付: ${date.monthValue}月${date.dayOfMonth}日")
                }
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("時刻: %02d:%02d".format(time.hour, time.minute))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onSave(title.trim(), date, time) },
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
