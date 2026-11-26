package com.droiddeck.launcher.stores.xbox

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.ui.DroidDeckTheme
import kotlinx.coroutines.delay

// The game's art as a slow slideshow while a cloud stream connects, until the first video frame.
// From Even Better Xcloud (BannerCloud) 7ec2a75.
internal class XboxLaunchArt(private val activity: Activity, private val log: (String) -> Unit) {
    private val urls = mutableStateListOf<String>()
    private var visible by mutableStateOf(true)
    @Volatile var shown = true
        private set

    fun view(title: String): ComposeView = ComposeView(activity).apply {
        setContent { DroidDeckTheme { LaunchArt(urls, title, visible) } }
    }

    fun load(product: String) {
        if (product.isEmpty()) return
        val app = activity.applicationContext
        Thread({
            runCatching {
                val account = StoreAccounts.signedInAs(app, Store.XBOX) ?: return@runCatching
                val game = XboxLibraryCache.read(app, account, Xodus.market())?.games?.firstOrNull { it.id == product }
                game?.imageUrl?.let { url -> activity.runOnUiThread { if (urls.isEmpty()) urls.add(url) } }
                val more = XboxCatalog.launchArt(product)
                if (more.isNotEmpty()) activity.runOnUiThread { urls.addAll(more.filter { it !in urls }) }
            }.onFailure { log("launch art: ${it.javaClass.simpleName}") }
        }, "launch-art").start()
    }

    fun hide() {
        shown = false
        activity.runOnUiThread { visible = false }
    }
}

@Composable
private fun LaunchArt(images: List<String>, title: String, visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(tween(400)), exit = fadeOut(tween(700))) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            var index by remember { mutableIntStateOf(0) }
            LaunchedEffect(images.size) {
                while (true) {
                    delay(SLIDE_MS)
                    if (images.size > 1) index = (index + 1) % images.size
                }
            }
            Crossfade(targetState = images.getOrNull(index % images.size.coerceAtLeast(1)), animationSpec = tween(1400),
                label = "launch-art") { url ->
                if (url != null) Slide(url, index)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color(0x66000000), 0.45f to Color(0x22000000), 0.78f to Color(0xB3000000), 1f to Color(0xF2000000))))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, end = 40.dp, bottom = 34.dp))
        }
    }
}

@Composable
private fun Slide(url: String, index: Int) {
    val ctx = LocalContext.current
    val progress = remember(url) { Animatable(0f) }
    LaunchedEffect(url) { progress.animateTo(1f, tween((SLIDE_MS + 1600).toInt(), easing = LinearEasing)) }
    val dir = if (index % 2 == 0) 1f else -1f
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url).crossfade(500).build(),
        contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val p = progress.value
            val s = 1.06f + 0.10f * p
            scaleX = s; scaleY = s
            translationX = dir * (p - 0.5f) * size.width * 0.04f
        })
}

private const val SLIDE_MS = 5200L
