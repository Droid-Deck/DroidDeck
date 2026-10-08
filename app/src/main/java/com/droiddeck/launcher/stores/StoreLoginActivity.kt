package com.droiddeck.launcher.stores

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
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

/**
 * A store's own sign-in page in a WebView, one activity for the three stores: GOG's OAuth page
 * (the token comes back in the redirect's fragment), Epic's web login (an authorization code on
 * a JSON page), Amazon's device sign-in (a PKCE code on the return URL). Plain views, as the
 * proven GOG flow was: the page is rendered with the store's own client identity, and a social
 * sign-in hop to Google / Facebook / Apple runs under a real browser identity in a popup, since
 * those providers refuse an embedded browser.
 *
 * Nothing from the page is logged but redacted URLs and key names; the tokens go straight to
 * StoreAccounts' private file and the activity finishes.
 */
class StoreLoginActivity : ComponentActivity() {
    companion object {
        private const val TAG = "StoreLogin"
        const val EXTRA_STORE = "store"
        private const val KEY_STATE = "oauth_state"
        private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.6533.103 Mobile Safari/537.36"

        fun generateState(): String {
            val bytes = ByteArray(24)
            java.security.SecureRandom().nextBytes(bytes)
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
    }

    /** What one store's flow decides: the page to open, the identity to open it with, and the redirect that ends it. */
    internal interface Flow {
        val startUrl: String
        val userAgent: String
        /** True when [url] is the flow's end; the activity then calls [finish]. */
        fun isRedirect(url: String): Boolean
        /** Runs off the main thread with the redirect; returns null on success, else a message to show. */
        fun finish(activity: StoreLoginActivity, view: WebView, url: String): String?
        /** Whether a JSON page at the redirect must be read out of the document instead of the URL (Epic). */
        val readsPage: Boolean get() = false
    }

    private var store: Store? = null
    private var flow: Flow? = null
    private var webViewRef: WebView? = null
    private var popupWebView: WebView? = null
    private lateinit var contentHost: FrameLayout
    private lateinit var popupHost: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var titleText: TextView
    private var oauthState: String? = null
    private val captured = AtomicBoolean(false)

    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store.byId(intent.getStringExtra(EXTRA_STORE))
        oauthState = savedInstanceState?.getString(KEY_STATE) ?: generateState()
        flow = store?.let { LoginFlows.forStore(it, oauthState!!) }
        if (flow == null) { finish(); return }
        setContentView(buildChrome())
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
        webView.loadUrl(flow!!.startUrl)
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
        errorPanel.addView(errorText)
        errorPanel.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        contentHost.addView(errorPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(bar); root.addView(progressBar); root.addView(contentHost)
        return root
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
        flow = store?.let { LoginFlows.forStore(it, fresh) } ?: return
        webViewRef?.let { it.settings.userAgentString = flow!!.userAgent; it.loadUrl(flow!!.startUrl) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(userAgent: String, isPopup: Boolean): WebView = WebView(this).apply {
        setBackgroundColor(0xFF0A0B0D.toInt())
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
        // The stores' social-login buttons are window.open() popups; without these the call is dropped silently.
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
            val child = newWebView(CHROME_UA, isPopup = true)
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

    /** Deferred: destroying a WebView from inside one of its own callbacks crashes chromium. */
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
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            Log.d(TAG, "page[popup=$isPopup]: ${StoreLog.redactUrl(url)}")
            // Amazon's return URL lands as a page load rather than a navigation we can intercept.
            if (url != null && view != null && flow?.isRedirect(url) == true && flow?.readsPage != true) complete(view, url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // Epic's redirect is a JSON page: the code is in the document, read once it is loaded.
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
            showError(getString(R.string.stores_login_error_ssl))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = route(view, request.url)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = route(view, Uri.parse(url))

        private fun route(view: WebView, uri: Uri): Boolean {
            val f = flow ?: return false
            val url = uri.toString()
            if (f.isRedirect(url) && !f.readsPage) { complete(view, url); return true }
            // A social sign-in hop that stays in the main view: the provider gets a browser identity,
            // and the store gets its own identity back afterwards (a plain Chrome UA makes GOG's
            // page serve a form that never renders in a WebView).
            if (!isPopup && store == Store.GOG) {
                if (GogAuth.isGogHost(uri.host) && view.settings.userAgentString != f.userAgent) { view.settings.userAgentString = f.userAgent; return false }
                if (GogAuth.isIdentityProviderHost(uri.host) && view.settings.userAgentString != CHROME_UA) {
                    view.settings.userAgentString = CHROME_UA
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
            // The document's text, then the exchange off the main thread.
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
    }

    override fun onDestroy() {
        popupWebView?.let { popupWebView = null; destroyPopupView(it) }
        webViewRef?.let { it.webChromeClient = null; (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        webViewRef = null
        super.onDestroy()
    }
}
