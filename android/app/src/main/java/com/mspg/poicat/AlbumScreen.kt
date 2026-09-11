package com.mspg.poicat

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.brain.toLocalDate
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.drive.PhotoDriveSync
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Step4-4: Album-only design tokens, matching Home (Step1) / bottom nav
// (Step2) / AI chat (Step3) / Poi/Calendar/Memo (Step4-1〜3) by value
// ("大人かわいい×ちょっと高級×無愛想な黒猫"). Scoped to this file
// deliberately — Theme.kt stays untouched until this look is promoted
// (Step0). PhotoThumbnail/PhotoDetailDialog/AlbumPhotoPickerDialog are also
// defined in this file and shared by AiChat/Poi/Calendar/Memo, so their
// visual-only changes here are the last piece completing the palette
// across every screen that shows a photo.
private val AlbumInk = Color(0xFF201E1D) // 墨色
private val AlbumCream = Color(0xFFF7F3EF) // 生成り — matches Theme.kt's page background
private val AlbumCard = Color(0xFFEFE7DE) // a shade deeper than the page
private val AlbumGold = Color(0xFFC9A66B) // restrained accent, never a fill color
private val AlbumPink = Color(0xFFD98A9C) // the app's existing pink, kept rare

/**
 * Album tab (opened from Home, not one of the 5 bottom tabs): add a photo
 * from the gallery or camera, browse them newest-first, filter by album
 * name, and tap one to view it large with an editable caption/album and a
 * delete option. Photos are stored once in app-private storage and only
 * ever referenced (never copied) from here.
 */
@Composable
fun AlbumScreen(
    onBack: () -> Unit,
    selectedAlbum: String?,
    onSelectedAlbumChange: (String?) -> Unit,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val repository = remember { PhotoRepository(context.applicationContext) }

    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var albums by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailPhoto by remember { mutableStateOf<Photo?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    suspend fun reload() {
        photos = selectedAlbum?.let { repository.byAlbum(it) } ?: repository.all()
        albums = repository.albumNames()
    }

    LaunchedEffect(selectedAlbum) { reload() }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val photo = repository.importFromUri(uri, caption = null, albumName = selectedAlbum)
                reload()
                // ローカル保存は上の行で既に完了済み — この先のDriveアップロード試行が
                // 何であれ、ここまでの結果（画面に表示済みの写真）には影響しない。
                PhotoDriveSync.syncNewPhoto(activity, repository, photo)
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            scope.launch {
                val photo = repository.registerCapturedFile(file, caption = null, albumName = selectedAlbum)
                reload()
                PhotoDriveSync.syncNewPhoto(activity, repository, photo)
            }
        }
    }

    fun launchCamera() {
        val file = repository.newCameraCaptureFile()
        pendingCameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        takePicture.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchCamera() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Design pass: embedded (inside Poi's own アルバム タブ) skips this screen's
            // own outer padding entirely — Poi's own padding(20.dp) around the whole tab
            // area already provides it, so adding it again here would double the margin.
            .padding(if (embedded) 0.dp else 20.dp),
    ) {
        // Design tweak: embedded drops both the redundant "アルバム" heading (Poi's own
        // "ポイ" heading and selected タブ already say where you are) and the "← 戻る"
        // button — tapping "仕事" in the tab row above is the more direct way back, so
        // a second, separate back affordance is redundant here. onBack stays wired for
        // the standalone (embedded = false) case, where there's no tab row to use instead.
        if (!embedded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← 戻る", color = AlbumInk)
                }
                Spacer(Modifier.width(4.dp))
                Text("アルバム", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AlbumInk)
            }
            Spacer(Modifier.height(12.dp))
        }

        // Step4-4: two equal utility actions, neither a single standout CTA —
        // "写真を選ぶ" gets a soft pink pill, "撮影" a quieter card-toned one.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                colors = ButtonDefaults.buttonColors(containerColor = AlbumPink.copy(alpha = 0.25f), contentColor = AlbumInk),
                shape = RoundedCornerShape(percent = 50),
            ) { Text("写真を選ぶ") }

            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AlbumCard, contentColor = AlbumInk),
                shape = RoundedCornerShape(percent = 50),
            ) { Text("撮影") }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = Color.Transparent,
                labelColor = AlbumInk.copy(alpha = 0.5f),
                selectedContainerColor = AlbumPink.copy(alpha = 0.22f),
                selectedLabelColor = AlbumInk,
            )
            val chipBorder = BorderStroke(0.dp, Color.Transparent)
            FilterChip(
                selected = selectedAlbum == null,
                onClick = { onSelectedAlbumChange(null) },
                label = { Text("すべて") },
                shape = RoundedCornerShape(percent = 50),
                colors = chipColors,
                border = chipBorder,
            )
            albums.forEach { album ->
                FilterChip(
                    selected = selectedAlbum == album,
                    onClick = { onSelectedAlbumChange(album) },
                    label = { Text(album) },
                    shape = RoundedCornerShape(percent = 50),
                    colors = chipColors,
                    border = chipBorder,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (photos.isEmpty()) {
            Text("写真はまだないにゃ", color = AlbumInk.copy(alpha = 0.5f))
        } else {
            // Step4-4: capped at 640dp and centered so the grid doesn't stretch
            // edge-to-edge on a Fold's unfolded, much wider screen; a normal
            // phone stays fillMaxWidth as before.
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                items(photos, key = { it.id }) { photo ->
                    PhotoThumbnail(photo = photo, onClick = { detailPhoto = photo })
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
                    repository.updateDetails(photo, caption, album, linkedDate?.toEpochMilli())
                    reload()
                    detailPhoto = null
                }
            },
            onDelete = {
                scope.launch {
                    repository.delete(photo)
                    reload()
                    detailPhoto = null
                }
            },
        )
    }
}

@Composable
fun PhotoThumbnail(photo: Photo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var bitmap by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photo.filePath) { bitmap = decodeSampledBitmap(photo.filePath, 300) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(AlbumCard)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = photo.caption,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        photo.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            Text(
                text = caption,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun PhotoDetailDialog(
    photo: Photo,
    onDismiss: () -> Unit,
    onSave: (caption: String?, album: String?, linkedDate: LocalDate?) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var caption by remember(photo.id) { mutableStateOf(photo.caption ?: "") }
    var album by remember(photo.id) { mutableStateOf(photo.albumName ?: "") }
    var linkedDate by remember(photo.id) { mutableStateOf(photo.linkedDate?.toLocalDate()) }
    var bitmap by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(photo.filePath) { bitmap = decodeSampledBitmap(photo.filePath, 1200) }
    val addedDate = photo.addedAt.toLocalDate()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(AlbumCard)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AlbumCream),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text("読み込み中…", color = AlbumInk.copy(alpha = 0.5f))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "登録日: ${addedDate.monthValue}月${addedDate.dayOfMonth}日",
                fontSize = 12.sp,
                color = AlbumGold,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text("タイトル・メモ") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AlbumInk, unfocusedTextColor = AlbumInk),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                label = { Text("アルバム名（任意）") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AlbumInk, unfocusedTextColor = AlbumInk),
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val base = linkedDate ?: LocalDate.now()
                    DatePickerDialog(
                        context,
                        { _, year, month, dayOfMonth -> linkedDate = LocalDate.of(year, month + 1, dayOfMonth) },
                        base.year,
                        base.monthValue - 1,
                        base.dayOfMonth,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(linkedDate?.let { "カレンダーの日付: ${it.monthValue}月${it.dayOfMonth}日" } ?: "カレンダーの日付を設定（任意）")
            }
            if (linkedDate != null) {
                TextButton(onClick = { linkedDate = null }) {
                    Text("日付をなしにする")
                }
            }

            Spacer(Modifier.height(12.dp))
            // horizontalScroll: 4 buttons on one row is tight on a Fold's narrow cover
            // screen — scrollable rather than letting any button clip or overlap, same
            // pattern this file's own album-chip row uses. Modifier.weight() can't be
            // combined with horizontalScroll (unbounded width), so this drops the old
            // space-between push-right layout for a plain left-to-right flow instead.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onDelete) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    scope.launch {
                        val success = exportToDeviceStorage(
                            context = context,
                            sourceFile = File(photo.filePath),
                            displayName = (photo.caption?.trim()?.ifBlank { null } ?: "photo_${photo.id}") + ".jpg",
                            mimeType = "image/jpeg",
                            isImage = true,
                        )
                        Toast.makeText(
                            context,
                            if (success) "端末に保存したにゃ" else "保存できなかったにゃ",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    Text("端末に保存", color = AlbumInk)
                }
                TextButton(onClick = onDismiss) { Text("閉じる") }
                Button(
                    onClick = { onSave(caption.trim().ifBlank { null }, album.trim().ifBlank { null }, linkedDate) },
                    colors = ButtonDefaults.buttonColors(containerColor = AlbumPink, contentColor = Color.White),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

/**
 * A grid of every photo already in the album, for picking one to link
 * elsewhere (currently: from a memo). Read-only browsing — tapping a photo
 * invokes [onPick] and does not itself open the full detail view.
 *
 * Deliberately not a LazyVerticalGrid: this dialog's content sits inside a
 * wrap-content Dialog window rather than a screen with an already-bounded
 * height (like AlbumScreen's own grid), and a lazy layout measured with an
 * unbounded height throws. A plain scrollable Column of chunked rows has no
 * such requirement, and the photo counts here are small enough that it
 * costs nothing in practice.
 */
@Composable
fun AlbumPhotoPickerDialog(onDismiss: () -> Unit, onPick: (Photo) -> Unit) {
    val context = LocalContext.current
    val repository = remember { PhotoRepository(context.applicationContext) }
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    LaunchedEffect(Unit) { photos = repository.all() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AlbumCard)
                .padding(16.dp),
        ) {
            Text("写真を選ぶ", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AlbumInk)
            Spacer(Modifier.height(12.dp))
            if (photos.isEmpty()) {
                Text("アルバムに写真がまだないにゃ", color = AlbumInk.copy(alpha = 0.5f))
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    photos.chunked(3).forEach { rowPhotos ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowPhotos.forEach { photo ->
                                PhotoThumbnail(
                                    photo = photo,
                                    onClick = { onPick(photo) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - rowPhotos.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("閉じる") }
        }
    }
}

/** Decodes [path] downsampled to roughly [reqSize]px on the long side, to keep
 * thumbnail/detail memory use reasonable regardless of the original photo size. */
suspend fun decodeSampledBitmap(path: String, reqSize: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

    var sample = 1
    while (bounds.outWidth / sample > reqSize || bounds.outHeight / sample > reqSize) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}
