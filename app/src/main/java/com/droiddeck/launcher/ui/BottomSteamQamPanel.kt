package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlignHorizontalCenter
import androidx.compose.material.icons.outlined.AlignHorizontalLeft
import androidx.compose.material.icons.outlined.AlignHorizontalRight
import androidx.compose.material.icons.outlined.WidthFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.CdpPage
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamQam
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class QamFrame(val image: ImageBitmap, val cssWidth: Float, val cssHeight: Float, val contentRight: Float) {
    val pxPerCss: Float get() = image.width / cssWidth

    /** The menu's own background, sampled inside its right edge, to fill the panel around it. */
    val fill: Color = runCatching {
        val x = (contentRight * pxPerCss).roundToInt() - 4
        Color(image.asAndroidBitmap().getPixel(x.coerceIn(0, image.width - 1), image.height / 2))
    }.getOrDefault(Color.Black)
}

/** How Steam's Quick Access menu sits on the bottom screen. */
internal enum class QamFit(val id: String, @StringRes val label: Int, val icon: ImageVector, val align: Int) {
    FULL("full", R.string.bottom_qam_layout_full, Icons.Outlined.WidthFull, 0),
    LEFT("left", R.string.bottom_qam_layout_left, Icons.Outlined.AlignHorizontalLeft, -1),
    CENTER("center", R.string.bottom_qam_layout_center, Icons.Outlined.AlignHorizontalCenter, 0),
    RIGHT("right", R.string.bottom_qam_layout_right, Icons.Outlined.AlignHorizontalRight, 1);

    val next: QamFit get() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): QamFit = entries.firstOrNull { it.id == id } ?: FULL

        private val state = mutableStateOf<QamFit?>(null)

        fun current(context: Context): QamFit = state.value ?: from(SessionPrefs.quickAccessLayout(context)).also { state.value = it }

        fun choose(context: Context, fit: QamFit) {
            state.value = fit
            SessionPrefs.setQuickAccessLayout(context, fit.id)
        }
    }
}

internal object QamLayout {
    private const val WIDE_CSS = "div.BasicUI { width: 100% !important; } div[class*=\\\"tab_\\\"] { max-width: none !important; }"

    /** Scale and left edge of the menu in a box; [align] -1, 0 or 1 places a narrower menu left, centred or right. */
    fun fit(boxWidth: Float, boxHeight: Float, contentRight: Float, cssHeight: Float, align: Int = 0): Pair<Float, Float> {
        val scale = min(boxWidth / contentRight, boxHeight / cssHeight)
        return scale to (boxWidth - contentRight * scale) * (align.coerceIn(-1, 1) + 1) / 2f
    }

    fun toCss(x: Float, y: Float, scale: Float, left: Float): Pair<Float, Float> = (x - left) / scale to y / scale

    /** Makes the menu follow the panel-shaped view (Steam sizes it once, on open) and lifts each tab's 300 px cap, then measures where the content ends. */
    /** [wide] is false while the menu is shown on the top screen, where it keeps Steam's own layout. */
    fun contentScript(wide: Boolean): String = "(() => { let s = document.getElementById('dd-qam-wide'); " +
        "if (!s) { s = document.createElement('style'); s.id = 'dd-qam-wide'; document.head.appendChild(s); } " +
        "const css = '${if (wide) WIDE_CSS else ""}'; " +
        "if (s.textContent !== css) s.textContent = css; if (document.scrollingElement.scrollLeft) document.scrollingElement.scrollLeft = 0; " +
        "const w = innerWidth; let right = 0; for (const e of document.body.querySelectorAll('*')) { " +
        "const r = e.getBoundingClientRect(); const tab = /(^| )tab_/.test(String(e.className)); " +
        "if (r.width > 0 && r.height > 0 && (tab || r.width < w * 0.75) && r.right > right) right = r.right; } " +
        "return JSON.stringify([w, innerHeight, devicePixelRatio, right]); })()"
}

private const val TICK_MS = 1_000L
private const val FAST_TICK_MS = 150L
private const val MAX_RETRY_MS = 4_000L

/** The last frame shown, so returning to the panel shows the menu at once while the live stream reconnects. */
private object QamCache {
    @Volatile var image: ImageBitmap? = null
    @Volatile var shape: FloatArray? = null
}

@Composable
internal fun SteamQamPanel() {
    val context = LocalContext.current.applicationContext
    var image by remember { mutableStateOf(QamCache.image) }
    var shape by remember { mutableStateOf(QamCache.shape) }
    var failed by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2_500)
        slow = true
    }
    val fit = QamFit.current(context)
    val holder = remember { arrayOfNulls<CdpPage>(1) }
    val relay = remember { intArrayOf(0) }
    val live = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val aspect = remember { floatArrayOf(0f) }
    val measure = Modifier.fillMaxSize().onSizeChanged { if (it.height > 0) aspect[0] = it.width.toFloat() / it.height }
    val show = { frame: ImageBitmap -> image = frame; QamCache.image = frame; live.set(true) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            var misses = 0
            // The lease is renewed on its own, so slow discovery or a retry backoff never lets the held menu lapse.
            val renew = launch {
                while (isActive) {
                    if (SessionState.phase == SessionPhase.READY && aspect[0] > 0f) SteamQam.hold(context, aspect[0])
                    delay(TICK_MS)
                }
            }
            try {
                while (isActive) {
                    // Holding the menu before Steam's first frame keeps gamescope from presenting it.
                    if (SessionState.phase != SessionPhase.READY) {
                        delay(FAST_TICK_MS)
                        continue
                    }
                    if (aspect[0] == 0f) {
                        delay(FAST_TICK_MS)
                        continue
                    }
                    val held = SteamQam.hold(context, aspect[0])
                    ensureActive()
                    var reached = held != null
                    if (held?.open == SteamQam.QUICK_ACCESS) {
                        var page = holder[0]
                        if (page == null || page.closed) {
                            page?.close()
                            live.set(false)
                            page = SteamQam.target(context)?.let { t ->
                                if (relay[0] != 0 && relay[0] != t.port) SteamQam.stopRelay(context, relay[0])
                                relay[0] = t.port
                                runCatching {
                                    CdpPage(t.port, t.path) { method, params ->
                                        if (method == "Page.screencastFrame") {
                                            holder[0]?.send("Page.screencastFrameAck", JSONObject().put("sessionId", params.optInt("sessionId")))
                                            decodeFrame(params.optString("data"))?.let(show)
                                        }
                                    }
                                }.onFailure { android.util.Log.w("SteamQam", "devtools connect: $it") }.getOrNull()
                            }
                            holder[0] = page
                            ensureActive()
                            page?.send("Page.enable")
                            val started = page?.call("Page.startScreencast", JSONObject().put("format", "jpeg").put("quality", 85)
                                .put("maxWidth", 1920).put("maxHeight", 1440))
                            if (page != null && (started == null || started.has("error") || !started.has("result"))) {
                                page.close()
                                page = null
                                holder[0] = null
                            }
                        }
                        // The screencast sends frames only when the menu repaints, so a still menu needs one screenshot.
                        if (!live.get()) {
                            page?.call("Page.captureScreenshot", JSONObject().put("format", "jpeg").put("quality", 85))
                                ?.optJSONObject("result")?.optString("data")?.let(::decodeFrame)?.let(show)
                        }
                        reached = page != null
                        val wide = !held.top && QamFit.current(context) == QamFit.FULL
                        (page?.evaluate(QamLayout.contentScript(wide)) as? String)?.let { text ->
                            runCatching {
                                val a = JSONArray(text)
                                val layout = floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                                if (!layout.contentEquals(shape)) { shape = layout; QamCache.shape = layout }
                            }
                        }
                    }
                    failed = !reached
                    // With no menu page to reach, retries slow down instead of starting guest processes several times a second.
                    misses = if (reached) 0 else misses + 1
                    val wait = when {
                        !reached -> minOf(TICK_MS shl minOf(misses - 1, 2), MAX_RETRY_MS)
                        live.get() -> TICK_MS
                        else -> FAST_TICK_MS
                    }
                    val used = QamFit.current(context)
                    withTimeoutOrNull(wait) {
                        snapshotFlow { QamFit.current(context) }.first { it != used }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    renew.cancelAndJoin()
                    val page = holder[0]
                    holder[0] = null
                    runCatching { page?.send("Page.stopScreencast") }
                    // The page outlives this connection, so the widened layout would otherwise stay on the top screen.
                    runCatching { page?.evaluate(QamLayout.contentScript(wide = false)) }
                    page?.close()
                    SteamQam.release(context, relay[0])
                }
            }
        }
    }
    val img = image
    val l = shape
    val f = remember(img, l) {
        if (img != null && l != null && l[0] > 0f) QamFrame(img, l[0], l[1], l[3].coerceIn(l[0] * 0.25f, l[0])) else null
    }
    if (f == null) {
        Box(measure) {
            if (slow) PanelNote(stringResource(if (failed) R.string.bottom_qam_unavailable else R.string.achievements_reading))
        }
        return
    }
    BoxWithConstraints(measure.background(f.fill)) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val (scale, left) = QamLayout.fit(boxW, boxH, f.contentRight, f.cssHeight, fit.align)
        Canvas(
            Modifier.fillMaxSize().pointerInput(f.cssWidth, f.contentRight, boxW, boxH, fit) {
                var touching = false
                var target: CdpPage? = null
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val (x, y) = QamLayout.toCss(change.position.x, change.position.y, scale, left)
                            val type = when (event.type) {
                                PointerEventType.Press -> if (x in 0f..f.contentRight && y in 0f..f.cssHeight) "touchStart" else null
                                PointerEventType.Move -> "touchMove"
                                PointerEventType.Release -> "touchEnd"
                                else -> null
                            }?.takeIf { it == "touchStart" || touching } ?: continue
                            if (type == "touchStart") target = holder[0]
                            touching = type != "touchEnd"
                            val points = if (type == "touchEnd") JSONArray()
                                else JSONArray().put(JSONObject().put("x", x.toDouble()).put("y", y.toDouble()))
                            target?.send("Input.dispatchTouchEvent", JSONObject().put("type", type).put("touchPoints", points))
                            if (!touching) target = null
                            change.consume()
                        }
                    }
                } finally {
                    if (touching) target?.send("Input.dispatchTouchEvent",
                        JSONObject().put("type", "touchCancel").put("touchPoints", JSONArray()))
                }
            },
        ) {
            val px = f.pxPerCss
            drawImage(
                f.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize((f.contentRight * px).roundToInt().coerceAtMost(f.image.width), f.image.height),
                dstOffset = IntOffset(left.roundToInt(), 0),
                dstSize = IntSize((f.contentRight * scale).roundToInt(), (f.cssHeight * scale).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private fun decodeFrame(data: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(data, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
