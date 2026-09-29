package com.mspg.poicat

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.recovery.AlbumRecoveryScanner
import kotlinx.coroutines.launch

// このファイル専用の色 — 既存の各画面と同じトーンをこのファイル内だけで再現。
private val RecInk = Color(0xFF201E1D)
private val RecCard = Color(0xFFEFE7DE)
private val RecGold = Color(0xFFC9A66B)

/**
 * #POIアルバム復旧調査 Phase 1: みゆタン/かっちゃん2台分の写真について、ローカル/
 * Google Drive/Firestoreのどこに何が残っているかを非破壊でスキャンし、件数だけを
 * 表示するプレビュー画面。
 *
 * 【重要】この画面(および[AlbumRecoveryScanner])には「復旧する」「削除する」
 * 「統合する」の類のボタンは一切無い — Phase 1の役割は「残っているものを全部
 * 見つけて件数を見せるだけ」であり、実際の復旧・削除・統合はまだ実装しない
 * (次のPhase以降で、ユーザーが個別に確認・承認した項目だけを対象に行う設計)。
 * この画面を開いている間も既存アルバム機能([AlbumScreen])の一覧・追加・削除・
 * Drive同期には一切触れない — 完全に独立した読み取り専用画面。
 */
@Composable
fun AlbumRecoveryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val photoRepository = remember { PhotoRepository(context.applicationContext) }

    var uiState by remember { mutableStateOf<RecoveryUiState>(RecoveryUiState.Scanning) }

    fun startScan() {
        uiState = RecoveryUiState.Scanning
        scope.launch {
            val result = runCatching { AlbumRecoveryScanner.scan(activity, photoRepository) }
            uiState = result.fold(
                onSuccess = { RecoveryUiState.Done(it) },
                onFailure = { RecoveryUiState.ScanFailed(it.message ?: it::class.simpleName ?: "unknown") },
            )
        }
    }

    LaunchedEffect(Unit) { startScan() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← もどる", color = RecInk) }
            Spacer(Modifier.width(4.dp))
            Text("データ復旧チェック", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = RecInk)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "この画面は写真の状態を確認するだけにゃ。何も変更・削除・アップロード・" +
                "ダウンロードは行わないにゃ。",
            fontSize = 12.sp,
            color = RecInk.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))

        when (val state = uiState) {
            is RecoveryUiState.Scanning -> Text("スキャン中…", color = RecGold)
            is RecoveryUiState.ScanFailed -> Text("スキャンに失敗したにゃ：${state.reason}", color = RecGold)
            is RecoveryUiState.Done -> RecoveryPreviewContent(state.preview)
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { startScan() }) { Text("再スキャン") }
    }
}

private sealed class RecoveryUiState {
    object Scanning : RecoveryUiState()
    data class ScanFailed(val reason: String) : RecoveryUiState()
    data class Done(val preview: AlbumRecoveryScanner.AlbumRecoveryPreview) : RecoveryUiState()
}

@Composable
private fun RecoveryPreviewContent(preview: AlbumRecoveryScanner.AlbumRecoveryPreview) {
    Column {
        SectionCard(title = "ローカル写真") {
            ScanStateLine("取得状況", preview.localPhotos)
            val entries = preview.localPhotos.successOrNull()
            if (entries != null) {
                CountLine("正常", entries.count { it.category == AlbumRecoveryScanner.LocalPhotoCategory.NORMAL })
                CountLine(
                    "孤立ローカルファイル(Room行なし)",
                    entries.count { it.category == AlbumRecoveryScanner.LocalPhotoCategory.ORPHAN_FILE },
                )
                CountLine(
                    "Roomのみ(実ファイルなし)",
                    entries.count { it.category == AlbumRecoveryScanner.LocalPhotoCategory.MISSING_FILE },
                )
                CountLine(
                    "softDelete済み(実ファイルあり・復旧候補)",
                    entries.count { it.category == AlbumRecoveryScanner.LocalPhotoCategory.SOFT_DELETED_FILE_PRESENT },
                )
                CountLine(
                    "その他の不整合",
                    entries.count { it.category == AlbumRecoveryScanner.LocalPhotoCategory.OTHER_INCONSISTENT },
                )
                DetailToggle(entries) { entry -> "${entry.category}：${entry.filePath}" }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "Google Drive（自分のフォルダ）") {
            ScanStateLine("取得状況", preview.ownDrivePhotos)
            preview.ownDrivePhotos.successOrNull()?.let { CountLine("ファイル件数", it.size) }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "Google Drive（パートナーのフォルダ）") {
            ScanStateLine("取得状況", preview.partnerDrivePhotos)
            preview.partnerDrivePhotos.successOrNull()?.let { CountLine("ファイル件数", it.size) }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "Firestore（夫婦間の共有情報）") {
            ScanStateLine("フォルダ接続情報(driveFolders)", preview.firestoreDriveFolders)
            preview.firestoreDriveFolders.successOrNull()?.let {
                CountLine("自分のalbumFolderId", if (it.ownAlbumFolderId != null) "接続済み" else "未接続")
                CountLine("パートナーのalbumFolderId件数", it.partnerAlbumFolderIds.size)
            }
            ScanStateLine("写真メタデータ(photoMetadata)", preview.firestorePhotoMetadata)
            preview.firestorePhotoMetadata.successOrNull()?.let { CountLine("件数", it.size) }
            ScanStateLine("削除記録(driveTombstones)", preview.firestoreTombstones)
            preview.firestoreTombstones.successOrNull()?.let { CountLine("件数", it.size) }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "重複候補（自動統合・自動削除は行いません）") {
            CountLine("グループ数(合計)", preview.duplicateCandidates.size)
            CountLine(
                "LEVEL1 driveFileId一致",
                preview.duplicateCandidates.count { it.confidence == AlbumRecoveryScanner.DuplicateConfidence.LEVEL1_DRIVE_ID },
            )
            CountLine(
                "LEVEL2 ハッシュ完全一致",
                preview.duplicateCandidates.count { it.confidence == AlbumRecoveryScanner.DuplicateConfidence.LEVEL2_HASH },
            )
            CountLine(
                "LEVEL3 サイズ+EXIF一致",
                preview.duplicateCandidates.count { it.confidence == AlbumRecoveryScanner.DuplicateConfidence.LEVEL3_SIZE_AND_EXIF },
            )
            CountLine(
                "LEVEL4 判断不能",
                preview.duplicateCandidates.count { it.confidence == AlbumRecoveryScanner.DuplicateConfidence.LEVEL4_UNKNOWN },
            )
            DetailToggle(preview.duplicateCandidates) { group -> "${group.confidence}：${group.members.joinToString(" / ")}" }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(RecCard)
            .padding(12.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RecInk)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun <T> ScanStateLine(label: String, state: AlbumRecoveryScanner.ScanState<T>) {
    val text = when (state) {
        is AlbumRecoveryScanner.ScanState.NotApplicable -> "対象外（未接続/未参加）"
        is AlbumRecoveryScanner.ScanState.AuthRequired -> "要確認（Drive認証を確認できず）"
        is AlbumRecoveryScanner.ScanState.Failed -> "取得失敗：${state.reason}"
        is AlbumRecoveryScanner.ScanState.Success -> "取得済み"
    }
    Text("$label：$text", fontSize = 12.sp, color = RecInk.copy(alpha = 0.8f))
}

@Composable
private fun CountLine(label: String, count: Int) {
    Text("　$label：${count}件", fontSize = 12.sp, color = RecInk.copy(alpha = 0.7f))
}

@Composable
private fun CountLine(label: String, text: String) {
    Text("　$label：$text", fontSize = 12.sp, color = RecInk.copy(alpha = 0.7f))
}

@Composable
private fun <T> DetailToggle(items: List<T>, describe: (T) -> String) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "詳細を閉じる" else "詳細を見る", color = RecGold, fontSize = 12.sp)
    }
    if (expanded) {
        items.forEach { item ->
            Text("・${describe(item)}", fontSize = 11.sp, color = RecInk.copy(alpha = 0.6f))
        }
    }
}
