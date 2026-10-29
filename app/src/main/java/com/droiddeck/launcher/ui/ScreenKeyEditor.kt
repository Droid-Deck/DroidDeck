package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.SCREEN_KEY_CIRCLE
import com.droiddeck.launcher.input.SCREEN_KEY_DPAD
import com.droiddeck.launcher.input.SCREEN_KEY_PILL
import com.droiddeck.launcher.input.SCREEN_KEY_RECT
import com.droiddeck.launcher.input.SCREEN_KEY_ROUND
import com.droiddeck.launcher.input.SCREEN_KEY_STICK
import com.droiddeck.launcher.input.SCREEN_KEY_TRIGGER
import com.droiddeck.launcher.input.ScreenKeys
import com.droiddeck.launcher.input.pcKeyLabel
import com.droiddeck.launcher.input.screenKeyDirections
import java.io.File
import kotlin.math.roundToInt

private val PANEL_WIDTH = 600.dp
private val CHIP_PADDING = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)

@Composable
fun ScreenKeyEditor(
    codes: List<Int>,
    keyY: Float,
    size: Int,
    opacity: Float,
    shape: Int,
    toggle: Boolean,
    labels: Boolean,
    text: String,
    hasImage: Boolean,
    onResize: (Int) -> Unit,
    onOpacity: (Float) -> Unit,
    onShape: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
    onLabels: (Boolean) -> Unit,
    onText: (String) -> Unit,
    onImage: (String) -> Unit,
    onClearImage: () -> Unit,
    onBind: () -> Unit,
    onTextFocus: () -> Unit,
    onTextBlur: () -> Unit,
    onDirection: (Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    var offset by remember(codes) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val screen = LocalConfiguration.current
    val panelWidthPx = with(density) { PANEL_WIDTH.toPx() }
    val screenWidthPx = screen.screenWidthDp * density.density
    val screenHeightPx = screen.screenHeightDp * density.density
    val atTop = keyY > 0.45f
    val button = shape <= SCREEN_KEY_RECT
    val directions = screenKeyDirections(shape)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(context, uri, codes)?.let(onImage)
    }
    Box(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = if (atTop) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Surface(
            Modifier.widthIn(max = PANEL_WIDTH).fillMaxWidth()
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) },
            shape = RoundedCornerShape(18.dp),
            color = p.background, contentColor = p.onBackground, border = BorderStroke(1.dp, p.line2),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 2.dp, bottom = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.DragIndicator, null,
                        tint = p.onBackground.copy(alpha = 0.45f),
                        modifier = Modifier.pointerInput(panelWidthPx, screenWidthPx, screenHeightPx) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                offset = Offset(
                                    (offset.x + drag.x).coerceIn(
                                        -(screenWidthPx - panelWidthPx) / 2f + 8f,
                                        (screenWidthPx - panelWidthPx) / 2f - 8f,
                                    ),
                                    (offset.y + drag.y).coerceIn(
                                        -screenHeightPx * if (atTop) 0f else 0.75f,
                                        screenHeightPx * if (atTop) 0.75f else 0f,
                                    ),
                                )
                            }
                        },
                    )
                    Box(
                        Modifier.weight(1f).height(36.dp)
                            .background(p.line2.copy(alpha = 0.24f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { onText(it.take(24)) },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = p.onBackground, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(p.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth().onFocusChanged {
                                if (it.isFocused) onTextFocus() else onTextBlur()
                            }.focusGlide(),
                        )
                        if (text.isEmpty()) {
                            Text(
                                stringResource(R.string.screen_key_text),
                                color = p.onBackground.copy(alpha = 0.4f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (button) {
                        IconButton(onClick = onBind, modifier = Modifier.width(38.dp).focusGlide(CircleShape)) {
                            Icon(
                                Icons.Default.Keyboard, stringResource(R.string.screen_key_bind),
                                tint = p.onBackground.copy(alpha = 0.8f),
                            )
                        }
                    }
                    IconButton(onClick = { pickImage.launch("image/*") }, modifier = Modifier.width(38.dp).focusGlide(CircleShape)) {
                        Icon(
                            Icons.Default.Image, stringResource(R.string.screen_key_image),
                            tint = p.onBackground.copy(alpha = 0.8f),
                        )
                    }
                    if (hasImage) IconButton(onClick = onClearImage, modifier = Modifier.width(34.dp).focusGlide(CircleShape)) {
                        Icon(Icons.Default.Close, stringResource(R.string.screen_key_image_clear), tint = p.error)
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.width(38.dp).focusGlide(CircleShape)) {
                        Icon(Icons.Default.Delete, stringResource(R.string.common_delete), tint = p.error)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.width(34.dp).focusGlide(CircleShape)) {
                        Icon(Icons.Default.Close, null, tint = p.onBackground)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf<Pair<Int?, Int>>(
                        (if (button) SCREEN_KEY_PILL else null) to R.string.screen_key_type_button,
                        SCREEN_KEY_TRIGGER to R.string.screen_key_shape_trigger,
                        SCREEN_KEY_DPAD to R.string.touch_dpad,
                        SCREEN_KEY_STICK to R.string.deck_surface_stick,
                    ).forEach { (value, label) ->
                        val selected = if (value == null) button else shape == value
                        Chip(stringResource(label), selected, Modifier.weight(1f)) {
                            onShape(if (value == null) (if (button) shape else SCREEN_KEY_PILL) else value)
                        }
                    }
                    Box(Modifier.width(1.dp).height(20.dp).background(p.line2))
                    listOf(
                        SCREEN_KEY_PILL to R.string.screen_key_shape_pill,
                        SCREEN_KEY_ROUND to R.string.screen_key_shape_round,
                        SCREEN_KEY_CIRCLE to R.string.screen_key_shape_circle,
                        SCREEN_KEY_RECT to R.string.screen_key_shape_rect,
                    ).forEach { (value, label) ->
                        Chip(
                            stringResource(label), button && shape == value, Modifier.weight(1f), enabled = button,
                        ) { onShape(value) }
                    }
                    Box(Modifier.width(1.dp).height(20.dp).background(p.line2))
                    Text(
                        stringResource(R.string.screen_key_toggle), Modifier.padding(start = 6.dp, end = 2.dp),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    )
                    Switch(checked = toggle, onCheckedChange = onToggle, modifier = Modifier.height(30.dp).focusGlide())
                }
                if (directions.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        directions.forEachIndexed { index, arrow ->
                            val code = codes.getOrNull(index) ?: 0
                            Chip(
                                if (code == 0) arrow else "$arrow ${pcKeyLabel(context, code)}",
                                code != 0, Modifier.weight(1f),
                            ) { onDirection(index) }
                        }
                        Text(
                            stringResource(R.string.screen_key_text), Modifier.padding(start = 6.dp, end = 2.dp),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        )
                        Switch(checked = labels, onCheckedChange = onLabels, modifier = Modifier.height(30.dp).focusGlide())
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeySlider(
                        label = stringResource(R.string.screen_key_size),
                        value = "$size",
                        current = size.toFloat(),
                        min = ScreenKeys.MIN_SIZE.toFloat(),
                        max = ScreenKeys.MAX_SIZE.toFloat(),
                        modifier = Modifier.weight(1f),
                    ) { onResize(it.roundToInt()) }
                    KeySlider(
                        label = stringResource(R.string.screen_key_opacity),
                        value = "${(opacity * 100).roundToInt()}%",
                        current = opacity,
                        min = 0.2f,
                        max = 1f,
                        modifier = Modifier.weight(1f),
                        onChange = onOpacity,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeySlider(
    label: String,
    value: String,
    current: Float,
    min: Float,
    max: Float,
    modifier: Modifier,
    onChange: (Float) -> Unit,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Slider(current, onChange, Modifier.weight(1f).height(28.dp).focusGlide(), valueRange = min..max)
        Text(
            value, Modifier.width(32.dp), style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .background(
                if (selected) p.primary.copy(alpha = 0.18f) else p.line2.copy(alpha = 0.28f),
                CircleShape,
            )
            .focusGlide(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = when {
                !enabled -> p.onBackground.copy(alpha = 0.28f)
                selected -> p.primary
                else -> p.onBackground.copy(alpha = 0.7f)
            },
        )
    }
}

private fun importImage(context: Context, uri: Uri, codes: List<Int>): String? {
    val dir = File(context.filesDir, ScreenKeys.IMAGE_DIR).apply { mkdirs() }
    val name = "key_" + codes.joinToString("_") + "_" + System.currentTimeMillis() + ".png"
    val longest = (ScreenKeys.MAX_SIZE * context.resources.displayMetrics.density).toInt()
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val source = maxOf(bounds.outWidth, bounds.outHeight)
        if (source <= 0) return@runCatching null
        var sample = 1
        while (source / (sample * 2) >= longest) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val scale = longest.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        try {
            val saved = File(dir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (saved) name else null
        } finally {
            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()
        }
    }.getOrNull().also { if (it == null) File(dir, name).delete() }
}
