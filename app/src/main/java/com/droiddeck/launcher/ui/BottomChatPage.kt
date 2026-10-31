package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamWebLogin
import com.droiddeck.launcher.runtime.LinuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val STEAM_CHAT = "https://steamcommunity.com/chat"
private const val CHAT_PAGE_SCALE = 0.75f
private val STEAM_HOSTS = listOf("steamcommunity.com", "steam-chat.com", "steampowered.com", "steamstatic.com", "steamcontent.com")

/** Steam chat stays on Steam's own sites; anything else is not opened on the bottom screen. */
internal fun steamChatAllows(host: String?): Boolean =
    host != null && STEAM_HOSTS.any { host == it || host.endsWith(".$it") }

/** The desktop chat fits the bottom panel better than the phone site; keep WebView's Chrome version. */
internal fun desktopUserAgent(webView: String?): String {
    val chrome = webView?.let { Regex("Chrome/[\\d.]+").find(it)?.value } ?: "Chrome/127.0.6533.103"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
}

/**
 * At the panel's own scale Steam's desktop chat gets about 600x300 CSS pixels and its friends list collapses, so the
 * page is laid out [cssWidth] wide and drawn at [scale]. Steam's site header (the rows before .chat_main_flex; its
 * class name is generated) is hidden to leave the height to the friends list and the chat.
 */
internal fun steamChatFitScript(cssWidth: Int, scale: Float): String {
    val zoom = "%.2f".format(java.util.Locale.US, scale)
    return "(function(){var m=document.querySelector('meta[name=viewport]');" +
        "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
        "m.content='width=$cssWidth, initial-scale=$zoom';" +
        "if(!document.getElementById('dd-chat-fit')){var s=document.createElement('style');s.id='dd-chat-fit';" +
        "s.textContent='$STEAM_CHAT_FIT_CSS';document.head.appendChild(s);}" +
        "window.dispatchEvent(new Event('resize'));})()"
}

internal const val STEAM_CHAT_FIT_CSS = ".chat_main>:has(~.chat_main_flex){display:none!important}"

@Composable
fun BottomChatPage() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var chatOpen by remember { mutableStateOf(true) }
    // Set when Android ended the page's renderer; the chat opens again once DroidDeck is back in front.
    var stopped by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && stopped) {
                stopped = false
                chatOpen = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // null while the Steam client's sign-in is being copied, then whether there was one.
    var fromSteam by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(chatOpen) {
        if (!chatOpen) {
            fromSteam = null
            return@LaunchedEffect
        }
        val cookies = withContext(Dispatchers.IO) {
            SteamWebLogin.read(LinuxRuntime.rootDir(context), SessionState.steamClientArch, File(context.cacheDir, "steam-web-login"))
        }
        val manager = CookieManager.getInstance()
        for (c in cookies) suspendCancellableCoroutine { cont -> manager.setCookie("https://${c.host}", c.header) { cont.resume(Unit) } }
        manager.flush()
        fromSteam = cookies.any { it.header.startsWith("steamLoginSecure=") }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!chatOpen) {
            VoiceRow(context)
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                if (stopped) Text(stringResource(R.string.bottom_chat_stopped), fontSize = 13.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                Text(stringResource(R.string.bottom_chat_why), fontSize = 15.sp, color = colors.onBackground, textAlign = TextAlign.Center)
                BottomPillButton(stringResource(R.string.bottom_chat_open), "bottom-chat-open") {
                    stopped = false
                    chatOpen = true
                }
            }
        } else {
            VoiceRow(context, onClose = { chatOpen = false },
                note = if (fromSteam == false) stringResource(R.string.bottom_chat_not_signed_in) else "")
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
                if (fromSteam != null) SteamChatView(onStopped = {
                    stopped = true
                    chatOpen = false
                })
            }
        }
    }
}

/** With [onClose] set the chat is open: the row also carries Close and leaves out the long mic hint. */
@Composable
private fun VoiceRow(context: Context, onClose: (() -> Unit)? = null, note: String = "") {
    val colors = MaterialTheme.colorScheme
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    fun step(direction: Int) {
        // No flags: Android's volume panel would cover the game on the main screen.
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onClose != null) BottomPillButton(stringResource(R.string.bottom_chat_close), "bottom-chat-close", onClose)
        if (SessionState.micAvailable) {
            val on = SessionState.micEnabled
            BottomPillButton(stringResource(if (on) R.string.bottom_chat_mic_on else R.string.bottom_chat_mic_off), "bottom-chat-mic") {
                SessionService.setMicEnabled(context, !on)
            }
        }
        val hint = if (onClose == null && !SessionState.micAvailable) stringResource(R.string.bottom_chat_mic_unavailable) else note
        Text(hint, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 3, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.bottom_chat_volume, volume * 100 / max), fontSize = 14.sp, color = colors.onSurfaceVariant)
        BottomPillButton("−", "bottom-chat-volume-down") { step(AudioManager.ADJUST_LOWER) }
        BottomPillButton("+", "bottom-chat-volume-up") { step(AudioManager.ADJUST_RAISE) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SteamChatView(onStopped: () -> Unit) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            // AndroidView's default WRAP_CONTENT height gives the page a 0 px tall viewport, which collapses Steam
            // chat's full-height layout (measured on the Retroid Pocket Duo: html and every container 0 px).
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF0A0B0D.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.userAgentString = desktopUserAgent(runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrNull())
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !steamChatAllows(request.url.host)

                override fun onPageFinished(view: WebView, url: String) {
                    val scale = view.resources.displayMetrics.density * CHAT_PAGE_SCALE
                    if (view.width > 0) view.evaluateJavascript(steamChatFitScript((view.width / scale).roundToInt(), CHAT_PAGE_SCALE), null)
                }

                // Android ends the page's renderer once DroidDeck goes to picture-in-picture ("isolated not needed"
                // on the Retroid Pocket Duo); the page cannot come back, so the chat closes and reopens on resume.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onStopped()
                    return true
                }
            }
            loadUrl(STEAM_CHAT)
        }
    }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
        CookieManager.getInstance().flush()
        view.destroy()
    })
}
