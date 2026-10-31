package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.webkit.CookieManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState

private const val STEAM_CHAT = "https://steamcommunity.com/chat"
private val STEAM_HOSTS = listOf("steamcommunity.com", "steampowered.com", "steamstatic.com", "steamcontent.com")

/** Steam chat stays on Steam's own sites; anything else is not opened on the bottom screen. */
internal fun steamChatAllows(host: String?): Boolean =
    host != null && STEAM_HOSTS.any { host == it || host.endsWith(".$it") }

/** The desktop chat fits the bottom panel better than the phone site; keep WebView's Chrome version. */
internal fun desktopUserAgent(webView: String?): String {
    val chrome = webView?.let { Regex("Chrome/[\\d.]+").find(it)?.value } ?: "Chrome/127.0.6533.103"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
}

@Composable
fun BottomChatPage() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var chatOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceRow(context)
        if (!chatOpen) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                Text(stringResource(R.string.bottom_chat_why), fontSize = 15.sp, color = colors.onBackground, textAlign = TextAlign.Center)
                BottomPillButton(stringResource(R.string.bottom_chat_open), "bottom-chat-open") { chatOpen = true }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))) { SteamChatView() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BottomPillButton(stringResource(R.string.bottom_chat_close), "bottom-chat-close") { chatOpen = false }
            }
        }
    }
}

@Composable
private fun VoiceRow(context: Context) {
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
        if (SessionState.micAvailable) {
            val on = SessionState.micEnabled
            BottomPillButton(stringResource(if (on) R.string.bottom_chat_mic_on else R.string.bottom_chat_mic_off), "bottom-chat-mic") {
                SessionService.setMicEnabled(context, !on)
            }
        } else {
            Text(stringResource(R.string.bottom_chat_mic_unavailable), fontSize = 12.sp, color = colors.onSurfaceVariant,
                maxLines = 3, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.bottom_chat_volume, volume * 100 / max), fontSize = 14.sp, color = colors.onSurfaceVariant,
            modifier = if (SessionState.micAvailable) Modifier.weight(1f) else Modifier, textAlign = TextAlign.End)
        BottomPillButton("−", "bottom-chat-volume-down") { step(AudioManager.ADJUST_LOWER) }
        BottomPillButton("+", "bottom-chat-volume-up") { step(AudioManager.ADJUST_RAISE) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SteamChatView() {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
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
            }
            loadUrl(STEAM_CHAT)
        }
    }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
        CookieManager.getInstance().flush()
        view.destroy()
    })
}
