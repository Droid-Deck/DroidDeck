package com.droiddeck.launcher.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamGuides
import com.droiddeck.launcher.session.SteamWebLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private const val GAME_POLL_MS = 2_000L

private sealed class GuideList {
    object Loading : GuideList()
    object Failed : GuideList()
    class Found(val guides: List<SteamGuides.Guide>) : GuideList()
}

@Composable
internal fun BottomGuidesPage(runningGame: () -> String?) {
    val context = LocalContext.current
    var appId by remember { mutableStateOf(runningGame()?.toLongOrNull()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            appId = runningGame()?.toLongOrNull()
            delay(GAME_POLL_MS)
        }
    }
    val id = appId
    if (id == null) {
        PageNote(stringResource(R.string.bottom_guides_no_game))
        return
    }
    val game = id.toString()
    var reading by remember(id) { mutableStateOf(SteamGuides.place(context, game)?.url) }
    var list by remember(id) { mutableStateOf<GuideList>(GuideList.Loading) }
    LaunchedEffect(id) {
        val cookies = withContext(Dispatchers.IO) {
            runCatching {
                SteamWebLogin.read(LinuxRuntime.rootDir(context), SessionState.steamClientArch, File(context.cacheDir, "steam-web-login"))
            }.getOrDefault(emptyList())
        }
        val manager = CookieManager.getInstance()
        for (c in cookies) suspendCancellableCoroutine { cont -> manager.setCookie("https://${c.host}", c.header) { cont.resume(Unit) } }
        val header = cookies.filter { steamChatAllows(it.host) }.joinToString("; ") { it.header.substringBefore(';') }
        list = withContext(Dispatchers.IO) { SteamGuides.list(id, header) }?.let { GuideList.Found(it) } ?: GuideList.Failed
    }
    val open = reading
    if (open != null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BottomPillButton(stringResource(R.string.bottom_guides_back), "bottom-guides-back") { reading = null }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp))) {
                GuideReader(open, SteamGuides.place(context, game)?.takeIf { it.url == open }?.scroll ?: 0f,
                    onPlace = { url, scroll -> SteamGuides.savePlace(context, game, url, scroll) },
                    onStopped = { reading = null })
            }
        }
        return
    }
    when (val l = list) {
        GuideList.Loading -> PageNote(stringResource(R.string.achievements_reading))
        GuideList.Failed -> PageNote(stringResource(R.string.bottom_guides_failed))
        is GuideList.Found -> if (l.guides.isEmpty()) PageNote(stringResource(R.string.bottom_guides_empty)) else {
            val last = SteamGuides.place(context, game)
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (last != null) item(key = "continue") {
                    BottomPillButton(stringResource(R.string.bottom_guides_continue), "bottom-guides-continue") { reading = last.url }
                }
                items(l.guides, key = { it.id }) { guide -> GuideRow(guide) { reading = guide.url } }
            }
        }
    }
}

@Composable
private fun PageNote(text: String) {
    Box(Modifier.fillMaxSize()) {
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(24.dp))
    }
}

@Composable
internal fun GuideRow(guide: SteamGuides.Guide, onOpen: () -> Unit) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface)
            .clickable(role = Role.Button, onClick = onOpen).testTag("guide-${guide.id}").padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(pal.line)) {
            if (guide.preview != null) AsyncImage(model = guide.preview, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(guide.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(guide.author.takeIf { it.isNotEmpty() }, "★".repeat(guide.stars).takeIf { it.isNotEmpty() }).joinToString(" · "),
                fontSize = 12.sp, color = pal.signal, maxLines = 1)
            Text(guide.summary, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GuideReader(url: String, scroll: Float, onPlace: (String, Float) -> Unit, onStopped: () -> Unit) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(0xFF1B2838.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            var restore = scroll
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !steamChatAllows(request.url.host)

                override fun onPageFinished(view: WebView, url: String) {
                    if (restore > 0f) {
                        val ratio = restore
                        restore = 0f
                        view.postDelayed({ view.scrollTo(0, (view.contentHeight * view.scale * ratio).toInt()) }, 400)
                    }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onStopped()
                    return true
                }
            }
            setOnScrollChangeListener { v, _, y, _, _ ->
                val web = v as WebView
                val height = web.contentHeight * web.scale
                val current = web.url ?: return@setOnScrollChangeListener
                if (height > 0) onPlace(current, y / height)
            }
            loadUrl(url)
        }
    }, modifier = Modifier.fillMaxSize(), onRelease = { view ->
        view.url?.let { current -> onPlace(current, view.scrollY / (view.contentHeight * view.scale).coerceAtLeast(1f)) }
        view.destroy()
    })
}
