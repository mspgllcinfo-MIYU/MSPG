package com.mspg.poicat.drive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
 *
 * 実機テストで "Can't access your Google Account / allowing cookie access" エラーが
 * 発生した原因：Android WebViewはLollipop以降を対象とするアプリでサードパーティ
 * Cookie・DOM storageをデフォルトで無効化しており、明示的に有効化しないとGoogleの
 * アカウント確認（apis.google.com側からaccounts.google.comを参照する、まさに
 * サードパーティ文脈）が機能しない。加えてGoogleのサインイン画面は window.open() で
 * ポップアップを開くことがあり、素のWebViewはポップアップを開けず何も起きないように
 * 見えることがある — WebChromeClient.onCreateWindow/onCloseWindowでポップアップ用の
 * 2枚目のWebViewを用意して対応する。
 */
class DrivePickerActivity : ComponentActivity() {

    private lateinit var mainWebView: WebView

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
    private fun newConfiguredWebView(): WebView {
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        // Googleのアカウント確認・セッション保持に必要 — 両方ともAndroid WebViewの
        // デフォルトではオフになっている。
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        return webView
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN)
        val apiKey = intent.getStringExtra(EXTRA_API_KEY)
        if (accessToken == null || apiKey == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        mainWebView = newConfiguredWebView()
        mainWebView.addJavascriptInterface(JsBridge(), "AndroidBridge")
        mainWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // JSONObject.quote() gives a properly escaped/quoted JS string literal —
                // safe against any character an access token or API key could contain.
                view.evaluateJavascript(
                    "initPicker(${JSONObject.quote(accessToken)}, ${JSONObject.quote(apiKey)});",
                    null,
                )
            }
        }
        mainWebView.webChromeClient = object : WebChromeClient() {
            // Googleのサインイン/アカウント確認がwindow.open()でポップアップを開いた
            // ときに呼ばれる。ポップアップ用の2枚目のWebViewを画面に表示し、閉じられ
            // たら（window.close()）メインのPicker画面へ戻す。
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val popup = newConfiguredWebView()
                popup.webViewClient = WebViewClient()
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView) {
                        setContentView(mainWebView)
                    }
                }
                setContentView(popup)

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        mainWebView.loadUrl("file:///android_asset/picker.html")
        setContentView(mainWebView)
    }

    companion object {
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }
}
