package com.droiddeck.launcher.stores.xbox

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Microsoft's web player (xbox.com/play) in its own process and WebView data directory: the store sign-in
 * clears its own web data after each attempt, so the player keeps a separate Microsoft session. Signing out of
 * the store clears it ([clearWebSignIn]).
 */
class XboxCloudActivity : ComponentActivity() {
    private var web: WebView? = null
    private var custom: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var root: FrameLayout

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        Log.i(TAG, "WebView ${WebViewCompat.getCurrentWebViewPackage(this)?.versionName}")

        val view = WebView(this)
        web = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = chromeUserAgent(userAgentString)
        }
        // No "X-Requested-With: <package>" header: it marks the page as embedded in an app.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
        Log.i(TAG, "UA ${view.settings.userAgentString}")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"
            override fun onPageFinished(view: WebView, url: String) { Log.i(TAG, "page ${url.substringBefore('?')}") }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "console ${message.messageLevel()}: ${message.message().take(300)}")
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.i(TAG, "permission request ${request.resources.joinToString()} denied")
                request.deny()
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                custom?.let { root.removeView(it) }
                custom = view; customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                hideBars()
            }
            override fun onHideCustomView() {
                custom?.let { root.removeView(it) }
                custom = null; customCallback = null
            }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        if (savedInstanceState != null) view.restoreState(savedInstanceState)
        else view.loadUrl(startUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        web?.loadUrl(startUrl(intent))
    }

    private fun startUrl(intent: Intent): String =
        intent.getStringExtra("url")?.takeIf { url -> runCatching { java.net.URI(url) }.getOrNull()?.let {
            it.scheme == "https" && it.host == "www.xbox.com" && it.userInfo == null && it.port == -1 } == true } ?: START_URL

    // Without the WebView markers the page treats this as Chrome for Android.
    private fun chromeUserAgent(default: String): String =
        default.replace("; wv)", ")").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // A controller's unused B arrives as BACK; ask before leaving so a stray press never ends a stream.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            val fromController = event.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            when {
                custom != null -> customCallback?.onCustomViewHidden()
                fromController -> confirmLeave()
                web?.canGoBack() == true -> web?.goBack()
                else -> finish()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    private var leaving: android.app.AlertDialog? = null

    private fun confirmLeave() {
        if (leaving?.isShowing == true) return
        leaving = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Leave Xbox Cloud Gaming?")
            .setMessage("The stream ends when you leave.")
            .setPositiveButton("Leave") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onPause() { super.onPause(); CookieManager.getInstance().flush() }

    override fun onDestroy() {
        web?.let { root.removeView(it); it.stopLoading(); it.destroy() }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XboxCloud"
        const val START_URL = "https://www.xbox.com/play"

        fun intent(context: Context, url: String? = null): Intent = Intent(context, XboxCloudActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (url != null) putExtra("url", url) }

        const val PROCESS_SUFFIX = ":xboxcloud"
        const val WEBVIEW_SUFFIX = "xboxcloud"

        fun clearWebSignIn(context: Context) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses.orEmpty().filter { it.processName.endsWith(PROCESS_SUFFIX) }
                .forEach { android.os.Process.killProcess(it.pid) }
            val suffix = WEBVIEW_SUFFIX
            listOf(java.io.File(context.applicationInfo.dataDir, "app_webview_$suffix"), java.io.File(context.cacheDir.parentFile, "cache/webview_$suffix"))
                .forEach { runCatching { it.deleteRecursively() } }
        }
    }
}
