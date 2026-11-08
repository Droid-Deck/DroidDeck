package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

@Composable
internal fun InputLayoutRow(layout: SavedInputLayout, role: InputRole, detail: String?, onManage: () -> Unit, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).focusRow(onClick = onPick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InputLayoutThumbnail(layout, role)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(layout.name, style = MaterialTheme.typography.titleMedium)
                Text(detail ?: stringResource(R.string.input_layout_count, layout.elements.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PadTextButton(onClick = onManage, modifier = Modifier.testTag("layout-manage-${layout.id}")) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
internal fun InputLayoutThumbnail(layout: SavedInputLayout, role: InputRole, images: Map<String, ByteArray> = emptyMap(),
    modifier: Modifier = Modifier.width(112.dp).height(76.dp)) {
    val bitmaps = remember(images) { images.mapValues { (_, bytes) -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } }
    DisposableEffect(bitmaps) { onDispose { bitmaps.values.forEach { it?.recycle() } } }
    key(bitmaps) {
        AndroidView(factory = { InputLayoutPreviewView(it, bitmaps.filterValues { it != null }.mapValues { it.value!! }) },
            modifier = modifier.testTag("layout-preview-${layout.id}"), update = { it.update(layout, role) })
    }
}

internal class InputLayoutPreviewView(context: Context, images: Map<String, android.graphics.Bitmap> = emptyMap()) : View(context) {
    private val renderer = InputSurfaceView(context, previewImages = images)
    private var sourceHeight = 563
    fun update(layout: SavedInputLayout, role: InputRole) {
        sourceHeight = if (role == InputRole.PRIMARY) 563 else 870
        renderer.updateCustomLayout(layout.elements)
        renderer.updateStyle(layout.style.copy(opacity = 100))
        renderer.measure(MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sourceHeight, MeasureSpec.EXACTLY))
        renderer.layout(0, 0, 1000, sourceHeight)
        contentDescription = layout.name
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(android.graphics.Color.rgb(10, 11, 13))
        val scale = minOf(width / 1000f, height / sourceHeight.toFloat())
        canvas.translate((width - 1000 * scale) / 2, (height - sourceHeight * scale) / 2)
        canvas.scale(scale, scale)
        renderer.draw(canvas)
        canvas.restore()
    }
}

@Composable
internal fun InputColorRow(host: MenuHost, value: Int?, onPick: (Int?) -> Unit) {
    val open = host.open == "layout-color"
    val tints = listOf<Pair<Int?, Int>>(null to R.string.ctrl_tint_gray) + ControllerPrefs.tints
    SettingsRow(stringResource(R.string.ctrl_color), null, highlighted = open) {
        Box {
            ValueChip(tints.firstOrNull { it.first == value }?.second?.let { stringResource(it) }
                ?: stringResource(R.string.ctrl_custom), open, modifier = Modifier.testTag("setting-layout-color")) {
                host.open = if (open) null else "layout-color"
            }
            AnchoredMenu(open, onDismiss = { if (host.open == "layout-color") host.open = null }, title = stringResource(R.string.ctrl_color)) { firstItemFocus ->
                tints.forEachIndexed { index, (color, name) ->
                    MenuItem(stringResource(name), checked = value == color, leading = {
                        Box(Modifier.size(14.dp).background(Color(color ?: GRAY_SWATCH), CircleShape).border(1.dp, Color.White.copy(alpha = .35f), CircleShape))
                    }, focusRequester = if (index == 0) firstItemFocus else null) {
                        onPick(color)
                        host.open = null
                    }
                }
            }
        }
    }
}

private const val GRAY_SWATCH = 0xFF26292E.toInt()
