package com.mspg.poicat.drive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * 既存の「POI用」配下フォルダ（アルバム/ファイル）をdrive.fileスコープで使える
 * ようにするための、一度きりのフォルダ選択画面。
 *
 * drive.fileスコープは、アプリが作成したファイルか、ユーザーがPicker等で明示的に
 * 選んだファイル／フォルダにしかアクセスできない — 既存フォルダをfiles.listで
 * 探すことはできないため、Googleの公式Picker APIでの選択が必須。Google Picker APIは
 * JS製ウィジェットでネイティブAndroid版が無いため、WebViewへ埋め込んで使う（Web版の
 * Driveアプリ等でも使われている、Google公式にサポートされている組み込み方式）。
 */
class DrivePickerActivity : ComponentActivity() {

    private inner class JsBridge {
        @JavascriptInterface
        fun onFolderPicked(id: String, name: String) {
            runOnUiThread {
                setResult(
                    Activity.RESULT_OK,
                    Intent().apply {
                        putExtra(EXTRA_FOLDER_ID, id)
                        putExtra(EXTRA_FOLDER_NAME, name)
                    },
                )
                finish()
            }
        }

        @JavascriptInterface
        fun onCancelled() {
            runOnUiThread {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN)
        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        if (accessToken == null || apiKey == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // JSONObject.quote() gives a properly escaped/quoted JS string literal —
                // safe against any character an access token or API key could contain.
                view.evaluateJavascript(
                    "initPicker(${JSONObject.quote(accessToken)}, ${JSONObject.quote(apiKey)});",
                    null,
                )
            }
        }
        webView.loadUrl("file:///android_asset/picker.html")
        setContentView(webView)
    }

    companion object {
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }
}
