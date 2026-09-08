package com.mspg.poicat

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

// Step3: AI chat-only design tokens, matching Home (Step1) / bottom nav (Step2)
// by value ("大人かわいい×ちょっと高級×無愛想な黒猫"). Scoped to this file
// deliberately — Theme.kt stays untouched until this look is promoted (Step0).
private val AiInk = Color(0xFF201E1D) // 墨色
private val AiCream = Color(0xFFF7F3EF) // 生成り — matches Theme.kt's page background
private val AiCard = Color(0xFFEFE7DE) // a shade deeper than the page, for the input tray
private val AiBubble = Color(0xFFEFE6D8) // warm cream — the cat's chat bubble
private val AiGold = Color(0xFFC9A66B) // restrained accent, never a fill color
private val AiPink = Color(0xFFD98A9C) // the app's existing pink, kept rare

@Composable
fun AiChatScreen() {
    var selectedRoom by remember { mutableStateOf(ChatRoom.CASUAL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text("猫AI", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AiInk)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatRoom.entries.forEach { room ->
                val selected = room == selectedRoom
                FilterChip(
                    selected = selected,
                    onClick = { selectedRoom = room },
                    label = { Text(room.label) },
                    shape = RoundedCornerShape(percent = 50),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = AiInk.copy(alpha = 0.5f),
                        selectedContainerColor = AiPink.copy(alpha = 0.22f),
                        selectedLabelColor = AiInk,
                    ),
                    border = BorderStroke(0.dp, Color.Transparent),
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

        // Step3: the whole input area reads as one tray the user throws things
        // into ("猫への投げ込み口"), not a chat compose bar — same generous-cream
        // + gold-hairline language as Home's card sections and the bottom nav.
        // Capped at 640dp and centered so it doesn't stretch edge-to-edge on a
        // Fold's unfolded, much wider screen; the message list above is unaffected.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AiCard)
                .border(1.dp, AiGold.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                .padding(12.dp),
        ) {
            pendingPhoto?.let { photo ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
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
                        text = "これも一緒に投げるにゃ",
                        fontSize = 12.sp,
                        color = AiInk.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 📷 / 🎤 stay quiet — cream on cream — so "投げる" reads as the
                // one primary action in the tray.
                Button(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AiCream, contentColor = AiInk),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text("📷")
                }

                Button(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        } else {
                            speechLauncher.launch(buildSpeechIntent())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AiCream, contentColor = AiInk),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text("🎤")
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    enabled = !isSending,
                    placeholder = { Text("ここに投げるにゃ") },
                )

                Button(
                    onClick = { send() },
                    enabled = !isSending && (input.isNotBlank() || pendingPhoto != null),
                    colors = ButtonDefaults.buttonColors(containerColor = AiPink, contentColor = Color.White),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text("投げる")
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
                            // Step3: a restrained pink for the user, warm cream for the
                            // cat — both paired with dark ink text for legibility over
                            // Material3's default primary/onPrimary (white-on-pink) pair.
                            color = if (isUser) AiPink.copy(alpha = 0.35f) else AiBubble,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = message.text,
                        color = AiInk,
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
                .background(AiBubble, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text("…", color = AiInk.copy(alpha = 0.6f))
        }
    }
}

private fun buildSpeechIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE)
    }
