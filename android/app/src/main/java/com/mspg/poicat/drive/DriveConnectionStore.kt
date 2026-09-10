package com.mspg.poicat.drive

import android.content.Context

/**
 * Drive連携の小さな永続状態 — 選択済みフォルダのID/名前のみを持つ、既存のRoom DB
 * （AppDatabase/PhotoDatabase/FileDatabase）とは完全に独立したSharedPreferences。
 * サインイン状態そのものはFirebaseAuthが既に永続化しているため、ここでは二重管理しない。
 */
class DriveConnectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var albumFolderId: String?
        get() = prefs.getString(KEY_ALBUM_FOLDER_ID, null)
        set(value) { prefs.edit().putString(KEY_ALBUM_FOLDER_ID, value).apply() }

    var albumFolderName: String?
        get() = prefs.getString(KEY_ALBUM_FOLDER_NAME, null)
        set(value) { prefs.edit().putString(KEY_ALBUM_FOLDER_NAME, value).apply() }

    var fileFolderId: String?
        get() = prefs.getString(KEY_FILE_FOLDER_ID, null)
        set(value) { prefs.edit().putString(KEY_FILE_FOLDER_ID, value).apply() }

    var fileFolderName: String?
        get() = prefs.getString(KEY_FILE_FOLDER_NAME, null)
        set(value) { prefs.edit().putString(KEY_FILE_FOLDER_NAME, value).apply() }

    companion object {
        private const val PREFS_NAME = "drive_connection"
        private const val KEY_ALBUM_FOLDER_ID = "album_folder_id"
        private const val KEY_ALBUM_FOLDER_NAME = "album_folder_name"
        private const val KEY_FILE_FOLDER_ID = "file_folder_id"
        private const val KEY_FILE_FOLDER_NAME = "file_folder_name"
    }
}
