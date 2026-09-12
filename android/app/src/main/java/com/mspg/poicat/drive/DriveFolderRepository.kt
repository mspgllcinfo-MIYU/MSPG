package com.mspg.poicat.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveFolder(val id: String, val name: String)
data class DriveUploadedFile(val id: String)
/** アップロード後にDrive側へ問い合わせて実際に保存された内容を検証するための情報。 */
data class DriveFileInfo(val id: String, val size: Long?, val mimeType: String?)
/** [listFiles]が返す、フォルダ内の1ファイルのメタデータ（バイナリ本体は含まない）。 */
data class DriveListedFile(val id: String, val name: String, val mimeType: String?)

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

    /**
     * 指定フォルダへファイルを1つアップロードする（multipart/related、metadata+content
     * を1リクエストで送る標準的なDrive v3アップロード方式）。ensureFolderで作成/取得
     * した「POI用/アルバム」「POI用/ファイル」フォルダはアプリ自身が作成したものなので
     * drive.fileスコープのまま子ファイルの作成が許可されている。
     *
     * 本文（metadata部＋バイナリ部）は送信前にByteArrayへ丸ごと組み立ててから
     * setFixedLengthStreamingMode()で送る — outputStreamへ書き込みながら送る方式だと
     * Content-Lengthが実際に送ったバイト数と食い違っていても気づけないため、事前に
     * 全体サイズを確定させてHttpURLConnection側にも明示的に伝える。
     */
    suspend fun uploadFile(
        accessToken: String,
        parentFolderId: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Result<DriveUploadedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val boundary = "poicat-${System.currentTimeMillis()}"
            val metadata = JSONObject().apply {
                put("name", displayName)
                put("mimeType", mimeType)
                put("parents", JSONArray().put(parentFolderId))
            }
            val body = ByteArrayOutputStream().apply {
                write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
                write(metadata.toString().toByteArray(Charsets.UTF_8))
                write("\r\n--$boundary\r\n".toByteArray(Charsets.UTF_8))
                write("Content-Type: $mimeType\r\n\r\n".toByteArray(Charsets.UTF_8))
                write(bytes)
                write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }.toByteArray()
            val url = "https://www.googleapis.com/upload/drive/v3/files" +
                "?uploadType=multipart&fields=${URLEncoder.encode("id", "UTF-8")}"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                // アップロードはメタデータ取得系のAPIより時間がかかりうるため、少し長めに取る。
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { out -> out.write(body) }
                val ok = connection.responseCode in 200..299
                val stream = if (ok) connection.inputStream else connection.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                check(ok) { "Drive upload error ${connection.responseCode}: $text" }
                DriveUploadedFile(id = JSONObject(text).getString("id"))
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * アップロード後の検証専用。fileIdが返っただけでは中身が正しく保存された保証には
     * ならないため、Drive側が実際に記録したsize/mimeTypeを問い合わせ、呼び出し側で
     * ローカルの元バイト数と突き合わせる。
     */
    suspend fun getFileInfo(accessToken: String, fileId: String): Result<DriveFileInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$API_BASE/${URLEncoder.encode(fileId, "UTF-8")}" +
                    "?fields=${URLEncoder.encode("id,size,mimeType", "UTF-8")}"
                val response = request(url, "GET", accessToken, body = null)
                val obj = JSONObject(response)
                DriveFileInfo(
                    id = obj.getString("id"),
                    size = if (obj.has("size")) obj.optString("size").toLongOrNull() else null,
                    mimeType = if (obj.has("mimeType")) obj.getString("mimeType") else null,
                )
            }
        }

    /**
     * 指定フォルダ直下のファイル（サブフォルダは除く）一覧を取得する。ルーム共有
     * （4桁PIN）機能が、パートナー端末が同じ共有Driveフォルダへアップロードした
     * 写真/ファイルをこちら側のローカルRoom DBへも取り込むために使う — 取り込み済み
     * かどうかの判定はこの一覧のidと、ローカル側に保存済みのdriveFileIdを比較して行う
     * （呼び出し側の責務）。
     */
    suspend fun listFiles(accessToken: String, parentFolderId: String): Result<List<DriveListedFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = "'${escapeQueryValue(parentFolderId)}' in parents and trashed=false " +
                    "and mimeType != '$FOLDER_MIME'"
                val url = "$API_BASE?q=${URLEncoder.encode(query, "UTF-8")}" +
                    "&fields=${URLEncoder.encode("files(id,name,mimeType)", "UTF-8")}"
                val response = request(url, "GET", accessToken, body = null)
                val files = JSONObject(response).optJSONArray("files") ?: JSONArray()
                (0 until files.length()).map { i ->
                    val obj = files.getJSONObject(i)
                    DriveListedFile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        mimeType = if (obj.has("mimeType")) obj.getString("mimeType") else null,
                    )
                }
            }
        }

    /** [fileId]の実バイトをダウンロードする（`alt=media`）。ルーム共有でパートナー端末が
     * アップロードした写真/ファイルを、こちら側のローカルストレージへも保存するために使う。 */
    suspend fun downloadFile(accessToken: String, fileId: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$API_BASE/${URLEncoder.encode(fileId, "UTF-8")}?alt=media"
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 30_000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                    val ok = connection.responseCode in 200..299
                    check(ok) {
                        val text = BufferedReader(InputStreamReader(connection.errorStream, Charsets.UTF_8)).use { it.readText() }
                        "Drive download error ${connection.responseCode}: $text"
                    }
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
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
