package com.droiddeck.launcher.stores.xbox

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.droiddeck.launcher.core.AddonPack
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoreLogFiles
import com.droiddeck.launcher.stores.StoreLog
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class XboxLoginActivity : ComponentActivity() {
    private lateinit var host: LinearLayout
    private lateinit var status: TextView
    private var view: WebView? = null
    @Volatile private var child: Xodus.Child? = null
    @Volatile private var session = 0L
    @Volatile private var closed = false
    @Volatile private var step = "starting"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply { setTextColor(Color.WHITE); text = "Preparing Microsoft sign-in…"; setPadding(24, 16, 24, 16) }
        host.addView(status)
        host.addView(Button(this).apply { text = getString(com.droiddeck.launcher.R.string.stores_login_close); setOnClickListener { finish() } })
        setContentView(host)
        if (android.os.Build.VERSION.SDK_INT < 28 || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            status.text = "Update Android System WebView to sign in to Xbox."
            return
        }
        CookieManager.getInstance().removeAllCookies { begin() }
    }

    private fun begin() {
        if (closed) return
        Thread({
            if (closed) return@Thread
            try {
                val accountGeneration = XboxCredentials.lifecycle(applicationContext) { it }
                progress("downloading the Xbox components", "Downloading Xbox components…")
                check(AddonPack.XBOX.await(applicationContext, { !closed }) { state ->
                    if (state is AddonPack.State.Downloading) show("Downloading Xbox components… ${(state.progress * 100).toInt()}%")
                }) { "Couldn't download the Xbox components. Check your connection and try again." }
                if (closed) return@Thread
                progress("starting the Xbox helper", "Starting the Xbox helper…")
                Xodus.start(applicationContext, listOf("--external-webview-stdio", "login"), credentialGeneration = accountGeneration).use { process ->
                    child = process
                    if (closed) return@use
                    // StoresState.logLine writes no file in this process, so the reason would miss the shared log.
                    Xodus.diagnostics(process) { logStep(it) }
                    progress("waiting for the Xbox helper", "Waiting for the Xbox helper…")
                    val timer = Thread({
                        runCatching { if (!process.process.waitFor(10, TimeUnit.MINUTES)) process.process.destroy() }
                    }, "xbox-login-timeout").apply { start() }
                    var ready = false
                    var latest = 0L
                    val input = process.process.inputStream.buffered()
                    while (!closed) {
                        val line = try { XboxCredentials.readLine(input, 131_072) } catch (e: java.io.EOFException) { break }
                        if (!line.startsWith("NEXUS_AUTH_V1 ")) continue
                        val frame = JSONObject(line.removePrefix("NEXUS_AUTH_V1 "))
                        when (frame.getString("type")) {
                            "ready" -> {
                                check(!ready && frame.getInt("version") == 1)
                                ready = true
                                process.send(JSONObject().put("type", "hello").put("version", 1))
                                progress("waiting for Microsoft's sign-in page", "Opening Microsoft sign-in…")
                            }
                            "open" -> {
                                val next = frame.getLong("session")
                                check(ready && next > latest && next <= 32 && trusted(frame.getString("url")))
                                latest = next; session = next
                                step = "loading Microsoft's sign-in page"
                                runOnUiThread { if (!closed) open(frame) }
                            }
                            "close" -> {
                                check(frame.getLong("session") == session)
                                session = 0L
                                step = "completing the sign-in"
                                runOnUiThread { disposeView(); status.text = "Completing Microsoft sign-in…" }
                            }
                            "context" -> {
                                check(frame.getLong("session") == session)
                                val context = frame.getString("context")
                                check(context.length <= 4096)
                                val message = JSONObject().put("type", "callback").put("value", JSONObject()
                                    .put("name", "CloudExperienceHost.getContext")
                                    .put("args", org.json.JSONArray(listOf("CloudExperienceHost", "TokenBroker", "TokenBroker", "{\"PrivatePropertyBag\":1,\"PasswordlessConnect\":1,\"PreferAssociate\":1,\"ChromelessUI\":0}")))
                                    .put("context", context))
                                val active = session
                                runOnUiThread { if (active == session && trusted(view?.url.orEmpty())) view?.evaluateJavascript(
                                    "globalThis['CloudExperienceHost.Bridge.dispatchMessage'](${JSONObject.quote(message.toString())})", null) }
                            }
                            else -> error("Unsupported Xbox sign-in response")
                        }
                    }
                    if (closed) return@use
                    check(process.process.waitFor(5, TimeUnit.SECONDS)) { "the Xbox helper did not exit" }
                    val exit = process.process.exitValue()
                    check(exit == 0) { "the Xbox helper stopped with exit code $exit" }
                    check(ready) { "the Xbox helper never became ready" }
                    check(latest > 0) { "Microsoft's sign-in page was never requested" }
                    check(session == 0L) { "the sign-in page was closed before Microsoft finished" }
                    timer.interrupt()
                }
                child = null
                if (closed) return@Thread
                step = "saving the account"
                val account = Xodus.run(applicationContext, listOf("account", "--json"), credentialGeneration = accountGeneration)
                check(account.optBoolean("stored")) { "Xbox did not save the account" }
                XboxCredentials.lifecycle(this, accountGeneration) {
                    val record = StoreAccounts.read(this, Store.XBOX) ?: error("Xbox credentials missing")
                    record.put("username", account.getString("username"))
                    StoreAccounts.write(this, Store.XBOX, record)
                }
                logStep("signed in")
                runOnUiThread { finish() }
            } catch (e: Exception) {
                child = null
                if (closed) return@Thread
                val failed = step
                val reason = failure(e)
                logStep("failed while $failed: $reason")
                runOnUiThread {
                    disposeView()
                    status.text = "Microsoft sign-in did not complete.\nFailed while $failed: $reason\nClose this window and try again."
                }
            }
        }, "xbox-login").start()
    }

    private fun show(text: String) = runOnUiThread { if (!closed && view == null) status.text = text }

    private fun progress(name: String, text: String) { step = name; show(text) }

    /** Written to the stores log, which the shared log zip carries; the main process's log helpers don't run in this process. */
    private fun logStep(line: String) {
        val text = StoreLog.redactLine("Xbox sign-in: $line")
        Log.i(TAG, text)
        StoreLogFiles.stamped(applicationContext, text)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(frame: JSONObject) {
        disposeView()
        val active = frame.getLong("session")
        val web = WebView(this)
        view = web
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; MSAppHost/3.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.102 Safari/537.36 Edge/18.26100"
        }
        WebViewCompat.addWebMessageListener(web, "droiddeckXbox", origins) { _, message, origin, mainFrame, _ ->
            val payload = message.data
            if (mainFrame && trusted(origin.toString()) && trusted(web.url.orEmpty()) && active == session && payload != null && payload.toByteArray().size <= 65_536) {
                runCatching { child?.send(JSONObject().put("type", "ipc").put("session", active).put("payload", payload)) }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(web,
            "if(window===window.top){Object.defineProperty(globalThis.external,'notify',{value:p=>{if(typeof p==='string')droiddeckXbox.postMessage(p)},configurable:false,writable:false})}", origins)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !trusted(request.url.toString())
            override fun onPageFinished(view: WebView, url: String) {
                if (!trusted(url)) { finish(); return }
                status.text = "Microsoft sign-in"
                val uri = Uri.parse(url)
                if (uri.host == "login.live.com" && uri.path == "/ppsecure/post.srf") {
                    view.evaluateJavascript("JSON.stringify(globalThis.ServerData ?? null)") { raw ->
                        val payload = runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrNull()
                        if (active == session && payload != null && payload != "null" && payload.length <= 65_536) runCatching {
                            child?.send(JSONObject().put("type", "ipc").put("session", active).put("payload", payload))
                        }
                    }
                }
            }
        }
        host.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        val headers = frame.getJSONObject("headers")
        val values = headers.keys().asSequence().associateWith { key ->
            require(key in allowedHeaders)
            headers.getString(key).also { value -> require(value.length <= 4096 && value.all { it in ' '..'~' }) }
        }
        web.loadUrl(frame.getString("url"), values)
    }

    private fun disposeView() { view?.let { host.removeView(it); it.stopLoading(); it.destroy() }; view = null }
    override fun onDestroy() {
        closed = true
        val current = child; child = null
        Thread({ runCatching { current?.close() } }, "xbox-login-close").start()
        disposeView()
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XboxLogin"

        /** Our own check messages are safe to show; anything else is named by its type, never its text, which may hold sign-in data. */
        internal fun failure(e: Exception): String {
            val message = e.message.orEmpty()
            return if (e is IllegalStateException && message.isNotBlank() && message != "Check failed." && message.length <= 160) message
            else e.javaClass.simpleName.ifBlank { "error" }
        }

        private val hosts = setOf("login.live.com", "account.live.com", "login.microsoftonline.com", "login.microsoft.com", "account.microsoft.com", "sisu.xboxlive.com")
        private val origins = hosts.map { "https://$it" }.toSet()
        private val allowedHeaders = setOf("cxh-capabilities", "cxh-correlationid", "cxh-msabinaryversion", "cxh-identityclientbinaryversion", "cxh-osversioninfo", "cxh-platform", "cxh-protocol", "cxh-source", "hostapp")
        internal fun trusted(value: String): Boolean = runCatching {
            val url = java.net.URI(value)
            url.scheme == "https" && url.host in hosts && url.port == -1 && url.userInfo == null
        }.getOrDefault(false)
    }
}
