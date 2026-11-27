package com.droiddeck.launcher.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.companion.CdpPage
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SteamQam
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

internal object QamLayout {
    /** Scale and left edge of the menu in a box; [align] -1, 0 or 1 places a narrower menu left, centred or right. */
    fun fit(boxWidth: Float, boxHeight: Float, contentRight: Float, cssHeight: Float, align: Int = 0): Pair<Float, Float> {
        val scale = min(boxWidth / contentRight, boxHeight / cssHeight)
        return scale to (boxWidth - contentRight * scale) * (align.coerceIn(-1, 1) + 1) / 2f
    }

    fun toCss(x: Float, y: Float, scale: Float, left: Float): Pair<Float, Float> = (x - left) / scale to y / scale

    const val CONTENT_SCRIPT = "(() => { const w = innerWidth; let right = 0; for (const e of document.body.querySelectorAll('*')) { " +
        "const r = e.getBoundingClientRect(); if (r.width > 0 && r.height > 0 && r.width < w * 0.75 && r.right > right) right = r.right; } " +
        "return JSON.stringify([w, innerHeight, devicePixelRatio, right]); })()"
}

private const val TICK_MS = 1_000L
private const val FAST_TICK_MS = 150L

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
    var align by remember { mutableIntStateOf(SessionPrefs.quickAccessAlign(context)) }
    val holder = remember { arrayOfNulls<CdpPage>(1) }
    val relay = remember { intArrayOf(0) }
    val live = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val aspect = remember { floatArrayOf(0f) }
    val measure = Modifier.fillMaxSize().onSizeChanged { if (it.height > 0) aspect[0] = it.width.toFloat() / it.height }
    val show = { frame: ImageBitmap -> image = frame; QamCache.image = frame; live.set(true) }
    DisposableEffect(Unit) {
        onDispose {
            val page = holder[0]
            val port = relay[0]
            holder[0] = null
            thread(name = "qam-release", isDaemon = true) {
                runCatching { page?.send("Page.stopScreencast") }
                page?.close()
                SteamQam.release(context, port)
            }
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
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
                val open = SteamQam.hold(context, aspect[0])
                failed = open == null
                if (open == SteamQam.QUICK_ACCESS) {
                    var page = holder[0]
                    if (page == null || page.closed) {
                        page?.close()
                        live.set(false)
                        page = SteamQam.target(context)?.let { t ->
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
                        page?.send("Page.enable")
                        page?.send("Page.startScreencast", JSONObject().put("format", "jpeg").put("quality", 85)
                            .put("maxWidth", 1920).put("maxHeight", 1440))
                    }
                    // The screencast sends frames only when the menu repaints, so a still menu needs one screenshot.
                    if (!live.get()) {
                        page?.call("Page.captureScreenshot", JSONObject().put("format", "jpeg").put("quality", 85))
                            ?.optJSONObject("result")?.optString("data")?.let(::decodeFrame)?.let(show)
                    }
                    (page?.evaluate(QamLayout.CONTENT_SCRIPT) as? String)?.let { text ->
                        runCatching {
                            val a = JSONArray(text)
                            val layout = floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                            if (!layout.contentEquals(shape)) { shape = layout; QamCache.shape = layout }
                        }
                    }
                }
                delay(if (live.get()) TICK_MS else FAST_TICK_MS)
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
        val (scale, left) = QamLayout.fit(boxW, boxH, f.contentRight, f.cssHeight, align)
        Canvas(
            Modifier.fillMaxSize().pointerInput(f.cssWidth, f.contentRight, boxW, boxH, align) {
                var touching = false
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
                        touching = type != "touchEnd"
                        val points = if (type == "touchEnd") JSONArray()
                            else JSONArray().put(JSONObject().put("x", x.toDouble()).put("y", y.toDouble()))
                        holder[0]?.send("Input.dispatchTouchEvent", JSONObject().put("type", type).put("touchPoints", points))
                        change.consume()
                    }
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
        val width = f.contentRight * scale
        val gap = with(density) { 64.dp.toPx() }
        val move = { step: Int -> align = (align + step).coerceIn(-1, 1); SessionPrefs.setQuickAccessAlign(context, align) }
        if (left >= gap) {
            Box(Modifier.align(Alignment.CenterStart).width(with(density) { left.toDp() }), contentAlignment = Alignment.Center) {
                MoveHandle(stringResource(R.string.bottom_qam_align_left), Icons.Filled.ChevronLeft, Modifier.testTag("qam-move-left")) { move(-1) }
            }
        }
        if (boxW - left - width >= gap) {
            Box(Modifier.align(Alignment.CenterEnd).width(with(density) { (boxW - left - width).toDp() }), contentAlignment = Alignment.Center) {
                MoveHandle(stringResource(R.string.bottom_qam_align_right), Icons.Filled.ChevronRight, Modifier.testTag("qam-move-right")) { move(1) }
            }
        }
    }
}

@Composable
private fun MoveHandle(description: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(22.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(width = 44.dp, height = 112.dp).semantics { contentDescription = description }
            .clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
            .glideBorder(hot, shape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick),
    ) {
        Icon(icon, contentDescription = null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
    }
}

private fun decodeFrame(data: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(data, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
