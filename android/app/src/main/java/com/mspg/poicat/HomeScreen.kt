package com.mspg.poicat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// Step1: home-only design tokens ("大人かわいい×ちょっと高級×無愛想な黒猫").
// Scoped to this file deliberately — Theme.kt stays untouched until this look
// is confirmed on-device and promoted to the shared theme (Step0).
private val HomeInk = Color(0xFF201E1D) // 墨色 — the hero panel and dark text
private val HomeCream = Color(0xFFF7F3EF) // matches the existing app background
private val HomeCard = Color(0xFFEFE7DE) // a shade deeper than the page background, for card layering
private val HomeGold = Color(0xFFC9A66B) // a restrained accent, never a fill color
private val HomePink = Color(0xFFD98A9C) // the app's existing pink — kept rare on purpose

/**
 * Home tab: the cat is front and center (see [AnimatedCat]), with a short
 * にゃ-speech line plus an at-a-glance summary of today's schedule, the next
 * upcoming one, unfinished Poi tasks, and the latest memo — all read from
 * the same shared table the other tabs and the cat AI use. Tapping the cat
 * or a section jumps to the matching tab.
 */
@Composable
fun HomeScreen(onNavigate: (AppTab) -> Unit, onOpenAlbum: () -> Unit) {
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
        CatHero(speech = speech, onTapCat = { onNavigate(AppTab.AI) })

        Spacer(Modifier.height(20.dp))

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

        Spacer(Modifier.height(12.dp))

        HomeSection(title = "アルバム", onClick = onOpenAlbum) {
            Text("写真を見る・追加する", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(20.dp))

        // Brand signature, deliberately quiet — this is a signature, not a heading.
        Text(
            text = "MIYU × AI",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = HomeInk.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
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
        // Truly nothing to report: this is the cat's default "waiting for you to
        // throw something at it" state, not a status report — kept short and blunt
        // rather than a cheerful "予定はありません" line.
        else -> "何にゃ"
    }
}

/**
 * The hero area: a dark ("墨色") panel the cat waits in, sized so it stays a
 * strong presence on a phone and grows noticeably larger on an unfolded Fold
 * screen without ever taking over the whole page. [AnimatedCat] itself is
 * untouched — its fixed 190dp artwork is scaled up visually via graphicsLayer
 * from inside a responsively-sized Box, rather than resized internally.
 */
@Composable
private fun CatHero(speech: String, onTapCat: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(HomeInk)
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HeroSpeechBubble(speech)
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // A phone stays close to the original 190dp presence; an unfolded
                // Fold's much wider screen scales the cat up substantially, capped
                // so it never turns into an oversized mascot.
                val catAreaSize = (maxWidth * 0.55f).coerceIn(220.dp, 320.dp)
                val catBaseSize = 190.dp
                val catScale = catAreaSize / catBaseSize

                Box(contentAlignment = Alignment.Center) {
                    // A very soft glow — the app's existing pink, kept as a mood
                    // accent rather than a color used to look "more cute".
                    Box(
                        modifier = Modifier
                            .size(catAreaSize * 0.85f)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(HomePink.copy(alpha = 0.16f)),
                    )
                    Box(modifier = Modifier.size(catAreaSize), contentAlignment = Alignment.Center) {
                        AnimatedCat(
                            onTap = onTapCat,
                            modifier = Modifier.graphicsLayer {
                                scaleX = catScale
                                scaleY = catScale
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroSpeechBubble(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(HomeCream)
            .border(1.dp, HomeGold.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        color = HomeInk,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun HomeSection(title: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(HomeCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = HomeInk)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}
