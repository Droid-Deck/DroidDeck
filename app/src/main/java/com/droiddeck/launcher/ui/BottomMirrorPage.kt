package com.droiddeck.launcher.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class MirrorHide(val id: String, val label: Int) {
    OFF("off", R.string.bottom_mirror_hide_off),
    BLACK("black", R.string.bottom_mirror_hide_black),
    DIM("dim", R.string.bottom_mirror_hide_dim);

    fun next(): MirrorHide = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(id: String?): MirrorHide = entries.firstOrNull { it.id == id } ?: OFF
    }
}

internal object MirrorRegion {
    const val MIN_SIZE = 0.02f

    fun parse(text: String?): RectF? {
        val v = text?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.size == 4 } ?: return null
        return normalize(v[0], v[1], v[2], v[3])
    }

    fun format(r: RectF): String = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.US, r.left, r.top, r.right, r.bottom)

    fun normalize(x0: Float, y0: Float, x1: Float, y1: Float): RectF? {
        val r = RectF(min(x0, x1).coerceIn(0f, 1f), min(y0, y1).coerceIn(0f, 1f), max(x0, x1).coerceIn(0f, 1f), max(y0, y1).coerceIn(0f, 1f))
        return r.takeIf { it.width() >= MIN_SIZE && it.height() >= MIN_SIZE }
    }

    fun outputSize(r: RectF, sourceAspect: Float, longest: Int): Pair<Int, Int> {
        val w = r.width() * sourceAspect
        val h = r.height()
        return if (w >= h) longest to max(1, (longest * h / w).roundToInt()) else max(1, (longest * w / h).roundToInt()) to longest
    }
}

private const val FRAME_MS = 66L
private const val COMPOSED_SETTLE_MS = 250L
private const val EDIT_WIDTH = 960
private const val EDIT_HEIGHT = 540
private const val LIVE_LONGEST = 720

@Composable
internal fun MirrorPanel(actions: BottomPageActions) {
    val context = LocalContext.current
    val copy = actions.copyRegion
    if (copy == null) {
        MirrorNote(stringResource(R.string.bottom_mirror_no_frame))
        return
    }
    DisposableEffect(Unit) {
        actions.holdComposed(true)
        onDispose { actions.holdComposed(false) }
    }
    val game = actions.runningGame() ?: "steam"
    var region by remember(game) { mutableStateOf(MirrorRegion.parse(SessionPrefs.mirrorRegion(context, game))) }
    var editing by remember(game) { mutableStateOf(region == null) }
    var sharp by remember { mutableStateOf(true) }
    val host = rememberMenuHost()
    if (editing) {
        MirrorEditor(copy, region,
            onSave = {
                region = it
                SessionPrefs.setMirrorRegion(context, game, MirrorRegion.format(it))
                editing = false
            },
            onCancel = if (region != null) ({ editing = false }) else null)
        return
    }
    val r = region ?: return
    var hide by remember(game) { mutableStateOf(MirrorHide.from(SessionPrefs.mirrorHide(context, game))) }
    DisposableEffect(r, hide) {
        actions.maskRegion(r.takeIf { hide != MirrorHide.OFF }, hide == MirrorHide.DIM)
        onDispose { actions.maskRegion(null, false) }
    }
    var frame by remember(r) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(r) { mutableStateOf(false) }
    LaunchedEffect(r) {
        while (isActive) {
            val (w, h) = MirrorRegion.outputSize(r, 16f / 9f, LIVE_LONGEST)
            val bitmap = capture(copy, r, w, h)
            failed = bitmap == null
            if (bitmap != null) frame = bitmap.asImageBitmap()
            delay(FRAME_MS)
        }
    }
    val pal = LocalPalette.current
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .border(1.dp, pal.line, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            if (f != null) Image(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                filterQuality = if (sharp) FilterQuality.None else FilterQuality.Medium)
            else if (failed) MirrorNote(stringResource(R.string.bottom_mirror_no_frame))
        }
        Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            SettingsGroup(stringResource(R.string.bottom_page_mirror)) {
                ActionRow(stringResource(R.string.bottom_mirror_region), null, stringResource(R.string.game_env_edit), onClick = { editing = true },
                    modifier = Modifier.testTag("bottom-mirror-edit"))
                ChoiceRow(host, "mirror-hide", stringResource(R.string.bottom_mirror_top), null,
                    MirrorHide.entries.map { it to stringResource(it.label) }, hide) {
                    hide = it
                    SessionPrefs.setMirrorHide(context, game, it.id)
                }
                ChoiceRow(host, "mirror-scaling", stringResource(R.string.bottom_mirror_scaling), null,
                    listOf(true to stringResource(R.string.bottom_mirror_sharp), false to stringResource(R.string.bottom_mirror_smooth)), sharp) {
                    sharp = it
                }
            }
        }
    }
}

@Composable
private fun MirrorEditor(
    copy: (RectF?, Int, Int, (Bitmap?) -> Unit) -> Unit,
    current: RectF?,
    onSave: (RectF) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val pal = LocalPalette.current
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var picked by remember { mutableStateOf(current) }
    LaunchedEffect(Unit) {
        delay(COMPOSED_SETTLE_MS)
        val bitmap = capture(copy, null, EDIT_WIDTH, EDIT_HEIGHT)
        failed = bitmap == null
        still = bitmap?.asImageBitmap()
    }
    PanelColumn {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onCancel?.let { BackLink(stringResource(R.string.common_cancel), compact = true, onClick = it) }
            Text(stringResource(R.string.bottom_mirror_pick), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.bottom_mirror_save), enabled = picked != null, compact = true,
                modifier = Modifier.testTag("bottom-mirror-save")) { picked?.let(onSave) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
            val image = still
            if (image == null) {
                if (failed) MirrorNote(stringResource(R.string.bottom_mirror_no_frame))
                return@BoxWithConstraints
            }
            Box(Modifier.aspectRatio(image.width.toFloat() / image.height).clip(RoundedCornerShape(8.dp))) {
                Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start = it; end = it },
                        onDrag = { change, _ -> end = change.position },
                        onDragEnd = {
                            val a = start
                            val b = end
                            if (a != null && b != null) MirrorRegion.normalize(a.x / size.width, a.y / size.height, b.x / size.width, b.y / size.height)
                                ?.let { picked = it }
                            start = null
                            end = null
                        },
                    )
                }) {
                    val a = start
                    val b = end
                    val shown = if (a != null && b != null) RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
                        else picked?.let { RectF(it.left * size.width, it.top * size.height, it.right * size.width, it.bottom * size.height) }
                    if (shown != null) {
                        val veil = Color.Black.copy(alpha = 0.5f)
                        drawRect(veil, Offset.Zero, Size(size.width, shown.top))
                        drawRect(veil, Offset(0f, shown.bottom), Size(size.width, size.height - shown.bottom))
                        drawRect(veil, Offset(0f, shown.top), Size(shown.left, shown.height()))
                        drawRect(veil, Offset(shown.right, shown.top), Size(size.width - shown.right, shown.height()))
                        drawRect(pal.signal, Offset(shown.left, shown.top), Size(shown.width(), shown.height()), style = Stroke(3.dp.toPx()))
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorNote(text: String) {
    Box(Modifier.fillMaxSize()) {
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(24.dp))
    }
}

private suspend fun capture(copy: (RectF?, Int, Int, (Bitmap?) -> Unit) -> Unit, region: RectF?, width: Int, height: Int): Bitmap? =
    suspendCancellableCoroutine { cont -> copy(region, width, height) { if (cont.isActive) cont.resume(it) else it?.recycle() } }
