package com.mspg.poicat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.brain.DateTimeParser
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDate
import com.mspg.poicat.brain.toLocalDateTime
import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Home tab: the cat is front and center (see [AnimatedCat]), with a short
 * にゃ-speech line plus an at-a-glance summary of today's schedule, the next
 * upcoming one, unfinished Poi tasks, and the latest memo — all read from
 * the same shared table the other tabs and the cat AI use. Tapping the cat
 * or a section jumps to the matching tab.
 */
@Composable
fun HomeScreen(onNavigate: (AppTab) -> Unit) {
    val context = LocalContext.current
    val repository = remember { CatEventRepository(context.applicationContext) }

    var todayEvents by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var nextEvent by remember { mutableStateOf<CatEvent?>(null) }
    var openTasks by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var recentMemo by remember { mutableStateOf<CatEvent?>(null) }

    LaunchedEffect(Unit) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        todayEvents = repository.onDay(today.toEpochMilli(), today.plusDays(1).toEpochMilli() - 1)
        nextEvent = repository.upcoming(now.toEpochMilli()).firstOrNull()
        openTasks = repository.incompleteTasks()
        recentMemo = repository.memos().firstOrNull()
    }

    val speech = remember(todayEvents, nextEvent, openTasks, recentMemo) {
        catSpeech(todayEvents, nextEvent, openTasks, recentMemo, LocalDateTime.now())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("ホーム", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SpeechBubble(speech)
            Spacer(Modifier.height(4.dp))
            AnimatedCat(onTap = { onNavigate(AppTab.AI) })
        }

        Spacer(Modifier.height(12.dp))

        HomeSection(title = "今日の予定", onClick = { onNavigate(AppTab.CAL) }) {
            if (todayEvents.isEmpty()) {
                Text("今日の予定はまだ入ってないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                todayEvents.forEach { event ->
                    val time = event.dateTime!!.toLocalDateTime()
                    Text("%02d:%02d  %s".format(time.hour, time.minute, event.title))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        HomeSection(title = "次の予定", onClick = { onNavigate(AppTab.CAL) }) {
            val next = nextEvent
            if (next == null) {
                Text("予定はまだ入ってないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${DateTimeParser.formatWhen(next.dateTime!!.toLocalDate(), LocalDate.now())}  ${next.title}")
            }
        }

        Spacer(Modifier.height(12.dp))

        HomeSection(title = "未完了のポイ", onClick = { onNavigate(AppTab.POI) }) {
            if (openTasks.isEmpty()) {
                Text("タスクはまだ入ってないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                openTasks.forEach { task ->
                    Text("・${task.title}")
                }
            }
        }

        recentMemo?.let { memo ->
            Spacer(Modifier.height(12.dp))
            HomeSection(title = "最近のメモ", onClick = { onNavigate(AppTab.MEMO) }) {
                Text(memo.title)
            }
        }
    }
}

/** Picks one short にゃ-line to summarize what's most worth mentioning right now. */
private fun catSpeech(
    todayEvents: List<CatEvent>,
    nextEvent: CatEvent?,
    openTasks: List<CatEvent>,
    recentMemo: CatEvent?,
    now: LocalDateTime,
): String {
    val today = now.toLocalDate()
    return when {
        todayEvents.isNotEmpty() -> "今日は${todayEvents.first().title}だにゃ"
        openTasks.isNotEmpty() -> "ポイが${openTasks.size}個残ってるにゃ"
        nextEvent != null ->
            "${DateTimeParser.formatWhen(nextEvent.dateTime!!.toLocalDate(), today)}は${nextEvent.title}だにゃ"
        recentMemo != null -> "${recentMemo.title}のことメモしてるにゃ"
        now.hour in 5..10 -> "おはようにゃ"
        else -> "今日は予定ないにゃ"
    }
}

@Composable
private fun SpeechBubble(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun HomeSection(title: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}
