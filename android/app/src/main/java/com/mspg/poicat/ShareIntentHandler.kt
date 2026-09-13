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

        val sharedUri: Uri? = if (mimeType?.startsWith("image/") == true) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        if (sharedText.isNullOrBlank() && sharedUri == null) return

        runCatching {
            val photoRepository = PhotoRepository(context)
            // #144: AiChatScreen.ktと同じく、この端末の現在の利用者
            // (RoomStore.displayName)を都度読めるラムダとしてCatBrainへ渡す。
            val roomStore = RoomStore(context)
            val catBrain = CatBrain(CatEventRepository(context), photoRepository) { roomStore.displayName }

            // 2. Import the photo (if any) and record the user message — same
            // shape as AiChatScreen.send(): text may be blank, photoIds may be empty.
            val photo: Photo? = sharedUri?.let { photoRepository.importFromUri(it, caption = null, albumName = null) }
            ChatRepository.addMessage(
                ChatMessage("user", sharedText.orEmpty(), System.currentTimeMillis(), photo?.let { listOf(it.id) } ?: emptyList()),
            )

            if (photo != null && sharedText.isNullOrBlank()) {
                // 3. A bare photo share, no caption: already saved to the album,
                // same as AiChatScreen's Phase B bare-photo send — nothing for
                // CatBrain to sort.
                return@runCatching
            }

            // 3. Same branching as send(): photo+caption vs. text-only.
            val reply = if (photo != null) {
                catBrain.respondToPhoto(sharedText.orEmpty(), photo)
            } else {
                catBrain.respond(sharedText!!)
            }

            // 4. Record the cat's reply.
            ChatRepository.addMessage(
                ChatMessage("assistant", reply.text, System.currentTimeMillis(), reply.photoIds),
            )
        }.onFailure {
            ChatRepository.addMessage(
                ChatMessage("assistant", "うまく受け取れなかったにゃ", System.currentTimeMillis()),
            )
        }

        // 5. Only now signal AppRoot to open 猫AI — after the message(s) it's
        // about to display already exist in ChatRepository.
        PendingNavigation.requestedTab = AppTab.AI
    }
}
