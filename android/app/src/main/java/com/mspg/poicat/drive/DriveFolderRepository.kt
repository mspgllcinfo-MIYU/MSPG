package com.mspg.poicat.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveFolder(val id: String, val name: String)

/**
 * 「POI用」配下フォルダ（アルバム/ファイル）へのアクセス方式。
 *
 * 当初はGoogle Picker（WebView埋め込み）で既存の手動作成フォルダを選ばせる設計
 * だったが、実機テストでGoogleのWebViewセキュリティ制約に阻まれることが判明した
 * （Cookieアクセス拒否→修正後は403）。これはWebView側の設定不足ではなく、
 * Google自身が2026年時点でWebView内でのPicker利用を非推奨としており、公式な
 * 代替は実際のWebバックエンド経由でのフルブラウザ遷移＋ディープリンク復帰という、
 * 新たなホスティング環境が要る構成のみ。
 *
 * そのため、WebView/Pickerを完全に廃止し、drive.fileスコープが常に許可している
 * 「アプリが作成したファイルには、アプリ自身がその後もfiles.list/files.createで
 * アクセスできる」という性質だけを使う設計に変更した。初回はアプリが「POI用」
 * フォルダ（と「アルバム」「ファイル」子フォルダ）を作成し、以降は同じ名前で
 * 再検索して再利用する — Picker/WebViewは一切使わない、純粋なREST呼び出しのみ。
 *
 * 共有Googleアカウントを2台で使う前提のため、片方の端末が作ったフォルダはもう
 * 一方の端末でも同じアカウント＋同じアプリのOAuth認可を通じてfiles.listで
 * 再発見できる（drive.fileのアクセス許可はアカウント×アプリ単位で記録され、
 * 端末単位ではないため）。
 *
 * 注意：ユーザーが以前手動で作成した「POI用」フォルダとは別物になる — アプリは
 * それを検索・使用できない（drive.fileスコープでは見えないため）。既存フォルダの
 * 中身を引き継ぎたい場合は、Googleドライブアプリ側で手動コピーしてもらう。
 */
object DriveFolderRepository {
    private const val API_BASE = "https://www.googleapis.com/drive/v3/files"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    suspend fun ensureFolder(accessToken: String, name: String, parentId: String?): Result<DriveFolder> =
        withContext(Dispatchers.IO) {
            runCatching {
                findFolder(accessToken, name, parentId) ?: createFolder(accessToken, name, parentId)
            }
        }

    private fun findFolder(accessToken: String, name: String, parentId: String?): DriveFolder? {
        val query = buildString {
            append("mimeType='$FOLDER_MIME' and name='${escapeQueryValue(name)}' and trashed=false")
            if (parentId != null) append(" and '${escapeQueryValue(parentId)}' in parents")
        }
        val url = "$API_BASE?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&fields=${URLEncoder.encode("files(id,name)", "UTF-8")}"
        val response = request(url, "GET", accessToken, body = null)
        val files = JSONObject(response).optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return null
        val first = files.getJSONObject(0)
        return DriveFolder(id = first.getString("id"), name = first.getString("name"))
    }

    private fun createFolder(accessToken: String, name: String, parentId: String?): DriveFolder {
        val body = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) put("parents", JSONArray().put(parentId))
        }
        val response = request(
            "$API_BASE?fields=${URLEncoder.encode("id,name", "UTF-8")}",
            "POST",
            accessToken,
            body = body.toString(),
        )
        val obj = JSONObject(response)
        return DriveFolder(id = obj.getString("id"), name = obj.getString("name"))
    }

    // Drive APIのqueryパラメータ内の文字列リテラルは ' と \ をエスケープする必要がある。
    private fun escapeQueryValue(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    private fun request(urlString: String, method: String, accessToken: String, body: String?): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        try {
            // HttpURLConnectionはデフォルトでタイムアウト無制限 — 万一応答が返らない
            // 場合でも呼び出し元(ConnectionScreen)のisBusyが永久に戻らなくなるのを
            // 防ぐため、明示的に上限を設ける。
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val ok = connection.responseCode in 200..299
            val stream = if (ok) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            check(ok) { "Drive API error ${connection.responseCode}: $text" }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
