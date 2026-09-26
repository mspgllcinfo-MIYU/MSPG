package com.mspg.poicat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports a copy of [sourceFile] (already living in this app's own private
 * storage — a photo or a file received via share) into the device's shared
 * storage, via MediaStore: Pictures/PoiCat for images, Downloads/PoiCat for
 * everything else. Scoped storage (Android 10+) means this never needs — and
 * never requests — any storage permission. A pre-existing item with the same
 * display name is never silently overwritten: MediaStore itself gives a
 * colliding insert a distinct name (e.g. "file (1).pdf") rather than
 * replacing what's already there.
 *
 * "保存期限なしの永続保存" inside PoiCat (see [com.mspg.poicat.data.PhotoRepository] /
 * [com.mspg.poicat.data.FileRepository]) and this on-demand export to the device's
 * own storage are deliberately separate: this function only ever copies out of
 * that already-permanent app storage, never moves or deletes from it.
 *
 * Android 9 (API 28) and below aren't supported — the MediaStore.Downloads
 * collection this relies on for non-image files doesn't exist before API 29 — and
 * this returns false there rather than requesting a legacy storage permission.
 */
suspend fun exportToDeviceStorage(
    context: Context,
    sourceFile: File,
    displayName: String,
    mimeType: String,
    isImage: Boolean,
): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false

    runCatching {
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relativeDir = if (isImage) {
            "${Environment.DIRECTORY_PICTURES}/PoiCat"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/PoiCat"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
        }

        val resolver = context.contentResolver
        val itemUri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        val output = resolver.openOutputStream(itemUri) ?: error("could not open output stream")
        output.use { out -> sourceFile.inputStream().use { input -> input.copyTo(out) } }
    }.isSuccess
}
