package com.mspg.poicat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.mspg.poicat.room.RoomDriveTombstoneSync
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns both the metadata (via [StoredFileDao]) and the actual bytes of files
 * received through Android's share sheet — PDFs, Word/Excel/PowerPoint
 * documents, plain text, or any other non-image type. Copied into app-private
 * storage immediately (mirroring [PhotoRepository]) since a shared [Uri]'s
 * read permission is only transient. Stored under `filesDir/files/` — regular
 * internal storage, not the cache directory, so nothing here is ever
 * auto-deleted by the system or by this app; files persist until the user
 * explicitly deletes them from the ファイル tab.
 */
class FileRepository(private val context: Context) {
    private val dao = FileDatabase.get(context).storedFileDao()

    // ルーム共有(4桁PIN)用のfire-and-forgetなFirestoreプッシュだけに使う —
    // PhotoRepository.syncScopeと同じ設計。
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val filesDirForShared: File by lazy {
        File(context.filesDir, "files").apply { mkdirs() }
    }

    /** Copies the shared file's bytes into app storage and saves a new [StoredFile] row for it. */
    suspend fun importFromUri(uri: Uri, mimeType: String?): StoredFile = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri) ?: guessFileName(mimeType)
        // The on-disk file name only ever uses a sanitized version of the shared
        // display name — a sharing app fully controls that string, and letting it
        // flow unsanitized into a File(parent, child) path could otherwise escape
        // filesDirForShared (e.g. a name containing "../"). The *unsanitized*
        // original is kept in StoredFile.fileName for accurate display.
        val destFile = File(
            filesDirForShared,
            "file_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_${sanitizeForFileName(displayName)}",
        )
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("could not open shared file")
        input.use { stream -> destFile.outputStream().use { out -> stream.copyTo(out) } }
        val file = StoredFile(
            filePath = destFile.absolutePath,
            fileName = displayName,
            mimeType = mimeType ?: "application/octet-stream",
        )
        file.copy(id = dao.insert(file))
    }

    suspend fun all() = dao.all()

    /** Saves bytes already downloaded from the shared Drive file folder (room-share catalog
     * refresh) as a new local file — already marked SYNCED with the given [driveFileId] since
     * it's already on Drive, so it's never re-uploaded from this device. Keeps the original
     * [fileName]/[mimeType] exactly as reported by Drive, same as a locally-shared file. */
    suspend fun importFromDrive(bytes: ByteArray, driveFileId: String, fileName: String, mimeType: String): StoredFile =
        withContext(Dispatchers.IO) {
            val destFile = File(
                filesDirForShared,
                "file_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_${sanitizeForFileName(fileName)}",
            )
            destFile.writeBytes(bytes)
            val file = StoredFile(
                filePath = destFile.absolutePath,
                fileName = fileName,
                mimeType = mimeType,
                driveSyncStatus = StoredFile.DRIVE_SYNC_SYNCED,
                driveFileId = driveFileId,
            )
            file.copy(id = dao.insert(file))
        }

    /** Whether a file with this Drive file id already exists locally — used to avoid
     * re-importing the same shared-Drive file on every catalog refresh. */
    suspend fun byDriveFileId(driveFileId: String) = dao.byDriveFileId(driveFileId)

    /** Marks a file's Drive sync state (PENDING/SYNCING/FAILED — see [markDriveSynced] for
     * the success case). A no-op if the file no longer exists (e.g. deleted mid-upload). */
    suspend fun markDriveSyncStatus(fileId: Long, status: String) = withContext(Dispatchers.IO) {
        val file = dao.byIds(listOf(fileId)).firstOrNull() ?: return@withContext
        dao.update(file.copy(driveSyncStatus = status))
    }

    /** Records a successful Drive upload's file id, so a later sync attempt for the same
     * file can recognize it's already there instead of uploading a duplicate. */
    suspend fun markDriveSynced(fileId: Long, driveFileId: String) = withContext(Dispatchers.IO) {
        val file = dao.byIds(listOf(fileId)).firstOrNull() ?: return@withContext
        dao.update(file.copy(driveSyncStatus = StoredFile.DRIVE_SYNC_SYNCED, driveFileId = driveFileId))
    }

    /**
     * 「×」削除 — [com.mspg.poicat.data.PhotoRepository.softDelete]と同じ論理削除
     * (tombstone)のみ。ローカルファイル・Google Drive原本のどちらも物理削除しない。
     * driveFileIdが夫婦間で共有中だった場合は、その削除状態を[RoomDriveTombstoneSync]
     * 経由でパートナー端末にも伝える(fire-and-forget)。
     */
    suspend fun softDelete(file: StoredFile) = withContext(Dispatchers.IO) {
        dao.update(file.copy(deletedAt = System.currentTimeMillis()))
        file.driveFileId?.let { driveFileId ->
            syncScope.launch { RoomDriveTombstoneSync.pushTombstone(context.applicationContext, driveFileId) }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun guessFileName(mimeType: String?): String {
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (extension != null) "file.$extension" else "file"
    }

    private fun sanitizeForFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifBlank { "file" }
}
