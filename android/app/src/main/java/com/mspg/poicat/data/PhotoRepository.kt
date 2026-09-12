package com.mspg.poicat.data

import android.content.Context
import android.net.Uri
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
 * Owns both the photo metadata (via [PhotoDao]) and the actual image files,
 * which live once under the app's private `files/photos/` directory — never
 * duplicated per album/schedule/memo. A [Uri] picked via the system Photo
 * Picker only grants transient read access, so the bytes are copied in here
 * immediately to guarantee the photo survives after the picker's URI
 * permission goes away.
 */
class PhotoRepository(private val context: Context) {
    private val dao = PhotoDatabase.get(context).photoDao()
    private val linkDao = PhotoDatabase.get(context).photoMemoLinkDao()

    // ルーム共有(4桁PIN)用のfire-and-forgetなFirestoreプッシュだけに使う —
    // CatEventRepository.syncScopeと同じ設計(ローカルが主、共有は後追い)。
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    /** Saves bytes already downloaded from the shared Drive album folder (room-share catalog
     * refresh) as a new local photo — already marked SYNCED with the given [driveFileId] since
     * it's already on Drive, so it's never re-uploaded from this device. */
    suspend fun importFromDrive(bytes: ByteArray, driveFileId: String): Photo = withContext(Dispatchers.IO) {
        val destFile = newPhotoFile()
        destFile.writeBytes(bytes)
        val photo = Photo(
            filePath = destFile.absolutePath,
            driveSyncStatus = Photo.DRIVE_SYNC_SYNCED,
            driveFileId = driveFileId,
        )
        photo.copy(id = dao.insert(photo))
    }

    /** Whether a photo with this Drive file id already exists locally — used to avoid
     * re-importing the same shared-Drive photo on every catalog refresh. */
    suspend fun byDriveFileId(driveFileId: String) = dao.byDriveFileId(driveFileId)

    /** Saves a [Photo] row for a file the camera already wrote (see [newCameraCaptureFile]). */
    suspend fun registerCapturedFile(file: File, caption: String?, albumName: String?): Photo = withContext(Dispatchers.IO) {
        val photo = Photo(filePath = file.absolutePath, caption = caption, albumName = albumName)
        photo.copy(id = dao.insert(photo))
    }

    suspend fun all() = dao.all()

    suspend fun byAlbum(album: String) = dao.byAlbum(album)

    suspend fun albumNames() = dao.albumNames()

    suspend fun byIds(ids: List<Long>) = if (ids.isEmpty()) emptyList() else dao.byIds(ids)

    suspend fun byAddedAtRange(start: Long, end: Long) = dao.byAddedAtRange(start, end)

    suspend fun searchByCaptionOrAlbum(keyword: String) = dao.searchByCaptionOrAlbum(keyword)

    /** Photos linked to the calendar day starting at [startOfDay] (inclusive) through [endOfDay] (inclusive). */
    suspend fun byLinkedDate(startOfDay: Long, endOfDay: Long) = dao.byLinkedDate(startOfDay, endOfDay)

    suspend fun updateDetails(photo: Photo, caption: String?, albumName: String?, linkedDate: Long?) {
        dao.update(photo.copy(caption = caption, albumName = albumName, linkedDate = linkedDate))
    }

    /** Marks a photo's Drive sync state (PENDING/SYNCING/FAILED — see [markDriveSynced] for
     * the success case). A no-op if the photo no longer exists (e.g. deleted mid-upload). */
    suspend fun markDriveSyncStatus(photoId: Long, status: String) = withContext(Dispatchers.IO) {
        val photo = dao.byIds(listOf(photoId)).firstOrNull() ?: return@withContext
        dao.update(photo.copy(driveSyncStatus = status))
    }

    /** Records a successful Drive upload's file id, so a later sync attempt for the same
     * photo can recognize it's already there instead of uploading a duplicate. */
    suspend fun markDriveSynced(photoId: Long, driveFileId: String) = withContext(Dispatchers.IO) {
        val photo = dao.byIds(listOf(photoId)).firstOrNull() ?: return@withContext
        dao.update(photo.copy(driveSyncStatus = Photo.DRIVE_SYNC_SYNCED, driveFileId = driveFileId))
    }

    /** Photos linked to a memo (any cat_events row), newest-added first — for the memo screen's photo strip. */
    suspend fun photosForMemo(eventId: Long): List<Photo> {
        val ids = linkDao.linksForEvent(eventId).map { it.photoId }
        return if (ids.isEmpty()) emptyList() else dao.byIds(ids)
    }

    /** Links an existing album photo to a memo. A no-op if already linked (never a duplicate row). */
    suspend fun linkToMemo(photo: Photo, eventId: Long) {
        if (!linkDao.exists(photo.id, eventId)) {
            linkDao.insert(PhotoMemoLink(photoId = photo.id, eventId = eventId))
        }
    }

    /** Unlinks a photo from one memo — the photo itself and its other links are untouched. */
    suspend fun unlinkFromMemo(photo: Photo, eventId: Long) {
        linkDao.deleteLink(photo.id, eventId)
    }

    /** Drops all memo links for an event — call when that memo itself is deleted, so no
     * dangling reference to it is left behind (the linked photos are not touched). */
    suspend fun unlinkAllForMemo(eventId: Long) {
        linkDao.deleteAllForEvent(eventId)
    }

    /**
     * 「×」削除 — 論理削除(tombstone)のみ。ローカルファイル・Google Drive原本のどちらも
     * 物理的には一切削除しない(ユーザー指示: Drive原本は絶対に削除しない)。deletedAtを
     * 立てるだけなので、一覧系クエリ([PhotoDao.all]等)から見えなくなるだけで、行自体は
     * 残る。あわせてこの写真を参照する既存のメモ紐付けも解除する(削除済み写真をメモの
     * 写真欄に残さないため)。
     *
     * このdriveFileIdが夫婦間で共有中(既にDriveへアップロード済み)だった場合は、
     * [RoomDriveTombstoneSync]経由でこの削除状態をパートナー端末にも伝える
     * (fire-and-forget、失敗してもこのローカル削除自体には影響しない) — これにより
     * パートナー端末側の同じ写真も非表示になり、かつ[RoomCatalogSync]によるDriveからの
     * 再取り込みで復活しなくなる。
     */
    suspend fun softDelete(photo: Photo) = withContext(Dispatchers.IO) {
        linkDao.deleteAllForPhoto(photo.id)
        dao.update(photo.copy(deletedAt = System.currentTimeMillis()))
        photo.driveFileId?.let { driveFileId ->
            syncScope.launch { RoomDriveTombstoneSync.pushTombstone(context.applicationContext, driveFileId) }
        }
    }
}
