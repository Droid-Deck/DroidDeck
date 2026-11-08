package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import com.droiddeck.launcher.input.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.droiddeck.launcher.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun InputElementProperties(element: InputSurfaceElement, action: InputAction? = null, onChange: (InputSurfaceElement) -> Unit) {
    val current by rememberUpdatedState(element)
    val change by rememberUpdatedState(onChange)
    OutlinedTextField(element.name, { onChange(element.copy(name = it.take(80))) }, label = { Text(stringResource(R.string.input_name)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
    if (element.kind == InputElementKind.BUTTON || element.kind == InputElementKind.TRIGGER) {
        val host = rememberMenuHost()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                withContext(Dispatchers.IO) { InputButtonImages.import(context.applicationContext, uri) }?.let { change(current.copy(icon = 0, image = it)) }
            }
        }
        ToggleRow(host, "element-toggle", stringResource(R.string.input_toggle_button), stringResource(R.string.input_toggle_button_hint), element.toggle) { onChange(element.copy(toggle = it)) }
        ChoiceRow(host, "element-shape", stringResource(R.string.input_button_shape), null,
            InputButtonShape.entries.map { it to stringResource(when (it) { InputButtonShape.ROUNDED -> R.string.input_shape_rounded; InputButtonShape.PILL -> R.string.input_shape_pill; InputButtonShape.CIRCLE -> R.string.input_shape_circle; InputButtonShape.RECTANGLE -> R.string.input_shape_rectangle }) },
            element.buttonShape) { onChange(element.copy(buttonShape = it)) }
        val open = host.open == "element-icon"
        SettingsRow(stringResource(R.string.input_button_symbol), null, highlighted = open) {
            Box {
                ValueChip(if (element.image.isNotEmpty()) stringResource(R.string.input_imported_image) else if (element.icon == 0) stringResource(R.string.input_button_text) else InputButtonIcons.label(context, element.icon), open) { host.open = if (open) null else "element-icon" }
                AnchoredMenu(open, onDismiss = { host.open = null }, title = stringResource(R.string.input_button_symbol)) { firstItemFocus ->
                    MenuItem(stringResource(R.string.input_button_text), element.icon == 0 && element.image.isEmpty(), focusRequester = firstItemFocus) { onChange(element.copy(icon = 0, image = "")); host.open = null }
                    MenuItem(stringResource(R.string.screen_key_image), element.image.isNotEmpty()) { host.open = null; pickImage.launch("image/*") }
                    for (code in InputButtonIcons.codes) MenuItem(InputButtonIcons.label(context, code), element.icon == code && element.image.isEmpty(), leading = {
                        val color = MaterialTheme.colorScheme.onSurface
                        val symbol = remember(context, code) { InputButtonIcons.drawable(context, code) }
                        val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
                        Canvas(Modifier.size(22.dp)) {
                            paint.color = android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
                            InputButtonIcons.draw(drawContext.canvas.nativeCanvas, code, size.width / 2, size.height / 2, size.minDimension, paint, symbol)
                        }
                    }) { onChange(element.copy(icon = code, image = "")); host.open = null }
                }
            }
        }
    }
    if (element.kind == InputElementKind.TRACKPAD) {
        ToggleRow(rememberMenuHost(), "element-mouse-gestures", stringResource(R.string.input_mouse_gestures), stringResource(R.string.input_mouse_gestures_hint), element.mouseGestures) { onChange(element.copy(mouseGestures = it)) }
    }
    if (element.kind == InputElementKind.STICK) {
        val host = rememberMenuHost()
        ToggleRow(host, "element-floating", stringResource(R.string.input_floating_stick), null, element.floating) { onChange(element.copy(floating = it)) }
        ToggleRow(host, "element-hide-idle", stringResource(R.string.input_hide_idle), null, element.hideWhenIdle) { onChange(element.copy(hideWhenIdle = it)) }
    }
    val host = rememberMenuHost()
    if ((element.kind == InputElementKind.BUTTON || element.kind == InputElementKind.TRIGGER) && element.icon == 0 && element.image.isEmpty() ||
        element.kind == InputElementKind.STICK && element.id.startsWith("legacy:") ||
        (element.kind == InputElementKind.STICK || element.kind == InputElementKind.DPAD) && (action as? InputAction.Directions)
            ?.let { listOf(it.up, it.right, it.down, it.left).plus(it.diagonals.orEmpty()).any { code -> code != 0 } } == true)
        ToggleRow(host, "element-label", stringResource(R.string.input_show_label), null, element.showLabel) { onChange(element.copy(showLabel = it)) }
    SliderRow(stringResource(R.string.ctrl_opacity), null, (element.opacity * 100).roundToInt().coerceIn(20, 100), 20..100, step = 5,
        format = { "$it%" }, modifier = Modifier.testTag("setting-element-opacity")) { onChange(element.copy(opacity = it / 100f)) }
}
