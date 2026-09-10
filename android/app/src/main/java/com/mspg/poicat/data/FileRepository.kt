package com.mspg.poicat.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

    /** Deletes the row and its backing file — the file is only ever referenced by this
     * one row, and no other data (photos, schedules, tasks, memos) references it. */
    suspend fun delete(file: StoredFile) = withContext(Dispatchers.IO) {
        dao.delete(file)
        runCatching { File(file.filePath).delete() }
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
