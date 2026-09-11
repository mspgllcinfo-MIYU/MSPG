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

        val sharedUri: Uri? = if (mimeType?.startsWith("image/") == true) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        if (sharedText.isNullOrBlank() && sharedUri == null) return

        runCatching {
            val photoRepository = PhotoRepository(context)
            val catBrain = CatBrain(CatEventRepository(context), photoRepository)

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
