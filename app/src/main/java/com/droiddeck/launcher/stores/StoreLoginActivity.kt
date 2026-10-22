package com.droiddeck.launcher.stores

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.gog.GogAuth
import java.util.concurrent.atomic.AtomicBoolean

class StoreLoginActivity : ComponentActivity() {
    companion object {
        private const val TAG = "StoreLogin"
        const val EXTRA_STORE = "store"
        const val EXTRA_CLIENT_URL = "client_url"
        private const val KEY_STATE = "oauth_state"
        private const val KEY_SIGN_IN_CLEARED = "sign_in_cleared"
        @Volatile internal var floodColor: Int? = null
        internal fun chromeUserAgent(webView: String?): String =
            webView?.replace(Regex(" Build/[^;)]*"), "")?.replace("; wv)", ")")?.replace(" Version/4.0", "")
                ?.takeIf { "Chrome/" in it }
                ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.6533.103 Mobile Safari/537.36"

        fun generateState(): String {
            val bytes = ByteArray(24)
            java.security.SecureRandom().nextBytes(bytes)
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
    }

    internal interface Flow {
        val startUrl: String
        val userAgent: String
        fun isRedirect(url: String): Boolean
        fun finish(activity: StoreLoginActivity, view: WebView, url: String): String?
        val readsPage: Boolean get() = false
        fun forgetSignIn(done: () -> Unit) = done()
    }

    private var store: Store? = null
    private var clientUrl: String? = null
    private var flow: Flow? = null
    private var webViewRef: WebView? = null
    private var popupWebView: WebView? = null
    private lateinit var contentHost: FrameLayout
    private lateinit var popupHost: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var titleText: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var reloadButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var retryButton: Button
    private var oauthState: String? = null
    private val captured = AtomicBoolean(false)
    private var flooded = false
    private val chromeUa by lazy { chromeUserAgent(runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()) }
    private var signInCleared = false

    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.byId(intent.getStringExtra(EXTRA_STORE))
        clientUrl = intent.getStringExtra(EXTRA_CLIENT_URL)?.takeIf { store == Store.EPIC }
        oauthState = savedInstanceState?.getString(KEY_STATE) ?: generateState()
        flow = newFlow(oauthState!!)
        floodColor?.let { c ->
            floodColor = null
            flooded = true
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c))
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
        if (flow == null) { finish(); return }
        val chrome = buildChrome()
        setContentView(chrome)
        if (flooded) {
            chrome.alpha = 0f
            chrome.translationY = dp(14).toFloat()
            chrome.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(320).start()
        }
        val webView = newWebView(flow!!.userAgent, isPopup = false)
        webViewRef = webView
        contentHost.addView(webView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popupWebView != null -> dismissPopup()
                    webViewRef?.canGoBack() == true -> webViewRef?.goBack()
                    else -> finish()
                }
            }
        })
        val start = flow!!.startUrl
        signInCleared = savedInstanceState?.getBoolean(KEY_SIGN_IN_CLEARED) == true
        if (signInCleared) webView.loadUrl(start)
        else flow!!.forgetSignIn {
            signInCleared = true
            if (!isDestroyed) webView.loadUrl(start)
        }
    }

    private fun newFlow(state: String): Flow? {
        val url = clientUrl
        if (url != null) return com.droiddeck.launcher.stores.epic.EpicClient.loginFlow(url, chromeUa)
        return store?.let { LoginFlows.forStore(it, state) }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") if (flooded) overridePendingTransition(0, 0)
    }

    private fun buildChrome(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0A0B0D.toInt()) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF121417.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        titleText = TextView(this).apply {
            text = getString(R.string.stores_login_title, store?.label ?: "")
            setTextColor(0xFFF2F4F7.toInt()); textSize = 16f; setPadding(dp(16), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reload = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate); setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.stores_login_reload); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { reloadLogin() }
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel); setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.stores_login_close); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        }
        topBar = bar; reloadButton = reload; closeButton = close
        bar.addView(titleText); bar.addView(reload); bar.addView(close)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }
        contentHost = FrameLayout(this).apply { setBackgroundColor(0xFF0A0B0D.toInt()); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        popupHost = FrameLayout(this).apply { setBackgroundColor(0xFF0A0B0D.toInt()); visibility = View.GONE }
        contentHost.addView(popupHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xFF0A0B0D.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24)); visibility = View.GONE
        }
        errorText = TextView(this).apply { setTextColor(0xFFF2F4F7.toInt()); textSize = 15f; gravity = Gravity.CENTER }
        val retry = Button(this).apply { text = getString(R.string.stores_login_retry); setOnClickListener { reloadLogin() } }
        retryButton = retry
        errorPanel.addView(errorText)
        errorPanel.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        contentHost.addView(errorPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bar); root.addView(progressBar); root.addView(contentHost)
        return root
    }

    /**
     * The activity handles density changes itself so that folding between two displays of different
     * density keeps the page and the sign-in in progress; the dp-based chrome sizes are applied again.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::topBar.isInitialized) return
        topBar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        titleText.setPadding(dp(16), 0, dp(8), 0)
        titleText.textSize = 16f
        errorText.textSize = 15f
        reloadButton.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        closeButton.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        progressBar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        errorPanel.setPadding(dp(24), dp(24), dp(24), dp(24))
        retryButton.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
    }

    private fun showError(message: String) {
        runOnUiThread { errorText.text = message; errorPanel.visibility = View.VISIBLE; errorPanel.bringToFront(); progressBar.visibility = View.GONE }
    }

    private fun clearError() { runOnUiThread { errorPanel.visibility = View.GONE } }

    private fun reloadLogin() {
        dismissPopup(); clearError()
        captured.set(false)
        val fresh = generateState()
        oauthState = fresh
        flow = newFlow(fresh) ?: return
        webViewRef?.let { it.settings.userAgentString = flow!!.userAgent; it.loadUrl(flow!!.startUrl) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(userAgent: String, isPopup: Boolean): WebView = WebView(this).apply {
        setBackgroundColor(0xFF0A0B0D.toInt())
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Store sign-in pages are web pages; they never need to read local files or content:// URIs.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.userAgentString = userAgent
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        if (isPopup) CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = Client(isPopup)
        webChromeClient = Chrome(isPopup)
    }

    private inner class Chrome(private val isPopup: Boolean) : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            if (resultMsg == null) return false
            dismissPopup()
            val child = newWebView(chromeUa, isPopup = true)
            popupWebView = child
            popupHost.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            popupHost.visibility = View.VISIBLE
            popupHost.bringToFront()
            titleText.text = getString(R.string.stores_login_provider_title)
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) { if (isPopup || window === popupWebView) dismissPopup() }
    }

    private fun dismissPopup() {
        val child = popupWebView ?: return
        popupWebView = null
        popupHost.post { destroyPopupView(child) }
    }

    private fun destroyPopupView(child: WebView) {
        child.stopLoading(); child.webChromeClient = null; child.loadUrl("about:blank")
        popupHost.removeView(child); child.destroy()
        if (popupWebView == null) {
            popupHost.visibility = View.GONE
            if (!isFinishing) titleText.text = getString(R.string.stores_login_title, store?.label ?: "")
        }
    }

    private inner class Client(private val isPopup: Boolean) : WebViewClient() {
        private var mainFrameUrl: String? = null

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            Log.d(TAG, "page[popup=$isPopup]: ${StoreLog.redactUrl(url)}")
            if (url != null && view != null && flow?.isRedirect(url) == true && flow?.readsPage != true) complete(view, url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (url != null && view != null && flow?.readsPage == true && flow?.isRedirect(url) == true) complete(view, url)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) showError(getString(R.string.stores_login_error_network, error?.description ?: ""))
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            val status = errorResponse?.statusCode ?: 0
            if (request?.isForMainFrame != true) return
            if (GogAuth.isIdentityProviderHost(request.url?.host) && (status == 403 || status == 400)) showError(getString(R.string.stores_login_error_idp_blocked))
            else showError(getString(R.string.stores_login_error_http, status))
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            val url = error?.url
            if (url == null || url == view?.url || url == mainFrameUrl) showError(getString(R.string.stores_login_error_ssl))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame) mainFrameUrl = request.url.toString()
            return route(view, request.url)
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = route(view, Uri.parse(url))

        private fun route(view: WebView, uri: Uri): Boolean {
            val f = flow ?: return false
            val url = uri.toString()
            if (f.isRedirect(url) && !f.readsPage) { complete(view, url); return true }
            if (!isPopup && store == Store.GOG) {
                if (GogAuth.isGogHost(uri.host) && view.settings.userAgentString != f.userAgent) { view.settings.userAgentString = f.userAgent; return false }
                if (GogAuth.isIdentityProviderHost(uri.host) && view.settings.userAgentString != chromeUa) {
                    view.settings.userAgentString = chromeUa
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                    view.loadUrl(url)
                    return true
                }
            }
            return false
        }
    }

    private fun complete(view: WebView, url: String) {
        if (!captured.compareAndSet(false, true)) return
        val f = flow ?: return
        dismissPopup()
        runOnUiThread {
            clearError()
            titleText.text = getString(R.string.stores_login_finishing)
            if (!f.readsPage) view.stopLoading()
        }
        if (f.readsPage) {
            view.evaluateJavascript("(function(){ try { return document.body.innerText; } catch(e){ return ''; } })()") { json ->
                Thread({ settle(f.finish(this, view, json ?: "")) }, "store-login").start()
            }
        } else Thread({ settle(f.finish(this, view, url)) }, "store-login").start()
    }

    private fun settle(problem: String?) {
        runOnUiThread {
            if (problem == null) { StoresState.refresh(this); finish(); return@runOnUiThread }
            captured.set(false)
            if (!isFinishing && !isDestroyed) android.widget.Toast.makeText(this, problem, android.widget.Toast.LENGTH_LONG).show()
            reloadLogin()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        oauthState?.let { outState.putString(KEY_STATE, it) }
        outState.putBoolean(KEY_SIGN_IN_CLEARED, signInCleared)
    }

    override fun onStart() {
        super.onStart()
        if (clientUrl != null && !isFinishing) com.droiddeck.launcher.session.SessionService.holdForSignIn(this, true)
    }

    override fun onStop() {
        if (clientUrl != null && !isChangingConfigurations) com.droiddeck.launcher.session.SessionService.holdForSignIn(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        popupWebView?.let { popupWebView = null; destroyPopupView(it) }
        webViewRef?.let { it.webChromeClient = null; (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        webViewRef = null
        super.onDestroy()
    }
}
