package com.mspg.poicat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.launch

// Step4-2: Calendar-only design tokens, matching Home (Step1) / bottom nav
// (Step2) / AI chat (Step3) / Poi (Step4-1) by value ("大人かわいい×ちょっと
// 高級×無愛想な黒猫"). Scoped to this file deliberately — Theme.kt stays
// untouched until this look is promoted (Step0).
private val CalendarInk = Color(0xFF201E1D) // 墨色
private val CalendarCream = Color(0xFFF7F3EF) // 生成り — matches Theme.kt's page background
private val CalendarCard = Color(0xFFEFE7DE) // a shade deeper than the page, for event rows
private val CalendarGold = Color(0xFFC9A66B) // restrained accent, never a fill color
private val CalendarPink = Color(0xFFD98A9C) // the app's existing pink, kept rare

/**
 * Calendar tab: a plain on-device month view over the same `cat_events` table
 * the AI chat reads/writes, so a schedule registered by chatting with the cat
 * shows up here automatically, and one added/edited here can be found again
 * by asking the cat. No network, no separate data store.
 */
@Composable
fun CalendarScreen(
    yearMonth: YearMonth,
    onYearMonthChange: (YearMonth) -> Unit,
    selectedDate: LocalDate,
    onSelectedDateChange: (LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CatEventRepository(context.applicationContext) }
    val photoRepository = remember { PhotoRepository(context.applicationContext) }

    var eventsInMonth by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var eventsOnSelectedDay by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var photosOnSelectedDay by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var detailPhoto by remember { mutableStateOf<Photo?>(null) }
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
        photosOnSelectedDay = photoRepository.byLinkedDate(start, end)
    }

    val datesWithEvents = remember(eventsInMonth) {
        eventsInMonth.mapNotNull { it.dateTime?.toLocalDate() }.toSet()
    }

    // A plain Column here would overflow off the bottom of the screen on months
    // with 6 calendar rows, hiding the "＋ 追加" button and event list entirely
    // with no way to scroll to them. Making the whole screen one LazyColumn
    // means it always scrolls to fit, whatever the month grid's height.
    //
    // Step4-2: wrapped in a Box so the whole calendar (grid, event list, photos)
    // can be capped at 640dp and centered on a Fold's unfolded, much wider
    // screen; a normal phone stays fillMaxWidth as before. The item/items
    // structure and all state below are unchanged, just re-nested one level.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(20.dp),
        ) {
            item {
                Text("カレンダー", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CalendarInk)
                Spacer(Modifier.height(16.dp))
                MonthHeader(
                    yearMonth = yearMonth,
                    onPrev = { onYearMonthChange(yearMonth.minusMonths(1)) },
                    onNext = { onYearMonthChange(yearMonth.plusMonths(1)) },
                )
                Spacer(Modifier.height(8.dp))
                MonthGrid(
                    yearMonth = yearMonth,
                    selectedDate = selectedDate,
                    datesWithEvents = datesWithEvents,
                    onSelectDate = { onSelectedDateChange(it) },
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
                        color = CalendarInk,
                        modifier = Modifier.weight(1f),
                    )
                    // Step4-2: same soft pink pill as PoiScreen's "＋ 追加" — quieter
                    // than a solid fill, function/onClick unchanged.
                    Button(
                        onClick = { editingEvent = null; showDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = CalendarPink.copy(alpha = 0.25f), contentColor = CalendarInk),
                        shape = RoundedCornerShape(percent = 50),
                    ) {
                        Text("＋ 追加")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (eventsOnSelectedDay.isEmpty()) {
                item {
                    Text(
                        text = "予定はまだ入ってないにゃ",
                        color = CalendarInk.copy(alpha = 0.5f),
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
                Spacer(Modifier.height(8.dp))
            }

            if (photosOnSelectedDay.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "📷 写真${photosOnSelectedDay.size}枚",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = CalendarInk,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        photosOnSelectedDay.forEach { photo ->
                            PhotoThumbnail(
                                photo = photo,
                                onClick = { detailPhoto = photo },
                                modifier = Modifier.size(80.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    detailPhoto?.let { photo ->
        PhotoDetailDialog(
            photo = photo,
            onDismiss = { detailPhoto = null },
            onSave = { caption, album, linkedDate ->
                scope.launch {
                    photoRepository.updateDetails(photo, caption, album, linkedDate?.toEpochMilli())
                    detailPhoto = null
                    refreshTick++
                }
            },
            onDelete = {
                scope.launch {
                    photoRepository.delete(photo)
                    detailPhoto = null
                    refreshTick++
                }
            },
        )
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
                    onSelectedDateChange(date)
                    if (YearMonth.from(date) != yearMonth) onYearMonthChange(YearMonth.from(date))
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
        IconButton(onClick = onPrev) { Text("‹", fontSize = 22.sp, color = CalendarInk.copy(alpha = 0.6f)) }
        Text("${yearMonth.year}年${yearMonth.monthValue}月", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CalendarInk)
        IconButton(onClick = onNext) { Text("›", fontSize = 22.sp, color = CalendarInk.copy(alpha = 0.6f)) }
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
                    color = CalendarInk.copy(alpha = 0.5f),
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
    // Step4-2: fill (selected) and ring (today) are independent layers, so a
    // day that is both keeps both markers instead of one overwriting the
    // other — normal/today/selected/hasEvent all stay legible in combination.
    val ringModifier = if (isToday) {
        Modifier.border(1.dp, CalendarGold.copy(alpha = 0.6f), CircleShape)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(if (isSelected) CalendarPink.copy(alpha = 0.3f) else Color.Transparent)
            .then(ringModifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            color = CalendarInk,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    if (hasEvent) {
                        if (isSelected) CalendarInk.copy(alpha = 0.5f) else CalendarGold
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
            .clip(RoundedCornerShape(16.dp))
            .background(CalendarCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(event.title, fontWeight = FontWeight.Bold, color = CalendarInk)
            event.dateTime?.let {
                val time = it.toLocalDateTime()
                Text(
                    text = "%02d:%02d".format(time.hour, time.minute),
                    fontSize = 12.sp,
                    color = CalendarGold,
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
        containerColor = CalendarCard,
        titleContentColor = CalendarInk,
        textContentColor = CalendarInk,
        title = { Text(if (isEditing) "予定を編集" else "予定を追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("予定の内容") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = CalendarInk, unfocusedTextColor = CalendarInk),
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
                colors = ButtonDefaults.buttonColors(containerColor = CalendarPink, contentColor = Color.White),
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
