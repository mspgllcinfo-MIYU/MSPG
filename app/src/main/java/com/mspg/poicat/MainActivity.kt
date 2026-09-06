package com.mspg.poicat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private enum class AppTab(val label: String) {
    HOME("ホーム"),
    POI("ポイ"),
    CAL("カレンダー"),
    MEMO("メモ"),
    AI("猫AI"),
}

class MainActivity : ComponentActivity() {
    private val incomingSendIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PoiRepository.init(applicationContext)
        CalendarRepository.init(applicationContext)
        MemoRepository.init(applicationContext)
        ChatRepository.init(applicationContext)
        incomingSendIntent.value = intent.takeIf { it.action == Intent.ACTION_SEND }
        setContent {
            PoiCatTheme {
                AppRoot(incomingSendIntent = incomingSendIntent.value, onIncomingSendIntentConsumed = { incomingSendIntent.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            incomingSendIntent.value = intent
        }
    }
}

@Composable
private fun PoiCatTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.lightColorScheme(
        background = Color(0xFFF7F3EF),
        surface = Color(0xFFF7F3EF),
        primary = Color(0xFFD98A9C),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun AppRoot(incomingSendIntent: Intent?, onIncomingSendIntentConsumed: () -> Unit) {
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var sharedPoiDraft by remember { mutableStateOf<PoiDraft?>(null) }
    LaunchedEffect(incomingSendIntent) {
        val draft = extractSharedDraft(context, incomingSendIntent)
        if (draft != null) {
            sharedPoiDraft = draft
            selectedTab = AppTab.POI
        }
        if (incomingSendIntent != null) onIncomingSendIntentConsumed()
    }

    var isBoxOpen by remember { mutableStateOf(false) }
    var showQuickMemo by remember { mutableStateOf(false) }

    fun showNotReady(feature: String) {
        scope.launch { snackbarHostState.showSnackbar("$feature は準備中です") }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                val icons = mapOf(
                    AppTab.HOME to Icons.Default.Home,
                    AppTab.POI to Icons.Default.Send,
                    AppTab.CAL to Icons.Default.CalendarMonth,
                    AppTab.MEMO to Icons.Default.EditNote,
                    AppTab.AI to Icons.Default.Pets,
                )
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            isBoxOpen = false
                        },
                        icon = { Icon(icons.getValue(tab), contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when {
                selectedTab == AppTab.HOME && isBoxOpen -> BoxScreen(
                    items = PoiRepository.items,
                    onBack = { isBoxOpen = false },
                    onNotReady = { showNotReady(it) },
                )
                selectedTab == AppTab.HOME -> HomeScreen(
                    unreadCount = PoiRepository.items.count { !it.isRead },
                    todaySchedule = CalendarRepository.schedulesOn(LocalDate.now()).firstOrNull(),
                    onOpenBox = { isBoxOpen = true },
                    onTapCat = { selectedTab = AppTab.AI },
                    onAddMemo = { showQuickMemo = true },
                )
                selectedTab == AppTab.POI -> PoiScreen(
                    prefill = sharedPoiDraft,
                    onPrefillConsumed = { sharedPoiDraft = null },
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                )
                selectedTab == AppTab.CAL -> CalendarScreen()
                selectedTab == AppTab.MEMO -> MemoScreen()
                else -> AiChatScreen()
            }
        }
    }

    if (showQuickMemo) {
        AddMemoDialog(date = LocalDate.now(), onDismiss = { showQuickMemo = false })
    }
}

@Composable
private fun HomeScreen(
    unreadCount: Int,
    todaySchedule: ScheduleEntry?,
    onOpenBox: () -> Unit,
    onTapCat: () -> Unit,
    onAddMemo: () -> Unit,
) {
    val today = remember {
        SimpleDateFormat("M月d日（E）", Locale.JAPANESE).format(Date())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 上部：今日の日付・曜日、今日の予定
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = today, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val scheduleText = if (todaySchedule != null) {
                (todaySchedule.time?.let { "$it " } ?: "") + todaySchedule.title
            } else {
                "今日の予定はまだありません"
            }
            Text(text = scheduleText, fontSize = 14.sp, color = Color.Gray)
        }

        // 中央：猫ちゃん
        Box(
            modifier = Modifier
                .padding(vertical = 24.dp)
                .size(260.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable { onTapCat() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.cat_reference),
                contentDescription = "猫ちゃん",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 中段：届いたもの
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFEDE6E0))
                .clickable { onOpenBox() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "旦那ちゃんから届いたもの", fontSize = 14.sp)
            Text(text = "${unreadCount}件", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        // 下段：今日のプチメモ＋
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onAddMemo() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
            Text(
                text = "今日のプチメモ",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
