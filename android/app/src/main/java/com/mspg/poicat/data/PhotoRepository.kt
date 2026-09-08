package com.mspg.poicat.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns both the photo metadata (via [PhotoDao]) and the actual image files,
 * which live once under the app's private `files/photos/` directory — never
 * duplicated per album/schedule/memo. A [Uri] picked via the system Photo
 * Picker only grants transient read access, so the bytes are copied in here
 * immediately to guarantee the photo survives after the picker's URI
 * permission goes away.
 */
class PhotoRepository(private val context: Context) {
    private val dao = PhotoDatabase.get(context).photoDao()

    private val photoDir: File by lazy {
        File(context.filesDir, "photos").apply { mkdirs() }
    }

    private fun newPhotoFile(): File = File(photoDir, "photo_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")

    /** Copies the picked image's bytes into app storage and saves a new [Photo] row for it. */
    suspend fun importFromUri(uri: Uri, caption: String?, albumName: String?): Photo = withContext(Dispatchers.IO) {
        val destFile = newPhotoFile()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("could not open picked photo")
        input.use { stream -> destFile.outputStream().use { out -> stream.copyTo(out) } }
        val photo = Photo(filePath = destFile.absolutePath, caption = caption, albumName = albumName)
        photo.copy(id = dao.insert(photo))
    }

    /** A fresh, not-yet-existing file for the camera to write a capture into. */
    fun newCameraCaptureFile(): File = newPhotoFile()

    /** Saves a [Photo] row for a file the camera already wrote (see [newCameraCaptureFile]). */
    suspend fun registerCapturedFile(file: File, caption: String?, albumName: String?): Photo = withContext(Dispatchers.IO) {
        val photo = Photo(filePath = file.absolutePath, caption = caption, albumName = albumName)
        photo.copy(id = dao.insert(photo))
    }

    suspend fun all() = dao.all()

    suspend fun byAlbum(album: String) = dao.byAlbum(album)

    suspend fun albumNames() = dao.albumNames()

    suspend fun byEvent(eventId: Long) = dao.byEvent(eventId)

    suspend fun updateDetails(photo: Photo, caption: String?, albumName: String?) {
        dao.update(photo.copy(caption = caption, albumName = albumName))
    }

    /** Unlinks a photo from an event without touching the photo/album itself — used when the
     * linked schedule/memo is deleted, so the photo stays in the album. */
    suspend fun unlinkFromEvent(photo: Photo) {
        dao.update(photo.copy(eventId = null))
    }

    /** Deletes the row and its backing file. The file is only ever referenced by this one row,
     * so there's no other place it needs to be cleaned up from. */
    suspend fun delete(photo: Photo) = withContext(Dispatchers.IO) {
        dao.delete(photo)
        runCatching { File(photo.filePath).delete() }
    }
}
