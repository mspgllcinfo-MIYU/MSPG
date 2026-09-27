package com.mspg.poicat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.mspg.poicat.brain.CatBrain
import com.mspg.poicat.data.CatEventRepository
import com.mspg.poicat.data.FileRepository
import com.mspg.poicat.data.Photo
import com.mspg.poicat.data.PhotoRepository
import com.mspg.poicat.drive.FileDriveSync
import com.mspg.poicat.maps.SharedLocationDetector
import com.mspg.poicat.room.RoomStore

/**
 * Handles an incoming ACTION_SEND intent (share-to-PoiCat from another app),
 * reusing exactly the same CatBrain/ChatRepository/PhotoRepository entry
 * points AiChatScreen's own send() uses for typed input — no separate
 * classification logic for shared content.
 *
 * Runs entirely outside Compose: ChatRepository's message lists are already
 * globally observed SnapshotStateLists, so writing into them here is enough
 * for the 猫AI screen to show the result the moment it's opened. The only
 * thing handed back into the Compose tree is the one-shot "open 猫AI" signal
 * via PendingNavigation, set last so navigation never races the write.
 */
object ShareIntentHandler {
    /** [context] is required to be an [Activity] (not just applicationContext) because the
     * file-share branch needs one for [FileDriveSync]'s silent Drive-authorization check. */
    suspend fun handle(context: Activity, intent: Intent) {
        // 1. Extract whatever was shared.
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val mimeType = intent.type

        // A general file share (PDF/Word/Excel/PowerPoint/plain text/anything else
        // that isn't an image) — handled entirely separately from the text/photo
        // flow below, and never touches CatBrain/ChatRepository/PendingNavigation.
        // Checked first and returns immediately, so it can never affect the existing
        // image/text branches; any accompanying EXTRA_TEXT caption is intentionally
        // not processed here (mirrors the existing "bare photo" case below: nothing
        // for CatBrain to sort out of a file with no further text-based instruction).
        if (mimeType != null && !mimeType.startsWith("image/")) {
            val fileUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (fileUri != null) {
                runCatching {
                    val fileRepository = FileRepository(context)
                    val storedFile = fileRepository.importFromUri(fileUri, mimeType)
                    // ローカル保存は上の行で既に完了済み — この先のDriveアップロード
                    // 試行が何であれ、ここまでの結果には影響しない（PhotoDriveSyncと
                    // 同じ考え方）。
                    FileDriveSync.syncNewFile(context, fileRepository, storedFile)
                }
                return
            }
        }

        // #148 Maps-1B: 地図/場所の共有(Google Maps/Gemini等からのURL共有)を、
        // この先のcatBrain.respond()へ渡す前にここで検出して分岐する。respond()
        // には「解釈できなかった入力を何であれメモとして保存する」という最終
        // フォールバックがあるため、渡してしまうとGoogle Mapsのリンクが確認
        // なしでそのままメモ化されてしまう(実際、この分岐を入れる前は起きて
        // いた)。共有元がGoogle MapsかGeminiかを区別する必要はなく、共有された
        // テキストにGoogle Maps系のURLが含まれているかどうかだけを見る
        // ([SharedLocationDetector]参照)。
        //
        // ここではCatEvent/メモをまだ一切作らない — PendingSharedLocation
        // (PendingNavigationと同じ一度きりのstateパターン)へ生のテキストを
        // 渡すだけで、AppRootが表示する確認ダイアログでユーザーが選んだ結果
        // としてのみ書き込みが発生する。ChatRepositoryへの記録やPendingNavigation
        // による猫AIタブへの遷移も行わない — 地図共有はこれまでの黒猫AIチャット
        // の会話履歴とは別扱いにする。
        if (sharedText != null && SharedLocationDetector.looksLikeLocationShare(sharedText)) {
            PendingSharedLocation.pending = sharedText
            return
        }

        // #POI画像共有3択: image/*の共有は、EXTRA_TEXTの有無に一切関係なく、まず
        // 「予定を読み取る/アルバムに保存/猫AIに送る」をユーザーに選んでもらう
        // (PendingImageShareChoice、AppRootが表示する確認ダイアログ)。以前は
        // 「キャプション無しならOCR、キャプション有りなら猫AIチャット」という
        // EXTRA_TEXTの有無だけでの自動判定だったが、共有元アプリがユーザーの意図と
        // 無関係にEXTRA_TEXTへ文字列を自動付与するケースがあり、OCRルートに入れない
        // 実機不具合が確認されたため廃止した。ここではPhoto DBへの行を一切作らない
        // (一時コピーのみ) — 共有元Uriの読み取り権限がユーザーの選択待ちの間に
        // 失効しても影響しないよう、選択肢を出す前に安全な一時ファイルへコピーして
        // おく。ユーザーがキャンセルした場合、POIの正式データには何も残らない。
        if (mimeType?.startsWith("image/") == true) {
            val imageUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (imageUri != null) {
                runCatching {
                    val tempFile = PhotoRepository(context).copyUriToTempFile(imageUri)
                    PendingImageShareChoice.pending = PendingImageShareChoice.Data(tempFile, sharedText)
                }
            }
            return
        }

        // ここに到達するのはテキストのみの共有(画像も、Mapsの場所らしいURLも無い場合)。
        // 従来通り黒猫AIチャットへそのまま渡す — この経路は画像共有3択の対象外で、
        // 挙動を変更していない。
        if (sharedText.isNullOrBlank()) return
        runCatching {
            val roomStore = RoomStore(context)
            val catBrain = CatBrain(CatEventRepository(context), PhotoRepository(context)) { roomStore.displayName }
            ChatRepository.addMessage(ChatMessage("user", sharedText, System.currentTimeMillis()))
            val reply = catBrain.respond(sharedText)
            ChatRepository.addMessage(
                ChatMessage("assistant", reply.text, System.currentTimeMillis(), reply.photoIds),
            )
        }.onFailure {
            ChatRepository.addMessage(
                ChatMessage("assistant", "うまく受け取れなかったにゃ", System.currentTimeMillis()),
            )
        }
        PendingNavigation.requestedTab = AppTab.AI
    }

    /**
     * #POI画像共有3択「猫AIに送る」の実行部分。既存の黒猫AI画像共有ルート
     * (catBrain.respondToPhoto→ChatRepository→PendingNavigation)と同じ処理を
     * そのまま使う。[photo]は呼び出し元([AppRoot]の確認ダイアログのコールバック)が
     * 既に確定保存済みのPhotoで、ここでは新たにPhoto行を作らない(二重import
     * しない)。[caption]が空ならCatBrainを呼ばず写真だけ記録する(#144フェーズB
     * の「キャプション無し画像送信」と同じ挙動)。
     */
    suspend fun sendPhotoToChat(context: Activity, photo: Photo, caption: String) {
        val trimmed = caption.trim()
        ChatRepository.addMessage(
            ChatMessage("user", trimmed, System.currentTimeMillis(), listOf(photo.id)),
        )
        if (trimmed.isBlank()) {
            PendingNavigation.requestedTab = AppTab.AI
            return
        }
        runCatching {
            val photoRepository = PhotoRepository(context)
            val roomStore = RoomStore(context)
            val catBrain = CatBrain(CatEventRepository(context), photoRepository) { roomStore.displayName }
            val reply = catBrain.respondToPhoto(trimmed, photo)
            ChatRepository.addMessage(
                ChatMessage("assistant", reply.text, System.currentTimeMillis(), reply.photoIds),
            )
        }.onFailure {
            ChatRepository.addMessage(
                ChatMessage("assistant", "うまく受け取れなかったにゃ", System.currentTimeMillis()),
            )
        }
        PendingNavigation.requestedTab = AppTab.AI
    }
}
