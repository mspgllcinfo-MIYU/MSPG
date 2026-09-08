package com.mspg.poicat

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mspg.poicat.brain.CatBrain
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiChatScreen() {
    var selectedRoom by remember { mutableStateOf(ChatRoom.CASUAL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text("猫AI", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatRoom.entries.forEach { room ->
                FilterChip(
                    selected = room == selectedRoom,
                    onClick = { selectedRoom = room },
                    label = { Text(room.label) },
                )
            }
        }

        ChatRoomView(room = selectedRoom, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChatRoomView(room: ChatRoom, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val photoRepository = remember { PhotoRepository(context.applicationContext) }
    val catBrain = remember { CatBrain(CatEventRepository(context.applicationContext), photoRepository) }
    var detailPhoto by remember { mutableStateOf<Photo?>(null) }
    // Phase B: a photo picked but not yet sent, shown as a preview next to the input.
    // Phase C will teach CatBrain to sort what this photo (plus any caption) means;
    // for now sending it only saves it and shows it in the chat.
    var pendingPhoto by remember { mutableStateOf<Photo?>(null) }

    val messages = ChatRepository.messages(room)
    val listState = rememberLazyListState()

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            input = text
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            speechLauncher.launch(buildSpeechIntent())
        }
    }

    // Same PickVisualMedia flow AlbumScreen/MemoScreen already use: the picker only
    // grants transient read access, so the photo is copied into app storage (and a
    // Photo row saved) right away via PhotoRepository.importFromUri — same as picking
    // a photo anywhere else in the app. Uncategorized (no album), like a plain "写真を
    // 選ぶ" pick elsewhere with no album selected.
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                pendingPhoto = photoRepository.importFromUri(uri, caption = null, albumName = null)
            }
        }
    }

    fun send() {
        val trimmed = input.trim()
        val photo = pendingPhoto
        if (trimmed.isBlank() && photo == null) return
        if (isSending) return

        ChatRepository.addMessage(
            room,
            ChatMessage("user", trimmed, System.currentTimeMillis(), photo?.let { listOf(it.id) } ?: emptyList()),
        )
        input = ""
        pendingPhoto = null

        if (photo != null && trimmed.isBlank()) {
            // A bare photo with no caption: Phase B already saved it to the album —
            // nothing for CatBrain to sort.
            return
        }

        isSending = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (photo != null) catBrain.respondToPhoto(trimmed, photo) else catBrain.respond(trimmed)
                }
            }
            isSending = false
            result.onSuccess { reply ->
                ChatRepository.addMessage(
                    room,
                    ChatMessage("assistant", reply.text, System.currentTimeMillis(), reply.photoIds),
                )
                errorText = null
            }
            result.onFailure {
                errorText = "うまく答えられなかったにゃ"
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                ChatBubble(message, photoRepository = photoRepository, onPhotoClick = { detailPhoto = it })
            }
            if (isSending) {
                item { TypingIndicator() }
            }
        }

        errorText?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        pendingPhoto?.let { photo ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(56.dp)) {
                    PhotoThumbnail(photo = photo, onClick = {}, modifier = Modifier.size(56.dp))
                    IconButton(
                        onClick = { pendingPhoto = null },
                        modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                    ) {
                        Text("✕", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    text = "写真を添付中",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }) {
                Text("📷")
            }

            Button(onClick = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    speechLauncher.launch(buildSpeechIntent())
                }
            }) {
                Text("🎤")
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                enabled = !isSending,
                placeholder = { Text("予定やメモを話しかけてにゃ") },
            )

            Button(onClick = { send() }, enabled = !isSending && (input.isNotBlank() || pendingPhoto != null)) {
                Text("送信")
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
                }
            },
            onDelete = {
                scope.launch {
                    photoRepository.delete(photo)
                    detailPhoto = null
                }
            },
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, photoRepository: PhotoRepository, onPhotoClick: (Photo) -> Unit) {
    val isUser = message.role == "user"
    var photos by remember(message.photoIds) { mutableStateOf<List<Photo>>(emptyList()) }
    LaunchedEffect(message.photoIds) {
        if (message.photoIds.isNotEmpty()) photos = photoRepository.byIds(message.photoIds)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // A photo-only send (Phase B) carries no text — skip the bubble entirely
        // rather than showing an empty one above the photo row below.
        if (message.text.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .background(
                            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = message.text,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (photos.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                photos.forEach { photo ->
                    Box(modifier = Modifier.padding(end = 6.dp)) {
                        PhotoThumbnail(photo = photo, onClick = { onPhotoClick(photo) }, modifier = Modifier.size(90.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun buildSpeechIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE)
    }
