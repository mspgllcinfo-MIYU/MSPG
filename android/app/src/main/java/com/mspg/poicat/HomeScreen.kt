package com.mspg.poicat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * Home tab: a read-only, at-a-glance summary of today's schedule, the next
 * upcoming one, and unfinished tasks — all read from the same shared table
 * the other tabs and the cat AI use. Deliberately light on features: no
 * editing here, just enough to see what matters without opening another tab.
 */
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val repository = remember { CatEventRepository(context.applicationContext) }

    var todayEvents by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var nextEvent by remember { mutableStateOf<CatEvent?>(null) }
    var openTasks by remember { mutableStateOf<List<CatEvent>>(emptyList()) }

    LaunchedEffect(Unit) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        todayEvents = repository.onDay(today.toEpochMilli(), today.plusDays(1).toEpochMilli() - 1)
        nextEvent = repository.upcoming(now.toEpochMilli()).firstOrNull()
        openTasks = repository.incompleteTasks()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("ホーム", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        HomeSection(title = "今日の予定") {
            if (todayEvents.isEmpty()) {
                Text("今日の予定はまだ入ってないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                todayEvents.forEach { event ->
                    val time = event.dateTime!!.toLocalDateTime()
                    Text("%02d:%02d  %s".format(time.hour, time.minute, event.title))
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        HomeSection(title = "次の予定") {
            val next = nextEvent
            if (next == null) {
                Text("予定はまだ入ってないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${DateTimeParser.formatWhen(next.dateTime!!.toLocalDate(), LocalDate.now())}  ${next.title}")
            }
        }

        Spacer(Modifier.height(24.dp))

        HomeSection(title = "未完了タスク") {
            if (openTasks.isEmpty()) {
                Text("タスクはまだ入ってないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                openTasks.forEach { task ->
                    Text("・${task.title}")
                }
            }
        }
    }
}

@Composable
private fun HomeSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}
