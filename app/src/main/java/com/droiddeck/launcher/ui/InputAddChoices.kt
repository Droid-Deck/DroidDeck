package com.droiddeck.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*

/** One controller input the editor can add: a control of [kind] that copies stock control [stock], labelled [name]. */
internal data class InputAddChoice(
    val key: String, val name: String, val kind: InputElementKind, val control: InputControl,
    val stock: List<String>, val icon: Int = 0,
) {
    val pill get() = kind == InputElementKind.TRIGGER || control == InputControl.LB || control == InputControl.RB
}

/** A stock group the editor can add as one set: [members] placed like [arrangement], in a unit square that spans [size] of the surface height. */
internal data class InputAddGroup(
    val key: String, val name: String, val group: InputControlGroup, val members: List<InputAddChoice>, val arrangement: List<SurfaceBounds>,
    val size: Float,
)

private val BUTTONS = listOf(InputControl.A, InputControl.B, InputControl.X, InputControl.Y,
    InputControl.UP, InputControl.DOWN, InputControl.LEFT, InputControl.RIGHT)
private val CONTROLS = listOf(InputControl.LS, InputControl.RS, InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT,
    InputControl.L3, InputControl.R3, InputControl.L4, InputControl.R4, InputControl.L5, InputControl.R5, InputControl.SELECT, InputControl.START, InputControl.GUIDE, InputControl.QAM)
private val SHOULDERS = setOf(InputControl.LB, InputControl.RB, InputControl.LT, InputControl.RT)

private fun controlChoice(control: InputControl) = InputAddChoice("control-${control.id}",
    if (control in SHOULDERS) control.id.uppercase() else control.label, InputElementKind.from(control), control,
    listOf("legacy:${control.id}"))

internal fun inputAddChoices(context: Context, trackpads: Boolean): List<InputAddChoice> =
    (BUTTONS + CONTROLS).map(::controlChoice) +
        InputAddChoice("control-keyboard", context.getString(R.string.input_add_keyboard), InputElementKind.BUTTON, InputControl.A,
            listOf("builtin:keyboard"), InputButtonIcons.KEYBOARD) +
        if (trackpads) listOf(InputControl.LEFT_PAD, InputControl.RIGHT_PAD).map(::controlChoice) else emptyList()

private fun cross(order: List<InputControl>) = order.zip(listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f)).map { (control, d) ->
    control to SurfaceBounds(.34f + d.first * .3f, .34f + d.second * .3f, .66f + d.first * .3f, .66f + d.second * .3f)
}

private fun shoulders(outer: InputControl, inner: InputControl, outerLeft: Boolean): List<Pair<InputControl, SurfaceBounds>> {
    val left = SurfaceBounds(0f, .37f, .47f, .63f)
    val right = SurfaceBounds(.53f, .37f, 1f, .63f)
    return listOf(outer to if (outerLeft) left else right, inner to if (outerLeft) right else left)
}

internal fun inputAddGroups(context: Context): List<InputAddGroup> = InputControlGroup.entries.filter { it.controls.size > 1 }.map { group ->
    val (placed, size) = when (group) {
        InputControlGroup.DPAD -> cross(listOf(InputControl.UP, InputControl.RIGHT, InputControl.DOWN, InputControl.LEFT)) to .4f
        InputControlGroup.FACE -> cross(listOf(InputControl.Y, InputControl.B, InputControl.A, InputControl.X)) to .4f
        InputControlGroup.LEFT_SHOULDER -> shoulders(InputControl.LT, InputControl.LB, outerLeft = true) to .6f
        else -> shoulders(InputControl.RT, InputControl.RB, outerLeft = false) to .6f
    }
    InputAddGroup("group-" + group.name.lowercase().replace('_', '-'), context.getString(group.label()), group,
        placed.map { controlChoice(it.first) }, placed.map { it.second }, size)
}

/** The control [this] adds, as control [id] in [b]; a stick takes its look and adaptive behaviour from the layout's [stick]. */
internal fun InputAddChoice.element(b: SurfaceBounds, id: String, stick: InputSurfaceElement? = null): InputSurfaceElement {
    val e = InputSurfaceElement(control, b.left, b.top, b.width, b.height, id, kind, name, icon = icon,
        buttonShape = if (pill) InputButtonShape.PILL else InputButtonShape.CIRCLE)
    return if (kind == InputElementKind.STICK && stick != null) e.copy(floating = stick.floating, hideWhenIdle = stick.hideWhenIdle, buttonShape = stick.buttonShape) else e
}

/** The control [choice] adds, in [bounds] of a square preview. */
internal fun InputAddChoice.preview(bounds: SurfaceBounds? = null): InputSurfaceElement = element(bounds ?: when {
    pill -> SurfaceBounds(0f, .25f, 1f, .75f)
    kind == InputElementKind.STICK -> SurfaceBounds(0f, 0f, 1f, 1f)
    kind == InputElementKind.TRACKPAD -> SurfaceBounds(.04f, .04f, .96f, .96f)
    else -> SurfaceBounds(.12f, .12f, .88f, .88f)
}, key)

internal fun InputAddGroup.preview() = members.zip(arrangement) { member, b -> member.preview(b) }

/** One tile of the Add control panel. */
private class AddTileItem(val key: String, val name: String, val preview: List<InputSurfaceElement>, val enabled: Boolean, val onClick: () -> Unit)

private val TileWidth = 88.dp
private val MiniSize = 64.dp

/** The Add control panel: tabs of single controls and stock groups, each tile drawn like the real controls in [style]. */
@Composable
internal fun AddControlPanel(choices: List<InputAddChoice>, groups: List<InputAddGroup>, available: (InputAddChoice) -> Boolean,
    groupAvailable: (InputAddGroup) -> Boolean, style: InputSurfaceStyle, onAdd: (InputAddChoice) -> Unit, onAddGroup: (InputAddGroup) -> Unit,
    onClose: () -> Unit) {
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    val screen = LocalConfiguration.current
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val keys = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val look = remember(style) { style.copy(opacity = 100, removed = emptySet()) }
    val minis = rememberControlMinis(look)
    fun pick(add: () -> Unit) { if (shown.targetState) { add(); close() } }
    val tiles = if (tab == 0) choices.map { c -> AddTileItem(c.key, c.name, listOf(c.preview()), available(c)) { pick { onAdd(c) } } }
        else groups.map { g -> AddTileItem(g.key, g.name, g.preview(), groupAvailable(g)) { pick { onAddGroup(g) } } }
    val firstEnabled = tiles.firstOrNull { it.enabled }
    val tabs = listOf(0 to stringResource(R.string.input_add_tab_buttons), 1 to stringResource(R.string.input_add_tab_groups))
    AppDialog(shown, close, "add-control", wide = false, maxWidth = 640.dp, scrollable = false,
        modifier = Modifier.testTag("input-add-panel").height((screen.screenHeightDp * .85f).dp)) {
        DialogTitleRow(stringResource(R.string.input_add_element), close, closeModifier = Modifier.testTag("input-add-close"), closeTouchSize = 48.dp)
        SegmentedTabs(tabs, tab, tabModifier = { Modifier.testTag(if (it == 0) "input-add-tab-buttons" else "input-add-tab-groups") }) { tab = it }
        if (tiles.any { !it.enabled }) Text(stringResource(R.string.input_add_full, MaxSurfaceControls), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("input-add-full"))
        BoxWithConstraints(Modifier.weight(1f)) {
            val columns = ((maxWidth + 6.dp) / (TileWidth + 6.dp)).toInt().coerceAtLeast(1)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in tiles.chunked(columns)) Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (tile in row) key(tile.key) {
                        ListRow(tile.enabled, focus = if (tile === firstEnabled) first else null, onClick = tile.onClick,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("input-add-${tile.key}")
                                .then(if (tile === firstEnabled) Modifier.onFocusChanged { focused = it.isFocused } else Modifier)) {
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Image(minis(tile.key, tile.preview), contentDescription = null, modifier = Modifier.size(MiniSize))
                                Text(tile.name, fontSize = 13.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onBackground, minLines = 2, maxLines = 3,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (keys && firstEnabled != null) LaunchedEffect(Unit) {
        repeat(10) { withFrameNanos { }; if (focused) return@LaunchedEffect; runCatching { first.requestFocus() } }
    }
}

/** Tile images, each drawn once by the real controls view into a still image, so a preview matches the canvas and takes no touches. */
@Composable
private fun rememberControlMinis(style: InputSurfaceStyle): (String, List<InputSurfaceElement>) -> ImageBitmap {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { MiniSize.roundToPx() }
    val drawn = remember(style, px) { HashMap<String, ImageBitmap>() }
    return { key, elements ->
        drawn.getOrPut(key) {
            val view = InputSurfaceView(context, customLayout = elements, surfaceStyle = style, haptics = false)
            val spec = View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY)
            view.measure(spec, spec)
            view.layout(0, 0, px, px)
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }.asImageBitmap()
        }
    }
}
