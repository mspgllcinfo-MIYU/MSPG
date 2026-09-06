package com.mspg.poicat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AiChatScreen() {
    var selectedRoom by remember { mutableStateOf(ChatRoom.CASUAL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text(text = "猫AI", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChatRoom.entries.forEach { room ->
                FilterChip(
                    selected = selectedRoom == room,
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
    val messages = ChatRepository.messages(room)
    var input by remember(room) { mutableStateOf("") }
    var isSending by remember(room) { mutableStateOf(false) }
    var errorText by remember(room) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) input = text
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) speechLauncher.launch(buildSpeechIntent()) }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isSending) return
        ChatRepository.addMessage(room, ChatMessage("user", trimmed, System.currentTimeMillis()))
        input = ""
        errorText = null
        isSending = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { CatAiClient.sendMessage(ChatRepository.messages(room)) }
            }
            isSending = false
            result
                .onSuccess {
                    ChatRepository.addMessage(room, ChatMessage("assistant", it, System.currentTimeMillis()))
                }
                .onFailure {
                    errorText = it.message ?: "猫AIに接続できなかったニャ"
                }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(text = "まだ会話はないニャ。話しかけてみてニャ。", fontSize = 14.sp, color = Color.Gray)
                }
            }
            items(messages) { message -> ChatBubble(message) }
            if (isSending) {
                item { TypingIndicator() }
            }
        }

        val error = errorText
        if (error != null) {
            Text(
                text = error,
                fontSize = 12.sp,
                color = Color(0xFFB00020),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("猫ちゃんに話しかけるニャ") },
                enabled = !isSending,
            )
            IconButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        speechLauncher.launch(buildSpeechIntent())
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            ) {
                Icon(Icons.Default.Mic, contentDescription = "音声入力")
            }
            IconButton(onClick = { send(input) }, enabled = !isSending) {
                Icon(Icons.Default.Send, contentDescription = "送信")
            }
        }
    }
}

private fun buildSpeechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE)
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = message.text,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isUser) Color(0xFFD98A9C) else Color(0xFFEDE6E0))
                .padding(12.dp),
            color = if (isUser) Color.White else Color.Black,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun TypingIndicator() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Text(
            text = "考え中ニャ…",
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFEDE6E0))
                .padding(12.dp),
            color = Color.Gray,
            fontSize = 14.sp,
        )
    }
}
