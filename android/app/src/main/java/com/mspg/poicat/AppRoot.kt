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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mspg.poicat.brain.CatBrain
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.maps.LocationDisplayName
import com.mspg.poicat.maps.MapsLauncher
import com.mspg.poicat.maps.SharedLocationDetector
import com.mspg.poicat.room.RoomStore
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

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
    AppTab.AI -> NavIcons.CatAi
}

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
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var calendarYearMonth by rememberSaveable(stateSaver = YearMonthSaver) { mutableStateOf(YearMonth.now()) }
    var calendarSelectedDate by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(LocalDate.now()) }
    // Block E: Home's アルバム entry now lands directly on Poi's own アルバム sub-tab
    // (see PoiScreen's requestAlbumTab) instead of the old standalone showAlbum
    // overlay — this is that request, cleared the moment PoiScreen consumes it.
    var poiRequestAlbum by remember { mutableStateOf(false) }
    // Phase 3: Google連携（サインイン＋Driveフォルダ接続）は独立タブにせず、Home
    // からの一時的な全画面遷移として扱う — AppTab/BottomTabBarには一切触れない。
    var showConnectionSettings by remember { mutableStateOf(false) }
    // 「夫婦でシェア」(4桁PINルーム共有)専用入口 — showConnectionSettingsと同じ
    // 「Homeからの一時的な全画面遷移」パターン。AppTab/BottomTabBarには触れない。
    var showRoomShare by remember { mutableStateOf(false) }
    // #148 Maps-2C: 共有場所の確認ダイアログで「予定に追加」が押された後、
    // 代わりに予定入力ダイアログを表示するためのローカル切替。
    // PendingSharedLocation.pending自体はこの間も保持し続け(場所テキストの
    // 保管場所は変えない)、この変数は「今どちらのダイアログを見せるか」
    // だけを覚える。ここでキャンセルしても、成功しても、どちらの経路でも
    // 最終的にPendingSharedLocation.pendingをnullへ戻すため、次に何か別の
    // 場所が共有されるまでこのダイアログ自体は二度と現れない。
    var showScheduleInputForLocation by remember { mutableStateOf(false) }

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

    // Consumes ShareIntentHandler's one-shot "open 猫AI" request (see
    // PendingNavigation) — set only after the shared content's chat messages
    // already exist, so switching tabs here always lands on a screen that
    // has something to show.
    LaunchedEffect(PendingNavigation.requestedTab) {
        val tab = PendingNavigation.requestedTab
        if (tab != null) {
            selectedTab = tab
            PendingNavigation.requestedTab = null
        }
    }

    // #148 Maps-1B: ShareIntentHandlerが検出した「地図/場所の共有らしい
    // テキスト」(PendingSharedLocation、PendingNavigationと同じ一度きりの
    // stateパターン)をここで観測し、確認ダイアログを表示する。共有を受信
    // しただけではCatEventもメモも一切作らない — 下のダイアログで
    // ユーザーが「メモに保存」を明示的に選んだ場合だけ、既存の
    // CatEventRepository.remember()経由で保存する。選択後・キャンセル後・
    // ダイアログを閉じた場合のいずれも、この一度きりのstateは必ずnullへ
    // 戻す(同じ共有を再度処理しないため)。
    val sharedLocationText = PendingSharedLocation.pending
    if (sharedLocationText != null) {
        if (showScheduleInputForLocation) {
            // #148 Maps-2C: 「予定に追加」の入力ダイアログ。ここではまだ何も
            // 保存しない — ユーザーが実際に予定内容を入力し、下の
            // onSubmitScheduleがCatBrain.registerScheduleAndReturnEventで
            // 本当に予定登録に成功した場合にだけ、その返り値のCatEventへ
            // repository.setLocationで場所を紐付ける。失敗時はfalseを返す
            // だけで、pendingは保持されたまま(ユーザーが再入力できる)。
            AddLocationToScheduleDialog(
                locationText = sharedLocationText,
                onCancel = {
                    showScheduleInputForLocation = false
                    PendingSharedLocation.pending = null
                },
                onSubmitSchedule = { scheduleInput ->
                    val catBrain = CatBrain(CatEventRepository(context), PhotoRepository(context)) { RoomStore(context).displayName }
                    val created = catBrain.registerScheduleAndReturnEvent(scheduleInput)
                    if (created != null) {
                        CatEventRepository(context).setLocation(created, sharedLocationText)
                        showScheduleInputForLocation = false
                        PendingSharedLocation.pending = null
                        true
                    } else {
                        false
                    }
                },
            )
        } else {
            SharedLocationConfirmationDialog(
                text = sharedLocationText,
                onOpenMaps = {
                    val mapsUrl = SharedLocationDetector.extractMapsUrl(sharedLocationText)
                    if (mapsUrl != null) {
                        MapsLauncher.openUrl(context, mapsUrl)
                    } else {
                        MapsLauncher.openSearch(context, sharedLocationText)
                    }
                    PendingSharedLocation.pending = null
                },
                onSaveAsMemo = {
                    scope.launch { CatEventRepository(context).remember(sharedLocationText, null) }
                    PendingSharedLocation.pending = null
                },
                onAddToSchedule = { showScheduleInputForLocation = true },
                onDismiss = { PendingSharedLocation.pending = null },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (showConnectionSettings) {
                ConnectionScreen(onBack = { showConnectionSettings = false })
            } else if (showRoomShare) {
                RoomShareScreen(onBack = { showRoomShare = false })
            } else {
                when (selectedTab) {
                    AppTab.HOME -> HomeScreen(
                        onNavigate = { selectedTab = it },
                        onOpenAlbum = { poiRequestAlbum = true; selectedTab = AppTab.POI },
                        onOpenConnectionSettings = { showConnectionSettings = true },
                        onOpenRoomShare = { showRoomShare = true },
                    )
                    AppTab.POI -> PoiScreen(
                        requestAlbumTab = poiRequestAlbum,
                        onAlbumTabRequestConsumed = { poiRequestAlbum = false },
                    )
                    AppTab.CAL -> CalendarScreen(
                        yearMonth = calendarYearMonth,
                        onYearMonthChange = { calendarYearMonth = it },
                        selectedDate = calendarSelectedDate,
                        onSelectedDateChange = { calendarSelectedDate = it },
                    )
                    AppTab.AI -> AiChatScreen()
                }
            }
        }
        if (!showConnectionSettings && !showRoomShare) {
            BottomTabBar(selectedTab = selectedTab, onSelect = { selectedTab = it })
        }
    }
}

@Composable
private fun BottomTabBar(selectedTab: AppTab, onSelect: (AppTab) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
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
                    if (tab == AppTab.AI) {
                        // BB's real button is the standalone overlay below (drawn on
                        // top, and free to extend above this bar's own top edge) — this
                        // Spacer only reserves BB's normal 1/4-width share, so the other
                        // 3 tabs' widths and this Row's own height are exactly as if BB
                        // were still a plain tab here.
                        Spacer(modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    } else {
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
                                    modifier = Modifier.size(28.dp),
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

        // BB: not a function icon like the other 3 — this is BB himself,
        // standing in as 猫AI's entire entry point (no pill, no label; his
        // silhouette alone is the affordance). A standalone element layered
        // on top of the bar rather than a Row child, so he's free to peek
        // above the bar's own top edge without affecting the Row's height.
        //
        // The box below is a generously tall (76dp), bottom-anchored hit
        // target; the offset that lifts BB up is applied to the *icon*
        // inside it, not to the box itself, so the box's own bounds — and
        // therefore its tap region — never move. 76dp comfortably contains
        // the 54dp icon at any offset from 0 down to about -20dp, so tapping
        // BB anywhere he's drawn, ears included, always lands inside this
        // same clickable box, across the whole range this offset is meant
        // to be tuned within.
        //
        // Block D: BottomNav dropped from 5 slots to 4 (メモ removed, folded into
        // Poi) — this fraction stays matched to the Row's own weighted slot count
        // (3 pill tabs + AI's Spacer, all weight(1f)) so BB's absolute-width overlay
        // still lines up with the space the Row reserves for him.
        val bbSelected = selectedTab == AppTab.AI
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth(1f / 4f)
                .height(76.dp)
                .clickable { onSelect(AppTab.AI) },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Icon(
                imageVector = NavIcons.CatAi,
                contentDescription = AppTab.AI.label,
                tint = NavInk.copy(alpha = if (bbSelected) 1f else 0.42f),
                modifier = Modifier
                    .size(width = 40.dp, height = 54.dp)
                    .offset(y = (-18).dp),
            )
        }
    }
}

/**
 * #148 Maps-1B/Maps-2C: Google Maps/Gemini等から共有された場所テキストを
 * 受け取った直後に表示する、最小限の確認ダイアログ。[onOpenMaps]/
 * [onSaveAsMemo]/[onAddToSchedule]のいずれも呼び出し元(AppRoot)が明示的に
 * 選ばれた場合にだけ実行する — このComposable自身はCatEventもメモも一切
 * 作らない([onAddToSchedule]がタップされた時点でもまだ何も保存しない、
 * 実際の予定作成は[AddLocationToScheduleDialog]でユーザーが入力を完了して
 * 初めて行われる)。[onDismiss]はダイアログ外タップ・システムバックの
 * 両方から呼ばれ、[onSaveAsMemo]と同様に何も保存しない。
 */
@Composable
private fun SharedLocationConfirmationDialog(
    text: String,
    onOpenMaps: () -> Unit,
    onSaveAsMemo: () -> Unit,
    onAddToSchedule: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("この場所どうする？ 📍") },
        text = { Text(text) },
        confirmButton = {
            Column {
                TextButton(onClick = onOpenMaps) { Text("Googleマップで開く") }
                TextButton(onClick = onSaveAsMemo) { Text("メモに保存") }
                TextButton(onClick = onAddToSchedule) { Text("予定に追加") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/**
 * #148 Maps-2C: 共有された場所を予定に紐付けるための、予定内容の入力
 * ダイアログ。[onSubmitSchedule]が呼ばれた時点ではまだ何も保存しない —
 * [onSubmitSchedule]自身が[com.mspg.poicat.brain.CatBrain.
 * registerScheduleAndReturnEvent]で実際に予定登録に成功した場合にだけ
 * [com.mspg.poicat.data.CatEventRepository.setLocation]を呼び、その結果
 * (true/false)を返す。falseが返った場合はこのダイアログを閉じずエラー
 * 表示に留め、ユーザーが日時の分かる言い方で入力し直せるようにする —
 * 予定登録に失敗した場合に別の予定へ場所を付けてしまうことは無い(そもそも
 * 何も作られていないため付けようがない)。
 */
@Composable
private fun AddLocationToScheduleDialog(
    locationText: String,
    onCancel: () -> Unit,
    onSubmitSchedule: suspend (String) -> Boolean,
) {
    var input by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("予定に追加 📍") },
        text = {
            Column {
                Text(
                    "この場所: ${LocationDisplayName.extractDisplayName(locationText) ?: locationText}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("いつ・何の予定か入力してにゃ（例: 15日14時、打ち合わせ）")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSubmitting,
                )
                val error = errorMessage
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = input.isNotBlank() && !isSubmitting,
                onClick = {
                    val submitted = input
                    isSubmitting = true
                    scope.launch {
                        val success = onSubmitSchedule(submitted)
                        isSubmitting = false
                        if (!success) {
                            errorMessage = "予定として登録できなかったにゃ。日時が分かる言い方でもう一度入力してにゃ"
                        }
                    }
                },
            ) { Text("登録") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isSubmitting) { Text("キャンセル") }
        },
    )
}
