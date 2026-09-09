package com.mspg.poicat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.YearMonth

// Step2: bottom nav design tokens, matching HomeScreen's local palette by value
// (HomeScreen.kt itself is not touched — these are duplicated on purpose until
// Step0 promotes a shared set into Theme.kt).
private val NavInk = Color(0xFF201E1D) // 墨色
private val NavPink = Color(0xFFD98A9C) // existing app pink, used only as a soft selection pill
private val NavGold = Color(0xFFC9A66B) // restrained accent — the top hairline only

// Step: a matched set of 5 custom icons (NavIcons.kt) replacing the standard
// Material glyphs, so BottomTabBar reads as this app's own designed set
// rather than generic-Android-app icons — see NavIcons.kt for the shared
// design language (24x24 viewport, filled-silhouette-with-cutouts) all 5
// are drawn in.
private fun iconFor(tab: AppTab): ImageVector = when (tab) {
    AppTab.HOME -> NavIcons.Home
    AppTab.POI -> NavIcons.Poi
    AppTab.CAL -> NavIcons.Calendar
    AppTab.MEMO -> NavIcons.Memo
    AppTab.AI -> NavIcons.CatAi
}

// BB gets a touch more visual size than the other 4 (which all share one
// size) — it's this app's one character-branded tab, and needs the extra
// room for its ears/half-lidded eyes to still read at a glance.
private fun iconSizeFor(tab: AppTab): Dp = if (tab == AppTab.AI) 32.dp else 28.dp

// "Where was I looking" navigation state, kept across BottomNav tab switches
// (and, as a side effect of rememberSaveable, config changes like a Fold
// fold/unfold). Deliberately limited to these 4 values — every in-progress
// dialog/input/photo-capture state stays local `remember` in its own screen
// and is still discarded when that screen leaves composition, unchanged.
// YearMonth/LocalDate aren't natively Bundle-storable, so each gets an
// explicit Saver rather than relying on rememberSaveable's default handling.
private val YearMonthSaver = Saver<YearMonth, List<Int>>(
    save = { listOf(it.year, it.monthValue) },
    restore = { YearMonth.of(it[0], it[1]) },
)

private val LocalDateSaver = Saver<LocalDate, Long>(
    save = { it.toEpochDay() },
    restore = { LocalDate.ofEpochDay(it) },
)

@Composable
fun AppRoot() {
    var selectedTab by remember { mutableStateOf(AppTab.AI) }
    var showAlbum by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var calendarYearMonth by rememberSaveable(stateSaver = YearMonthSaver) { mutableStateOf(YearMonth.now()) }
    var calendarSelectedDate by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(LocalDate.now()) }
    var aiSelectedRoom by rememberSaveable { mutableStateOf(ChatRoom.CASUAL) }
    var albumSelectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: reminders just won't show if denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (showAlbum) {
                AlbumScreen(
                    onBack = { showAlbum = false },
                    selectedAlbum = albumSelectedAlbum,
                    onSelectedAlbumChange = { albumSelectedAlbum = it },
                )
            } else {
                when (selectedTab) {
                    AppTab.HOME -> HomeScreen(onNavigate = { selectedTab = it }, onOpenAlbum = { showAlbum = true })
                    AppTab.POI -> PoiScreen()
                    AppTab.CAL -> CalendarScreen(
                        yearMonth = calendarYearMonth,
                        onYearMonthChange = { calendarYearMonth = it },
                        selectedDate = calendarSelectedDate,
                        onSelectedDateChange = { calendarSelectedDate = it },
                    )
                    AppTab.MEMO -> MemoScreen()
                    AppTab.AI -> AiChatScreen(
                        selectedRoom = aiSelectedRoom,
                        onSelectedRoomChange = { aiSelectedRoom = it },
                    )
                }
            }
        }
        BottomTabBar(selectedTab = selectedTab, onSelect = { showAlbum = false; selectedTab = it })
    }
}

@Composable
private fun BottomTabBar(selectedTab: AppTab, onSelect: (AppTab) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // A hairline, not a heavy divider — just enough to separate the nav from
        // whatever screen (including Home's dark hero) sits above it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NavGold.copy(alpha = 0.25f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 3.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 10.dp, horizontal = 6.dp),
        ) {
            AppTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                // The tap target (this Box) always keeps its full weight(1f)
                // width and a 48dp-minimum height, independent of how compact
                // the visual pill inside it is — shrinking the look must not
                // shrink the tappable area.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) NavPink.copy(alpha = 0.22f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = iconFor(tab),
                            contentDescription = tab.label,
                            tint = NavInk.copy(alpha = if (selected) 1f else 0.42f),
                            modifier = Modifier.size(iconSizeFor(tab)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tab.label,
                            fontSize = 13.5.sp,
                            color = NavInk.copy(alpha = if (selected) 1f else 0.42f),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
