package com.mspg.poicat

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Album tab (opened from Home, not one of the 5 bottom tabs): add a photo
 * from the gallery or camera, browse them newest-first, filter by album
 * name, and tap one to view it large with an editable caption/album and a
 * delete option. Photos are stored once in app-private storage and only
 * ever referenced (never copied) from here.
 */
@Composable
fun AlbumScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { PhotoRepository(context.applicationContext) }

    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var albums by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
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
                repository.importFromUri(uri, caption = null, albumName = selectedAlbum)
                reload()
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) {
            scope.launch {
                repository.registerCapturedFile(file, caption = null, albumName = selectedAlbum)
                reload()
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

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 戻る") }
            Spacer(Modifier.width(4.dp))
            Text("アルバム", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("写真を選ぶ") }

            Button(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }) { Text("撮影") }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = selectedAlbum == null, onClick = { selectedAlbum = null }, label = { Text("すべて") })
            albums.forEach { album ->
                FilterChip(selected = selectedAlbum == album, onClick = { selectedAlbum = album }, label = { Text(album) })
            }
        }

        Spacer(Modifier.height(12.dp))

        if (photos.isEmpty()) {
            Text("写真はまだないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text("読み込み中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "登録日: ${addedDate.monthValue}月${addedDate.dayOfMonth}日",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text("タイトル・メモ") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = album,
                onValueChange = { album = it },
                label = { Text("アルバム名（任意）") },
                modifier = Modifier.fillMaxWidth(),
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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDelete) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("閉じる") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onSave(caption.trim().ifBlank { null }, album.trim().ifBlank { null }, linkedDate) }) {
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
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            Text("写真を選ぶ", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            if (photos.isEmpty()) {
                Text("アルバムに写真がまだないにゃ", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
