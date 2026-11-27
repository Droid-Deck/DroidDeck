package com.droiddeck.launcher.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.droiddeck.launcher.R
import com.droiddeck.launcher.companion.CdpPage
import com.droiddeck.launcher.session.SteamQam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

internal class QamFrame(val image: ImageBitmap, val cssWidth: Float, val cssHeight: Float, val contentRight: Float) {
    val pxPerCss: Float get() = image.width / cssWidth
}

internal object QamLayout {
    fun fit(boxWidth: Float, boxHeight: Float, contentRight: Float, cssHeight: Float): Pair<Float, Float> {
        val scale = min(boxWidth / contentRight, boxHeight / cssHeight)
        return scale to (boxWidth - contentRight * scale) / 2f
    }

    fun toCss(x: Float, y: Float, scale: Float, left: Float): Pair<Float, Float> = (x - left) / scale to y / scale

    const val CONTENT_SCRIPT = "(() => { const w = innerWidth; let right = 0; for (const e of document.body.querySelectorAll('*')) { " +
        "const r = e.getBoundingClientRect(); if (r.width > 0 && r.height > 0 && r.width < w * 0.75 && r.right > right) right = r.right; } " +
        "return JSON.stringify([w, innerHeight, devicePixelRatio, right]); })()"
}

private const val TICK_MS = 1_000L

@Composable
internal fun SteamQamPanel() {
    val context = LocalContext.current.applicationContext
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var shape by remember { mutableStateOf<FloatArray?>(null) }
    var failed by remember { mutableStateOf(false) }
    val holder = remember { arrayOfNulls<CdpPage>(1) }
    DisposableEffect(Unit) {
        onDispose {
            val page = holder[0]
            holder[0] = null
            thread(name = "qam-release", isDaemon = true) {
                runCatching { page?.send("Page.stopScreencast") }
                page?.close()
                SteamQam.release(context)
            }
        }
    }
    LaunchedEffect(Unit) {
        var layout = floatArrayOf(0f, 0f, 1f, 0f)
        var parked: Pair<Int, Int>? = null
        withContext(Dispatchers.IO) {
            while (isActive) {
                val open = SteamQam.hold(context)
                failed = open == null
                if (open == SteamQam.QUICK_ACCESS) {
                    var page = holder[0]
                    if (page == null || page.closed) {
                        page?.close()
                        page = SteamQam.target(context)?.let { t ->
                            runCatching {
                                CdpPage(t.port, t.path) { method, params ->
                                    if (method == "Page.screencastFrame") {
                                        holder[0]?.send("Page.screencastFrameAck", JSONObject().put("sessionId", params.optInt("sessionId")))
                                        val bytes = Base64.decode(params.optString("data"), Base64.DEFAULT)
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { image = it.asImageBitmap() }
                                    }
                                }
                            }.onFailure { android.util.Log.w("SteamQam", "devtools connect: $it") }.getOrNull()
                        }
                        holder[0] = page
                        page?.send("Page.enable")
                        page?.send("Page.startScreencast", JSONObject().put("format", "jpeg").put("quality", 85)
                            .put("maxWidth", 1920).put("maxHeight", 1440))
                    }
                    (page?.evaluate(QamLayout.CONTENT_SCRIPT) as? String)?.let { text ->
                        runCatching {
                            val a = JSONArray(text)
                            layout = floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
                            if (!layout.contentEquals(shape)) shape = layout
                        }
                    }
                    val size = (layout[0] * layout[2]).roundToInt() to (layout[1] * layout[2]).roundToInt()
                    if (layout[0] > 0f && size != parked) {
                        SteamQam.park(context, size.first, size.second)
                        parked = size
                    }
                }
                delay(TICK_MS)
            }
        }
    }
    val img = image
    val l = shape
    val f = if (img != null && l != null && l[0] > 0f) QamFrame(img, l[0], l[1], l[3].coerceIn(l[0] * 0.25f, l[0])) else null
    if (f == null) {
        PanelNote(stringResource(if (failed) R.string.bottom_qam_unavailable else R.string.achievements_reading))
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val (scale, left) = QamLayout.fit(boxW, boxH, f.contentRight, f.cssHeight)
        Canvas(
            Modifier.fillMaxSize().pointerInput(f.cssWidth, f.contentRight, boxW, boxH) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val type = when (event.type) {
                            PointerEventType.Press -> "touchStart"
                            PointerEventType.Move -> "touchMove"
                            PointerEventType.Release -> "touchEnd"
                            else -> null
                        } ?: continue
                        val (x, y) = QamLayout.toCss(change.position.x, change.position.y, scale, left)
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
    }
}
