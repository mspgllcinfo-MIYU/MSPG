package com.mspg.poicat

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mspg.poicat.brain.CatBrain
import com.mspg.poicat.brain.toEpochMilli
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.gemini.ExternalAiLauncher
import com.mspg.poicat.gemini.ForgetResult
import com.mspg.poicat.gemini.GeminiOutcome
import com.mspg.poicat.gemini.GeminiSearchService
import com.mspg.poicat.gemini.MariTanMemoryStore
import com.mspg.poicat.gemini.extractForgetQuery
import com.mspg.poicat.gemini.extractRememberContent
import com.mspg.poicat.room.RoomStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

// 100点仕様: 仕事/プライベート/雑談の部屋分けを廃止し、猫AIの会話画面は1つだけに
// なった。ユーザーは分類を選ばない — CatBrainはもともとメッセージの内容自体から
// タスクの仕事/プライベート分類を判断しており（部屋の選択は一度も見ていなかった）、
// この変更でその挙動は変わらない。
@Composable
fun AiChatScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text("猫AI", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AiInk)
        Spacer(Modifier.height(12.dp))

        ChatView(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChatView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val photoRepository = remember { PhotoRepository(context.applicationContext) }
    // #144: 「私の仕事」のような話者本人を指す質問にCatBrainが答えられるよう、
    // この端末の現在の利用者(RoomStore.displayName)を都度読めるラムダとして渡す
    // — 固定値ではないので、設定画面で表示名を変えても次の発話から反映される。
    val roomStore = remember { RoomStore(context.applicationContext) }
    val catBrain = remember {
        CatBrain(CatEventRepository(context.applicationContext), photoRepository) { roomStore.displayName }
    }
    var detailPhoto by remember { mutableStateOf<Photo?>(null) }
    // Phase B: a photo picked but not yet sent, shown as a preview next to the input.
    // Phase C will teach CatBrain to sort what this photo (plus any caption) means;
    // for now sending it only saves it and shows it in the chat.
    var pendingPhoto by remember { mutableStateOf<Photo?>(null) }

    val messages = ChatRepository.messages()
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
        MariTanRow()

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

            // 📷 / 🎤 stay quiet — cream on cream — so "投げる" reads as the one
            // primary action in the tray. Extracted so the wide (single-row) and
            // narrow (stacked) layouts below can share the exact same buttons.
            val photoPickerButton: @Composable () -> Unit = {
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
            }
            val micButton: @Composable () -> Unit = {
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
            }
            val sendButton: @Composable () -> Unit = {
                Button(
                    onClick = { send() },
                    enabled = !isSending && (input.isNotBlank() || pendingPhoto != null),
                    colors = ButtonDefaults.buttonColors(containerColor = AiPink, contentColor = Color.White),
                    shape = RoundedCornerShape(percent = 50),
                ) {
                    Text("投げる")
                }
            }

            // Fold's cover screen (~340dp wide) can't fit 2 icon buttons + a send
            // button + a usefully wide text field on one row — below this width the
            // text field used to get squeezed down to a near-unusable sliver. Stack
            // the text field above the buttons instead; ordinary phones (360dp+) and
            // Fold opened keep the exact same single-row layout as before.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 360.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSending,
                            placeholder = { Text("ここに投げるにゃ") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AiInk, unfocusedTextColor = AiInk),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            photoPickerButton()
                            micButton()
                            Spacer(Modifier.weight(1f))
                            sendButton()
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        photoPickerButton()
                        micButton()
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            enabled = !isSending,
                            placeholder = { Text("ここに投げるにゃ") },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = AiInk, unfocusedTextColor = AiInk),
                        )
                        sendButton()
                    }
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
                    photoRepository.softDelete(photo)
                    detailPhoto = null
                }
            },
        )
    }
}

private enum class MariTanState { IDLE, LISTENING, THINKING, SPEAKING, SULKING, NOT_CONFIGURED, ERROR }

/**
 * マリたん：「外の情報を声で聞くための女の子猫AI」。黒猫POI AIとは完全に別役割・
 * 別UI要素として、この小さなアバター行1つだけを既存の黒猫AI会話画面(ChatView)の
 * 先頭に置く形で実装する。専用のチャット画面・文字入力欄・独立した会話履歴は
 * 一切持たない。
 *
 * タップ→（権限が無ければ許可ダイアログ→）即マイク起動→音声認識→Gemini
 * へ質問文だけを送信→回答をAndroid標準の音声合成(TextToSpeech)でマリたん
 * 自身が声で読み上げる、という一直線の流れ。回答は黒猫AIのチャット履歴
 * (ChatRepository)へは一切書き込まない — 黒猫とマリたんを混同させないため。
 * 通常時は長い回答テキストを画面表示せず、短い状態ラベル（聞いてるにゃ/
 * 調べてるにゃ/お話するにゃ等）だけを表示する。
 *
 * Geminiへ送るのは今回認識された音声テキストのみ — 黒猫AIの会話履歴やPOI内部の
 * 他データ（仕事/プラベ/タスク/メモ/カレンダー/アルバム/ファイル/Drive/ルーム共有）
 * は一切渡さない（[GeminiSearchService]のクラスコメント参照）。
 *
 * Version 1ではGoogle Search Groundingを使わない（実機A/Bテストで、grounding
 * 機能自体の無料枠がこのプロジェクトでは極端に少なく、429の直接原因と確定した
 * ため — [GeminiSearchService]参照）。無料での会話可能時間・回数を最優先し、
 * 天気/最新ニュース等のリアルタイム検索が要る質問への対応はVersion 2で検討する。
 *
 * 音声認識結果は、Geminiへ送る前にまずローカルだけで3種類の意図を判定する
 * （いずれもGemini APIを呼ばない — 無料枠の消費を増やさないための設計）:
 * 1. 外部AIアプリの起動依頼（[ExternalAiLauncher]） — 「ChatGPT」「Gemini」等の
 *    キーワードを検出したらAndroid Intentで該当アプリを起動するだけ。ChatGPT/
 *    Gemini APIをPOI内部へ新たに組み込むものではなく、起動後の会話内容も
 *    POIは一切取得・保存・監視しない。
 * 2. 「覚えて」依頼（[extractRememberContent]） — マリたん専用Memory
 *    （[MariTanMemoryStore]、黒猫AIの会話履歴とは別ファイル）へ、発言内容のみを
 *    追加保存する。全会話を自動保存することはしない。
 * 3. 「忘れて」依頼（[extractForgetQuery]） — 文字bigramの近さで最も一致する
 *    記憶を1件だけ削除する。候補が複数で曖昧な場合は何も削除しない。
 * いずれにも該当しない場合のみ、通常のGemini質問として扱い、保存済みMemoryを
 * （件数上限つきで）system_instructionへ添えて送る。
 *
 * 無料枠を使い切った場合(HTTP 429)はSULKING状態にするだけで、課金機能への自動
 * 移行は一切行わない。Gemini呼び出し自体が失敗した場合もERROR状態を短く見せる
 * だけで、詳細なエラー内容は画面に出さない（実機調査が要る場合はlogcatの
 * MariTanGeminiタグで追える — [GeminiSearchService]参照）。
 *
 * 音声合成の方式: Android標準のTextToSpeech（端末内蔵、APIキー不要・通信不要・
 * レート制限なし）を採用した。Google AI Studioの無料枠一覧で確認したところ
 * Gemini 3.1 Flash TTSは無料枠が3 RPMしかなく、マリたんの主要機能である会話の
 * たびに毎回消費するには不安定すぎる。安定運用・無料運用を優先し、TextToSpeech
 * を選んだ。
 */
@Composable
private fun MariTanRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(MariTanState.IDLE) }

    // マリたんの声。画面が破棄される際は必ずshutdown()する（TextToSpeechは
    // ネイティブリソース/バックグラウンドサービス接続を持つため）。
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        lateinit var instance: TextToSpeech
        instance = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                instance.language = Locale.JAPAN
                // 子供っぽさ/甲高さを避けつつ、機械的にならない自然な速さ・高さ
                // ——端末デフォルトの日本語音声(多くの機種で女性声)をそのまま使う。
                instance.setPitch(1.0f)
                instance.setSpeechRate(1.0f)
            }
        }
        ttsEngine = instance
        onDispose {
            instance.stop()
            instance.shutdown()
            ttsEngine = null
        }
    }

    fun speak(text: String, onDone: () -> Unit) {
        val engine = ttsEngine
        if (engine == null) {
            onDone()
            return
        }
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    scope.launch { onDone() }
                }

                @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
                override fun onError(utteranceId: String?) {
                    scope.launch { onDone() }
                }
            },
        )
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mari_tan_answer")
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text.isNullOrBlank()) {
            state = MariTanState.IDLE
        } else {
            // Gemini APIを呼ぶ前に、ローカルだけで判定できる3種類の意図を優先的に
            // 処理する（クラス冒頭のコメント参照）。
            val appTarget = ExternalAiLauncher.detectTarget(text)
            val rememberContent = extractRememberContent(text)
            val forgetQuery = extractForgetQuery(text)
            when {
                appTarget != null -> {
                    val launched = ExternalAiLauncher.launch(context.applicationContext, appTarget)
                    val appName = ExternalAiLauncher.displayName(appTarget)
                    val reply = if (launched) "${appName}を開くにゃ" else "${appName}が見つからないにゃ"
                    state = MariTanState.SPEAKING
                    speak(reply) { state = MariTanState.IDLE }
                }
                rememberContent != null -> {
                    state = MariTanState.THINKING
                    scope.launch {
                        MariTanMemoryStore.remember(context.applicationContext, rememberContent)
                        state = MariTanState.SPEAKING
                        speak("覚えたにゃ♡") { state = MariTanState.IDLE }
                    }
                }
                forgetQuery != null -> {
                    state = MariTanState.THINKING
                    scope.launch {
                        val result = MariTanMemoryStore.forget(context.applicationContext, forgetQuery)
                        val reply = when (result) {
                            is ForgetResult.Removed -> "忘れたにゃ"
                            ForgetResult.Ambiguous -> "どれのことか分からなかったにゃ…もう少し詳しく言ってほしいにゃ"
                            ForgetResult.NotFound, ForgetResult.NothingStored -> "そんなこと覚えてないにゃ"
                        }
                        state = MariTanState.SPEAKING
                        speak(reply) { state = MariTanState.IDLE }
                    }
                }
                else -> {
                    state = MariTanState.THINKING
                    scope.launch {
                        val memories = MariTanMemoryStore.all(context.applicationContext).map { it.text }
                        val outcome = GeminiSearchService.ask(text, memories)
                        when (val answer = outcome.getOrNull()) {
                            is GeminiOutcome.Answer -> {
                                state = MariTanState.SPEAKING
                                speak(answer.text) { state = MariTanState.IDLE }
                            }
                            GeminiOutcome.QuotaExceeded -> state = MariTanState.SULKING
                            GeminiOutcome.NotConfigured -> state = MariTanState.NOT_CONFIGURED
                            null -> state = MariTanState.ERROR
                        }
                    }
                }
            }
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            state = MariTanState.LISTENING
            speechLauncher.launch(buildSpeechIntent())
        } else {
            state = MariTanState.IDLE
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.mari_tan),
            contentDescription = "マリたん",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    enabled = state != MariTanState.THINKING &&
                        state != MariTanState.LISTENING &&
                        state != MariTanState.SPEAKING,
                ) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        state = MariTanState.LISTENING
                        speechLauncher.launch(buildSpeechIntent())
                    } else {
                        micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = when (state) {
                MariTanState.IDLE -> "マリたん：タップして話しかけてにゃ"
                MariTanState.LISTENING -> "マリたん：聞いてるにゃ…"
                MariTanState.THINKING -> "マリたん：調べてるにゃ…"
                MariTanState.SPEAKING -> "マリたん：お話するにゃ…"
                MariTanState.SULKING -> "マリたん：今日はもう調べられないにゃ…（ふて寝中）"
                MariTanState.NOT_CONFIGURED -> "マリたん：まだ準備中にゃ"
                MariTanState.ERROR -> "マリたん：うまく聞こえなかったにゃ"
            },
            fontSize = 12.sp,
            color = AiInk.copy(alpha = 0.6f),
        )
    }

    // ERRORは一時的な状態表示 — 少し経ったら自動でIDLEへ戻す(再タップしなくても
    // 元の「タップして話しかけてにゃ」に戻る)。SULKING/NOT_CONFIGUREDは
    // タップし直すまでそのまま表示し続ける(状態として意味があるため)。
    LaunchedEffect(state) {
        if (state == MariTanState.ERROR) {
            delay(4000)
            if (state == MariTanState.ERROR) state = MariTanState.IDLE
        }
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
