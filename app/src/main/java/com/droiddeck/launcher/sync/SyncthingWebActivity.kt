package com.droiddeck.launcher.sync

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.ui.BackLink
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.LocalPalette
import com.droiddeck.launcher.ui.SecondaryButton

/** Syncthing's own web UI, signed in automatically and kept on the loopback address. */
class SyncthingWebActivity : ComponentActivity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DroidDeckTheme(appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(this)) { Screen() } }
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        val colors = MaterialTheme.colorScheme
        val pal = LocalPalette.current
        val running = Syncthing.phase == Syncthing.Phase.RUNNING
        val target = remember(running) { if (running) Syncthing.webUiProxy(this) else null }
        DisposableEffect(target) { onDispose { target?.close() } }
        LaunchedEffect(Syncthing.phase) { if (Syncthing.phase == Syncthing.Phase.OFF) finish() }
        BackHandler { web?.takeIf { it.canGoBack() }?.goBack() ?: finish() }
        Column(Modifier.fillMaxSize().background(colors.background)) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                BackLink(stringResource(R.string.mode_back), compact = true) { finish() }
                Text(stringResource(R.string.sync_web_title), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Box(Modifier.weight(1f))
                target?.let { Text(Uri.parse(it.url).authority.orEmpty(), fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant) }
                SecondaryButton(stringResource(R.string.sync_web_reload), compact = true) { web?.reload() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
            if (target == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.sync_not_running), fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
            } else {
                val view = remember(target) { webView(target.url, target.cookie) }
                AndroidView({ view }, Modifier.fillMaxSize())
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(url: String, cookie: String): WebView = WebView(this).apply {
        web?.destroy()
        web = this
        setBackgroundColor(0xFF0A0B0D.toInt())
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        val origin = Uri.parse(url).authority
        webViewClient = object : WebViewClient() {
            // The proxy cookie isn't port-scoped, so nothing else on loopback may receive it.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.authority == origin) null else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.authority != origin

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(lockFolderPath(getString(R.string.sync_web_path_locked)), null)
            }
        }
        cookies.setCookie(url, "$cookie; HttpOnly; SameSite=Strict") { loadUrl(url) }
    }
}

/** Keeps Syncthing's folder path field read-only, so new folders get the default path inside DroidDeck's Sync folder. */
private fun lockFolderPath(note: String) = """
    (function (note) {
      if (window.droiddeckPathLock) return;
      window.droiddeckPathLock = true;
      function lock() {
        var input = document.getElementById('folderPath');
        if (!input) return;
        if (!input.readOnly) input.readOnly = true;
        if (input.hasAttribute('list')) input.removeAttribute('list');
        if (!document.getElementById('droiddeck-path-note')) {
          var p = document.createElement('p');
          p.id = 'droiddeck-path-note';
          p.className = 'help-block';
          p.textContent = note;
          input.parentNode.insertBefore(p, input.nextSibling);
        }
      }
      new MutationObserver(lock).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['readonly'] });
      lock();
    })(${org.json.JSONObject.quote(note)});
""".trimIndent()
