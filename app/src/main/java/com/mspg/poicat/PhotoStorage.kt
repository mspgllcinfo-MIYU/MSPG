package com.mspg.poicat

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * フォトピッカーが返すUriは一時的で、アプリ再起動後は読めなくなることがある。
 * 選択直後にアプリ専用ストレージへコピーし、以後もずっと読めるUriへ差し替える。
 */
object PhotoStorage {
    fun copyToAppStorage(context: Context, sourceUri: Uri): Uri? = runCatching {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        val input = context.contentResolver.openInputStream(sourceUri) ?: return null
        input.use { source -> file.outputStream().use { output -> source.copyTo(output) } }
        Uri.fromFile(file)
    }.getOrNull()
}
