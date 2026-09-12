package com.mspg.poicat

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDate
import com.mspg.poicat.data.CatEvent
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import java.time.LocalDate
import kotlinx.coroutines.launch

// Step4-1: Poi-only design tokens, matching Home (Step1) / bottom nav (Step2)
// / AI chat (Step3) by value ("大人かわいい×ちょっと高級×無愛想な黒猫").
// Scoped to this file deliberately — Theme.kt stays untouched until this
// look is promoted (Step0).
private val PoiInk = Color(0xFF201E1D) // 墨色
private val PoiCream = Color(0xFFF7F3EF) // 生成り — matches Theme.kt's page background
private val PoiCard = Color(0xFFEFE7DE) // a shade deeper than the page, for task rows
private val PoiGold = Color(0xFFC9A66B) // restrained accent, never a fill color
private val PoiPink = Color(0xFFD98A9C) // the app's existing pink, kept rare

// 100点仕様: メモは独立タブをやめ、プラベの中に統合（PrivateSubTabで切替）。
// 仕事/プラベ/アルバム/ファイル — a plain Kotlin enum, saved the same way ChatRoom
// already is elsewhere in this app (rememberSaveable's default Saver stores an enum
// via Bundle's Serializable support, no custom Saver needed).
private enum class PoiCategoryTab(val label: String) {
    WORK("仕事"),
    PRIVATE("プラベ"),
    ALBUM("アルバム"),
    FILE("ファイル"),
}

// プラベ内部でのタスク/メモ切替 — メモは独立タブではなくプラベの子として存在する。
private enum class PrivateSubTab(val label: String) {
    TASK("タスク"),
    MEMO("メモ"),
}

/**
 * Poi tab: a plain to-do list, stored as `isTask = true` rows in the same
 * shared `cat_events` table. The cat AI answers "今日やることは？" /
 * "残ってるタスクは？" from the same not-yet-done rows this screen manages.
 *
 * Block C-1: internally split into 仕事/プラベ sub-tabs, filtered client-side from
 * the same full task list — no new DAO query, and category=null tasks (not yet
 * classified) show up under プラベ without ever being written back as "private".
 *
 * Block C-2 / 100点仕様: アルバム/ファイルは同じタブ行に並び、既存の AlbumScreen()/
 * FileScreen() composableをそのまま埋め込む。メモは独立タブではなく、プラベタブの
 * 内側（PrivateSubTab）に統合されている — MemoScreen()自体は変更なし、呼び出し場所
 * だけが変わった。
 */
@Composable
fun PoiScreen(
    // Block E: a one-shot signal for Home's アルバム entry point, which now lands
    // directly on this tab instead of the old standalone showAlbum overlay. Default
    // false/no-op so a normal BottomNav "ポイ" tap (PoiScreen() with no arguments)
    // behaves exactly as before — selectedCategoryTab keeps whatever the user last
    // had selected, via its own rememberSaveable below.
    requestAlbumTab: Boolean = false,
    onAlbumTabRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CatEventRepository(context.applicationContext) }
    val photoRepository = remember { PhotoRepository(context.applicationContext) }

    var selectedCategoryTab by rememberSaveable { mutableStateOf(PoiCategoryTab.WORK) }
    // 100点仕様: プラベ内のタスク/メモ切替 — プラベタブ自身とは独立して覚えておく。
    var privateSubTab by rememberSaveable { mutableStateOf(PrivateSubTab.TASK) }
    // Block C-2: Poi's own アルバム sub-tab keeps its own selected-album state,
    // independent of any external caller.
    var poiSelectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }

    // Block E: consumed exactly once — without onAlbumTabRequestConsumed clearing the
    // request on AppRoot's side, every later plain "ポイ" BottomNav tap would keep
    // forcing this tab back to アルバム instead of remembering the user's own choice.
    LaunchedEffect(requestAlbumTab) {
        if (requestAlbumTab) {
            selectedCategoryTab = PoiCategoryTab.ALBUM
            onAlbumTabRequestConsumed()
        }
    }
    var tasks by remember { mutableStateOf<List<CatEvent>>(emptyList()) }
    var editingTask by remember { mutableStateOf<CatEvent?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var linkedPhotos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var detailPhoto by remember { mutableStateOf<Photo?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        tasks = repository.tasks()
    }

    // Unclassified (category == null) tasks — every pre-Block-B row, and anything
    // added outside CatBrain — show up under プラベ so nothing existing goes missing,
    // without ever writing "private" back onto them.
    val visibleTasks = when (selectedCategoryTab) {
        PoiCategoryTab.WORK -> tasks.filter { it.category == CatEvent.CATEGORY_WORK }
        PoiCategoryTab.PRIVATE -> tasks.filter { it.category == CatEvent.CATEGORY_PRIVATE || it.category == null }
        // アルバム/ファイルは自分の画面を描画するため、このリストは使われない。
        PoiCategoryTab.ALBUM, PoiCategoryTab.FILE -> emptyList()
    }

    // Photos a chat-attached photo got linked to this task with (Phase C) — same
    // photo<->event link MemoScreen already reads for a memo's photo strip.
    LaunchedEffect(editingTask, refreshTick) {
        linkedPhotos = editingTask?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
    }

    // Fold's cover screen (~340dp wide) leaves very little room once the usual 20dp
    // side margins are subtracted — enough to crowd the 仕事/プラベ/アルバム/ファイル
    // tab row. Trim the side margins (not the vertical ones) on narrow widths only;
    // ordinary phones (360dp+) and Fold opened keep the original 20dp untouched.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val horizontalPadding = if (screenWidthDp < 360.dp) 12.dp else 20.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ポイ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PoiInk, modifier = Modifier.weight(1f))
            // Block C-2: this is the task list's own "＋追加" — メモ/アルバム bring
            // their own add affordances (MemoScreen's "＋追加", AlbumScreen's
            // "写真を選ぶ"/"撮影"), so this button only makes sense under 仕事/プラベ
            // (タスク側). 100点仕様: プラベ内でメモを見ている間はMemoScreen自身の
            // ＋追加ボタンがあるため、こちらは隠す。
            if (selectedCategoryTab == PoiCategoryTab.WORK ||
                (selectedCategoryTab == PoiCategoryTab.PRIVATE && privateSubTab == PrivateSubTab.TASK)
            ) {
                // Step4-1: same pink-pill family as AiChatScreen's "投げる", but a
                // quieter tint — the list is the star here, not this button.
                Button(
                    onClick = { editingTask = null; showDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = PoiPink.copy(alpha = 0.25f), contentColor = PoiInk),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text("＋ 追加")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Block C-1: 仕事/プラベ/アルバム/ファイル switch — FilterChip row with
        // horizontalScroll so tabs never get cramped on a narrow phone (same pattern
        // AlbumScreen's own album-name chip row already uses).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PoiCategoryTab.entries.forEach { tab ->
                val selected = tab == selectedCategoryTab
                FilterChip(
                    selected = selected,
                    onClick = { selectedCategoryTab = tab },
                    label = { Text(tab.label) },
                    shape = RoundedCornerShape(percent = 50),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = PoiInk.copy(alpha = 0.5f),
                        selectedContainerColor = PoiPink.copy(alpha = 0.22f),
                        selectedLabelColor = PoiInk,
                    ),
                    border = BorderStroke(0.dp, Color.Transparent),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (selectedCategoryTab) {
            PoiCategoryTab.WORK -> {
                TaskList(
                    tasks = visibleTasks,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .align(Alignment.CenterHorizontally),
                    onToggleCompleted = { task ->
                        scope.launch {
                            repository.setTaskCompleted(task, !task.completed)
                            refreshTick++
                        }
                    },
                    onClick = { task -> editingTask = task; showDialog = true },
                    onDelete = { task ->
                        scope.launch {
                            photoRepository.unlinkAllForMemo(task.id)
                            repository.delete(task)
                            refreshTick++
                        }
                    },
                )
            }
            // 100点仕様: メモは独立タブをやめ、プラベの中のタスク/メモ切替に統合した。
            // 既存のメモデータ（cat_eventsのdateTimeがnull行）はMemoScreen側でそのまま
            // 表示・追加・削除できる — テーブルもクエリも変更なし、置き場所だけの変更。
            PoiCategoryTab.PRIVATE -> {
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrivateSubTab.entries.forEach { sub ->
                            val selected = sub == privateSubTab
                            FilterChip(
                                selected = selected,
                                onClick = { privateSubTab = sub },
                                label = { Text(sub.label) },
                                shape = RoundedCornerShape(percent = 50),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = PoiInk.copy(alpha = 0.5f),
                                    selectedContainerColor = PoiPink.copy(alpha = 0.22f),
                                    selectedLabelColor = PoiInk,
                                ),
                                border = BorderStroke(0.dp, Color.Transparent),
                            )
                        }
                    }
                    // メモ側はMemoScreen自身の「＋追加」ヘッダー行があるので間隔を詰める
                    // (以前の独立メモタブが使っていたのと同じ4dp)。タスク側は12dp。
                    Spacer(Modifier.height(if (privateSubTab == PrivateSubTab.MEMO) 4.dp else 12.dp))
                    when (privateSubTab) {
                        PrivateSubTab.TASK -> TaskList(
                            tasks = visibleTasks,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .widthIn(max = 640.dp)
                                .align(Alignment.CenterHorizontally),
                            onToggleCompleted = { task ->
                                scope.launch {
                                    repository.setTaskCompleted(task, !task.completed)
                                    refreshTick++
                                }
                            },
                            onClick = { task -> editingTask = task; showDialog = true },
                            onDelete = { task ->
                                scope.launch {
                                    // Drop the photo links before the task itself is gone, so no
                                    // link is left pointing at a now-nonexistent task id. The
                                    // photos/album are never touched by this.
                                    photoRepository.unlinkAllForMemo(task.id)
                                    repository.delete(task)
                                    refreshTick++
                                }
                            },
                        )
                        // embedded = true: Poi自身のpadding(20.dp)/「ポイ」見出しが既に
                        // この領域を囲んでいるため、MemoScreen自身の余白・見出しは省略する。
                        PrivateSubTab.MEMO -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            MemoScreen(embedded = true)
                        }
                    }
                }
            }
            // Block C-2: embedded, still managing its own repository, state, and
            // dialogs internally. Design pass: embedded = true suppresses each screen's
            // own outer padding and heading, since Poi's own padding(20.dp) above
            // already insets this whole area.
            PoiCategoryTab.ALBUM -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AlbumScreen(
                    onBack = { selectedCategoryTab = PoiCategoryTab.WORK },
                    selectedAlbum = poiSelectedAlbum,
                    onSelectedAlbumChange = { poiSelectedAlbum = it },
                    embedded = true,
                )
            }
            PoiCategoryTab.FILE -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                FileScreen(embedded = true)
            }
        }
    }

    if (showDialog) {
        val current = editingTask
        TaskEditDialog(
            initialTitle = current?.title ?: "",
            initialDueDate = current?.dateTime?.toLocalDate(),
            isEditing = current != null,
            linkedPhotos = linkedPhotos,
            // Closes this AlertDialog before opening the picker/detail Dialog rather
            // than stacking a second dialog window on top of it — same approach
            // MemoScreen already uses for its own photo strip.
            onPickPhoto = { showDialog = false; showPhotoPicker = true },
            onUnlinkPhoto = { photo ->
                scope.launch {
                    current?.let { photoRepository.unlinkFromMemo(photo, it.id) }
                    linkedPhotos = current?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                }
            },
            onPhotoClick = { showDialog = false; detailPhoto = it },
            onDismiss = { showDialog = false; editingTask = null },
            onDelete = current?.let { task ->
                {
                    scope.launch {
                        photoRepository.unlinkAllForMemo(task.id)
                        repository.delete(task)
                        showDialog = false
                        editingTask = null
                        refreshTick++
                    }
                }
            },
            onSave = { title, dueDate ->
                scope.launch {
                    val dueMillis = dueDate?.toEpochMilli()
                    if (current != null) {
                        // edit() only touches title/dateTime — the task's existing
                        // category (work, private, or unclassified) is preserved as-is.
                        repository.edit(current, title, dueMillis)
                    } else {
                        // A brand-new task takes the category of whichever tab its
                        // "＋ 追加" was pressed from — an explicit choice, not a guess,
                        // so it's never left unclassified like an existing null row.
                        val category = when (selectedCategoryTab) {
                            PoiCategoryTab.WORK -> CatEvent.CATEGORY_WORK
                            PoiCategoryTab.PRIVATE -> CatEvent.CATEGORY_PRIVATE
                            // Unreachable: this dialog only opens from the task list's own
                            // "＋ 追加", which isn't shown under アルバム/ファイル, nor under
                            // プラベ>メモ. Kept exhaustive rather than an `else`, so a future
                            // new tab can't silently fall through here unnoticed.
                            PoiCategoryTab.ALBUM, PoiCategoryTab.FILE -> null
                        }
                        repository.addTask(title, dueMillis, category)
                    }
                    showDialog = false
                    editingTask = null
                    refreshTick++
                }
            },
        )
    }

    if (showPhotoPicker) {
        AlbumPhotoPickerDialog(
            onDismiss = { showPhotoPicker = false; showDialog = true },
            onPick = { photo ->
                scope.launch {
                    editingTask?.let { photoRepository.linkToMemo(photo, it.id) }
                    linkedPhotos = editingTask?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    showPhotoPicker = false
                    showDialog = true
                }
            },
        )
    }

    detailPhoto?.let { photo ->
        PhotoDetailDialog(
            photo = photo,
            onDismiss = { detailPhoto = null; showDialog = true },
            onSave = { caption, album, linkedDate ->
                scope.launch {
                    photoRepository.updateDetails(photo, caption, album, linkedDate?.toEpochMilli())
                    linkedPhotos = editingTask?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    detailPhoto = null
                    showDialog = true
                }
            },
            onDelete = {
                scope.launch {
                    photoRepository.softDelete(photo)
                    linkedPhotos = editingTask?.let { photoRepository.photosForMemo(it.id) } ?: emptyList()
                    detailPhoto = null
                    showDialog = true
                }
            },
        )
    }
}

// 100点仕様: 仕事タブとプラベ>タスクの両方から同じリスト描画を使うための抽出。
// modifier は呼び出し側（ColumnScope内）で組み立てて渡す — align(CenterHorizontally)
// はColumnScope拡張関数のため、ここではなく呼び出し側のColumn直下で呼ぶ必要がある。
@Composable
private fun TaskList(
    tasks: List<CatEvent>,
    modifier: Modifier,
    onToggleCompleted: (CatEvent) -> Unit,
    onClick: (CatEvent) -> Unit,
    onDelete: (CatEvent) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tasks.isEmpty()) {
            item {
                Text(
                    text = "タスクはまだ入ってないにゃ",
                    color = PoiInk.copy(alpha = 0.5f),
                )
            }
        }
        items(tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                onToggleCompleted = { onToggleCompleted(task) },
                onClick = { onClick(task) },
                onDelete = { onDelete(task) },
            )
        }
    }
}

@Composable
private fun TaskRow(
    task: CatEvent,
    onToggleCompleted: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Step4-1: a completed row stays the same shape, just quieter — lighter
    // card, weaker ink — on top of the existing strike-through.
    val cardColor = if (task.completed) PoiCard.copy(alpha = 0.55f) else PoiCard
    val titleColor = if (task.completed) PoiInk.copy(alpha = 0.4f) else PoiInk
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Design pass: a very light lift (matching BottomTabBar's own shadow(3.dp),
            // just quieter) so cards read as sitting slightly above the page rather
            // than flat — "少し高級" without turning into a heavy Material card.
            .shadow(elevation = 1.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(cardColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = task.completed,
            onCheckedChange = { onToggleCompleted() },
            colors = CheckboxDefaults.colors(
                checkedColor = PoiGold,
                uncheckedColor = PoiInk.copy(alpha = 0.4f),
                checkmarkColor = PoiCream,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                color = titleColor,
            )
            task.dateTime?.let {
                val due = it.toLocalDate()
                Text(
                    text = "期限: ${due.monthValue}月${due.dayOfMonth}日",
                    fontSize = 12.sp,
                    color = if (task.completed) PoiInk.copy(alpha = 0.35f) else PoiGold,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Text("✕", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TaskEditDialog(
    initialTitle: String,
    initialDueDate: LocalDate?,
    isEditing: Boolean,
    linkedPhotos: List<Photo>,
    onPickPhoto: () -> Unit,
    onUnlinkPhoto: (Photo) -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (title: String, dueDate: LocalDate?) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle) }
    var dueDate by remember { mutableStateOf(initialDueDate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PoiCard,
        titleContentColor = PoiInk,
        textContentColor = PoiInk,
        title = { Text(if (isEditing) "タスクを編集" else "タスクを追加") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("やること") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = PoiInk, unfocusedTextColor = PoiInk),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val base = dueDate ?: LocalDate.now()
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth -> dueDate = LocalDate.of(year, month + 1, dayOfMonth) },
                            base.year,
                            base.monthValue - 1,
                            base.dayOfMonth,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(dueDate?.let { "期限: ${it.monthValue}月${it.dayOfMonth}日" } ?: "期限を設定（任意）")
                }
                if (dueDate != null) {
                    TextButton(onClick = { dueDate = null }) {
                        Text("期限をなしにする")
                    }
                }

                // Photos can only be linked once the task exists (needs an id), so this
                // section is hidden while adding a brand-new task — same constraint
                // MemoScreen already has for its own photo strip.
                if (isEditing) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("写真", fontWeight = FontWeight.Bold, color = PoiInk, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = onPickPhoto,
                            colors = ButtonDefaults.textButtonColors(contentColor = PoiPink),
                        ) { Text("＋ 写真を選ぶ") }
                    }
                    if (linkedPhotos.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            linkedPhotos.forEach { photo ->
                                Box(modifier = Modifier.size(64.dp)) {
                                    PhotoThumbnail(photo = photo, onClick = { onPhotoClick(photo) })
                                    IconButton(
                                        onClick = { onUnlinkPhoto(photo) },
                                        modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                                    ) {
                                        Text("✕", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onSave(title.trim(), dueDate) },
                enabled = title.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PoiPink, contentColor = Color.White),
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
