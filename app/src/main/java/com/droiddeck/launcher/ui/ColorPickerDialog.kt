package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** A small colour square; pressing it opens the colour picker. */
@Composable
internal fun ColorCube(color: Color, description: String, focus: FocusRequester? = null, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).size(44.dp).clip(shape).background(color)
            .border(1.dp, pal.line2, shape).glideBorder(hot, shape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    )
}

/** Colour wheel (touch), hue / saturation / brightness sliders (controller and touch) and a hex box for one colour. */
@Composable
internal fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val start = remember { ThemeColors.toHsv(initial) }
    var hue by remember { mutableStateOf(start.h) }
    var sat by remember { mutableStateOf(start.s) }
    var value by remember { mutableStateOf(start.v) }
    var hexText by remember { mutableStateOf(ThemeColors.hex(initial)) }
    val current = ThemeColors.fromHsv(hue, sat, value)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon { first } }
    fun picked(h: Float = hue, s: Float = sat, v: Float = value) {
        hue = h.coerceIn(0f, 360f); sat = s.coerceIn(0f, 1f); value = v.coerceIn(0f, 1f)
        hexText = ThemeColors.hex(ThemeColors.fromHsv(hue, sat, value))
    }
    AppDialog(shown, close, "colorPicker", wide = false, maxWidth = 460.dp) {
        DialogTitleRow(title, close)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ColorWheel(hue, sat, value) { h, s -> picked(h = h, s = s) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Swatch(Color(initial))
                Swatch(Color(current))
            }
        }
        ValueSlider(hue.toInt(), 0..360, step = 5, enabled = true, label = stringResource(R.string.theme_color_hue), format = { "$it°" },
            modifier = Modifier.focusRequester(first)) { picked(h = it.toFloat()) }
        ValueSlider((sat * 100).toInt(), 0..100, step = 2, enabled = true, label = stringResource(R.string.theme_color_saturation), format = { "$it%" }) { picked(s = it / 100f) }
        ValueSlider((value * 100).toInt(), 0..100, step = 2, enabled = true, label = stringResource(R.string.theme_color_brightness), format = { "$it%" }) { picked(v = it / 100f) }
        AdbTextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                ThemeColors.parseHex(text)?.let { argb -> ThemeColors.toHsv(argb).let { hsv -> hue = hsv.h; sat = hsv.s; value = hsv.v } }
            },
            label = "", keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, placeholder = stringResource(R.string.theme_color_hex),
            onDone = { ThemeColors.parseHex(hexText)?.let { onApply(it); close() } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.common_cancel), modifier = Modifier.weight(1f), onClick = close)
            PrimaryButton(stringResource(R.string.common_ok), enabled = ThemeColors.parseHex(hexText) != null, modifier = Modifier.weight(1f)) {
                ThemeColors.parseHex(hexText)?.let { onApply(it); close() }
            }
        }
    }
}

@Composable
private fun Swatch(color: Color) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(48.dp).clip(shape).background(color).border(1.dp, LocalPalette.current.line2, shape))
}

/** Hue around the circle and saturation out from the middle, at full brightness; brightness has its own slider. */
@Composable
private fun ColorWheel(hue: Float, sat: Float, value: Float, onPick: (hue: Float, sat: Float) -> Unit) {
    val rim = MaterialTheme.colorScheme.onBackground
    val size = 168.dp
    fun at(offset: Offset, side: Float) {
        val center = side / 2f
        val dx = offset.x - center
        val dy = offset.y - center
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        onPick((angle + 360f) % 360f, min(1f, hypot(dx, dy) / center))
    }
    Canvas(
        Modifier.size(size)
            .pointerInput(Unit) { detectTapGestures { at(it, this.size.width.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> at(change.position, this.size.width.toFloat()); change.consume() } },
    ) {
        val radius = this.size.minDimension / 2f
        drawCircle(Brush.sweepGradient(List(7) { Color.hsv(it * 60f, 1f, 1f) }), radius)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = radius), radius)
        val theta = Math.toRadians(hue.toDouble())
        val marker = Offset(radius + (kotlin.math.cos(theta) * sat * radius).toFloat(), radius + (kotlin.math.sin(theta) * sat * radius).toFloat())
        drawCircle(Color.Black.copy(alpha = 0.55f), 9.dp.toPx(), marker)
        drawCircle(rim, 7.dp.toPx(), marker)
        drawCircle(Color.hsv(hue, sat, value), 5.dp.toPx(), marker)
    }
}
