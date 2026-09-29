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
import androidx.compose.material3.AlertDialog
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
import com.mspg.poicat.recovery.AlbumRecoveryExecutor
import com.mspg.poicat.recovery.AlbumRecoveryScanner
import kotlinx.coroutines.launch

// このファイル専用の色 — 既存の各画面と同じトーンをこのファイル内だけで再現。
private val RecInk = Color(0xFF201E1D)
private val RecCard = Color(0xFFEFE7DE)
private val RecGold = Color(0xFFC9A66B)

/**
 * #POIアルバム復旧: みゆタン/かっちゃん2台分の写真について、ローカル/Google Drive/
 * Firestoreのどこに何が残っているかを非破壊でスキャンして件数を表示するプレビュー
 * (Phase 1、[AlbumRecoveryScanner]は引き続き読み取り専用のまま無変更)に加え、
 * 見つかった実ファイルをCOPY＋INSERTだけで救出する「全部復活」機能を持つ。
 *
 * 【「全部復活」の絶対原則】①再スキャン→②復旧予定件数の表示→③ユーザーの明示的な
 * 確認(ダイアログの「復旧する」)→④復旧実行→⑤自動で再スキャン→⑥結果表示、の順を
 * 必ず経由し、ボタン1回のタップで即復旧が始まることはない。復旧処理自体は
 * [AlbumRecoveryExecutor]がCOPY＋INSERTのみで行い、DELETE/UPDATE/MOVE/上書きは
 * 一切ない — 削除・統合・整理の類のボタンは今回も無い。この画面を開いている間も
 * 既存アルバム機能([AlbumScreen])の一覧・追加・削除・Drive同期には一切触れない。
 */
@Composable
fun AlbumRecoveryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val photoRepository = remember { PhotoRepository(context.applicationContext) }

    var uiState by remember { mutableStateOf<RecoveryUiState>(RecoveryUiState.Scanning) }
    var restoreState by remember { mutableStateOf<RestoreUiState>(RestoreUiState.Idle) }

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

    // 「全部復活」①最新状態を再スキャン→②復旧予定件数を表示(確認ダイアログ)。
    // 実際の復旧([AlbumRecoveryExecutor.executeRecovery])はまだ一切実行しない —
    // ③のユーザー確認(ダイアログの「復旧する」ボタン)を必ず経由する。
    fun startRestorePlanning() {
        restoreState = RestoreUiState.Planning
        scope.launch {
            uiState = RecoveryUiState.Scanning
            val scanResult = runCatching { AlbumRecoveryScanner.scan(activity, photoRepository) }
            val preview = scanResult.getOrNull()
            if (preview == null) {
                uiState = RecoveryUiState.ScanFailed(
                    scanResult.exceptionOrNull()?.message ?: scanResult.exceptionOrNull()?.let { it::class.simpleName } ?: "unknown",
                )
                restoreState = RestoreUiState.Idle
                return@launch
            }
            uiState = RecoveryUiState.Done(preview)
            val plan = runCatching { AlbumRecoveryExecutor.buildPlan(preview, photoRepository) }
            restoreState = plan.fold(
                onSuccess = { RestoreUiState.PlanReady(it) },
                onFailure = { RestoreUiState.RestoreFailed(it.message ?: it::class.simpleName ?: "unknown") },
            )
        }
    }

    // ④復旧実行。②で確認した[plan]をそのまま渡す(表示した件数と実際に処理する対象を
    // 食い違わせないため)。⑤完了後は自動で再スキャンし、⑥結果はrestoreStateとして
    // 画面に残す(再スキャンはuiStateだけを更新するため、結果表示は消えない)。
    fun startRestoreExecution(plan: AlbumRecoveryExecutor.RecoveryPlan) {
        restoreState = RestoreUiState.Restoring
        scope.launch {
            val result = runCatching { AlbumRecoveryExecutor.executeRecovery(activity, photoRepository, plan) }
            restoreState = result.fold(
                onSuccess = { RestoreUiState.RestoreDone(it) },
                onFailure = { RestoreUiState.RestoreFailed(it.message ?: it::class.simpleName ?: "unknown") },
            )
            startScan()
        }
    }

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

        Spacer(Modifier.height(24.dp))
        Text(
            "見つかった実ファイルを、消さず・上書きせず、全部アルバムに救出するにゃ。" +
                "重複していても今回は消さないにゃ（整理は別の機会に行うにゃ）。",
            fontSize = 12.sp,
            color = RecInk.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { startRestorePlanning() },
            enabled = restoreState !is RestoreUiState.Planning && restoreState !is RestoreUiState.Restoring,
        ) { Text("全部復活") }

        when (val state = restoreState) {
            is RestoreUiState.Idle -> Unit
            is RestoreUiState.Planning -> {
                Spacer(Modifier.height(8.dp))
                Text("復旧予定を確認中…", color = RecGold, fontSize = 12.sp)
            }
            is RestoreUiState.PlanReady -> {
                // ③確認ダイアログ。ここでユーザーが「復旧する」を押すまで、実際の復旧
                // ([AlbumRecoveryExecutor.executeRecovery])は一切呼ばれない。
                AlertDialog(
                    onDismissRequest = { restoreState = RestoreUiState.Idle },
                    title = { Text("この件数を復活しますか？") },
                    text = {
                        Column {
                            Text("孤立ローカル：${state.plan.orphanLocalCount}件")
                            Text("自分Drive：${state.plan.ownDriveCount}件")
                            Text("パートナーDrive：${state.plan.partnerDriveCount}件")
                            Text("削除記録(tombstone)経由：${state.plan.tombstoneCount}件")
                            Text("softDelete経由：${state.plan.softDeleteCount}件")
                            Spacer(Modifier.height(8.dp))
                            Text("合計：${state.plan.totalCount}件", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "既存の写真・Drive原本・削除記録はどれも変更・削除しないにゃ。" +
                                    "新しいコピーを追加するだけにゃ。",
                                fontSize = 11.sp,
                                color = RecInk.copy(alpha = 0.6f),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { startRestoreExecution(state.plan) },
                            enabled = state.plan.totalCount > 0,
                        ) { Text("復旧する") }
                    },
                    dismissButton = {
                        TextButton(onClick = { restoreState = RestoreUiState.Idle }) { Text("キャンセル") }
                    },
                )
            }
            is RestoreUiState.Restoring -> {
                Spacer(Modifier.height(8.dp))
                Text("復旧中…", color = RecGold, fontSize = 12.sp)
            }
            is RestoreUiState.RestoreDone -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    "復旧結果 — 成功：${state.result.succeededCount}件 / 失敗：${state.result.failedCount}件",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = RecInk,
                )
                if (state.result.failedCount > 0) {
                    DetailToggle(state.result.outcomes.filter { !it.success }) { outcome ->
                        "${outcome.target}：${outcome.reason}"
                    }
                }
            }
            is RestoreUiState.RestoreFailed -> {
                Spacer(Modifier.height(8.dp))
                Text("復旧予定の確認に失敗したにゃ：${state.reason}", color = RecGold, fontSize = 12.sp)
            }
        }
    }
}

private sealed class RestoreUiState {
    object Idle : RestoreUiState()
    object Planning : RestoreUiState()
    data class PlanReady(val plan: AlbumRecoveryExecutor.RecoveryPlan) : RestoreUiState()
    object Restoring : RestoreUiState()
    data class RestoreDone(val result: AlbumRecoveryExecutor.RecoveryResult) : RestoreUiState()
    data class RestoreFailed(val reason: String) : RestoreUiState()
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
