package com.droiddeck.launcher.ui

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.session.SessionState
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class SurfaceSnapshot(val elements: List<InputSurfaceElement>, val actions: Map<String, InputAction>,
    val clicks: Map<String, InputAction>, val scale: Float, val freeScale: Float, val removed: Set<String>)

private enum class SliderEdit { SCALE, SIZE, OPACITY }

private class ScaleStep(val base: List<InputSurfaceElement>, val baseFactor: Float, val result: List<InputSurfaceElement>, val factor: Float)

/** [place] moves the generated controls to where the game draws them at a given scale. */
internal class GamepadScaler(private val context: android.content.Context, private val width: Int, private val height: Int,
    private val safe: Rect = Rect(), private val place: (List<InputSurfaceElement>, Float) -> List<InputSurfaceElement> = { elements, _ -> elements }) {
    private fun generator(scale: Float) = LegacyGamepadLayout(context, width, height, safe, scale)
    private fun stockAt(scale: Float) = place(generator(scale).elements(), scale)
    val ids = generator(1f).elements().map { it.id }.toSet()
    // Positions follow where the kit draws the stock controls; sizes follow the plain stock, since the kit may draw at a fixed size.
    fun rescale(stored: List<InputSurfaceElement>, from: Float, to: Float) =
        rescaleBetween(stored, stockAt(from), stockAt(to), generator(from).elements(), generator(to).elements())
    fun reset(stored: List<InputSurfaceElement>) = stock(stored).let { placed -> stored.map { placed[it.id] ?: it } }
    fun stock(stored: List<InputSurfaceElement>) = place(generator(1f).regenerate(stored), 1f).associateBy { it.id }
    val geometry by lazy { stockAt(1f).associateBy { it.id } }
}

private fun InputSurfaceElement.samePlace(other: InputSurfaceElement, width: Int, height: Int) =
    abs(x - other.x) * width <= .5f && abs(y - other.y) * height <= .5f && abs(this.width - other.width) * width <= .5f && abs(this.height - other.height) * height <= .5f

private fun isStock(id: String) = id.startsWith("legacy:") || id.startsWith("builtin:")

/** Main-screen layouts start from where the game places them, fitted to the screen with the cutout kept clear, so opening the editor and saving move nothing. */
internal fun surfaceDraft(context: android.content.Context, library: InputLibrary, layout: SavedInputLayout, preset: SavedInputBindings, role: InputRole,
    safe: Rect? = null, target: Display? = null): SurfaceDraft {
    val primary = role == InputRole.PRIMARY
    val other = target?.takeIf { primary && it.displayId != Display.DEFAULT_DISPLAY }
    val (display, metrics) = other?.let { d -> context.createDisplayContext(d) to android.util.DisplayMetrics().also { @Suppress("DEPRECATION") d.getRealMetrics(it) } }
        ?: InputLibraries.primaryDisplay(context)
    val clear = safe ?: other?.let(OnScreenLayout::safeInsets) ?: OnScreenLayout.primarySafeInsets(context)
    val origin = layout.origin ?: layout.id
    // Only the main-screen gamepad is generated; second-screen layouts with the same controls keep their own stock positions.
    val generated = origin == InputLibraries.OVERLAY || origin.startsWith("migrated.layout.")
    val (w, h) = metrics.widthPixels to metrics.heightPixels
    val fits = primary && w > 0 && h > 0
    // The main screen edits the frame for its current orientation; Save writes it back beside the other one.
    val framed = if (fits) OnScreenLayout.oriented(layout, w, h) else layout
    val (rear, pads) = if (primary) OnScreenLayout.optionalControls(context) else null to null
    val gamepad = layout.elements.takeIf { e -> generated && e.any { it.id.startsWith("legacy:") } }?.let {
        val (gw, gh) = (if (primary) null else OnScreenLayout.generatedSize(context, layout, metrics.widthPixels, metrics.heightPixels))
            ?: (metrics.widthPixels to metrics.heightPixels)
        if (primary) GamepadScaler(display, gw, gh, clear) { elements, scale ->
            OnScreenLayout.kitPlaced(display, framed.copy(scale = scale), elements, gw, gh, clear, generated = true, rearButtons = rear, trackpads = pads)
        } else GamepadScaler(display, gw, gh)
    }
    val main = primary && gamepad != null && layout.elements.any { it.id in gamepad.ids }
    // Controls the game draws within half a pixel of their stored place keep it, so an untouched layout saves unchanged.
    fun inGame(elements: List<InputSurfaceElement>, frame: Float = framed.aspect): List<InputSurfaceElement> {
        val placed = OnScreenLayout.elements(display, framed.copy(elements = elements, aspect = frame, rotated = emptyList()), w, h, clear,
            rearButtons = rear, trackpads = pads)
        if (main) return placed
        val stored = elements.associateBy { it.id }
        return placed.map { p -> stored[p.id]?.takeIf { s -> s.samePlace(p, w, h) } ?: p }
    }
    val placed = if (fits) inGame(framed.elements) else layout.elements
    val shown = if (placed == framed.elements) framed else framed.copy(elements = placed, aspect = w.toFloat() / h)
    // Stock positions keep the frame they were made for, which can differ from the saved layout's.
    val defaults = InputLibraries.defaultPositions(context, library, layout)?.let { (stock, frame) ->
        when {
            fits && !main -> inGame(stock, frame.takeIf { it > 0f } ?: layout.aspect)
            frame > 0f && layout.aspect > 0f && abs(frame / layout.aspect - 1f) >= .01f ->
                OnScreenLayout.fit(stock, frame, 1f, (layout.aspect * 1000).roundToInt().coerceAtLeast(1), 1000, Rect())
            else -> stock
        }
    }
    val area = if (!primary) WHOLE_SURFACE else SurfaceBounds(clear.left.toFloat() / metrics.widthPixels, clear.top.toFloat() / metrics.heightPixels,
        1f - clear.right.toFloat() / metrics.widthPixels, 1f - clear.bottom.toFloat() / metrics.heightPixels)
    return SurfaceDraft(shown, preset, defaults, gamepad, ControllerPrefs.snap(context),
        ControllerPrefs.read(context).adaptiveSticks, area, framing = context.takeIf { fits })
}

internal class SurfaceDraft(val layout: SavedInputLayout, val preset: SavedInputBindings, val defaults: List<InputSurfaceElement>? = null,
    private val gamepad: GamepadScaler? = null, snap: Boolean = true, private val adaptiveSticks: Boolean = true,
    private val area: SurfaceBounds = WHOLE_SURFACE, private val framing: android.content.Context? = null) {
    var elements by mutableStateOf(layout.elements)
    var actions by mutableStateOf(preset.elements)
    var clicks by mutableStateOf(preset.clicks)
    var removed by mutableStateOf(layout.style.removed)
    val visible get() = elements.filterNot { it.id in removed }
    var selection by mutableStateOf<List<String>>(emptyList())
        private set
    val selected get() = selection.singleOrNull()
    private var sizeBase by mutableStateOf<Map<String, InputSurfaceElement>?>(null)
    var toolsExpanded by mutableStateOf(false)
    /** Where the collapsed handle and the open menu sit, in pixels, for the selection sliders to keep clear of. */
    var handleArea by mutableStateOf<SurfaceBounds?>(null)
    var menuArea by mutableStateOf<SurfaceBounds?>(null)
    /** The handle's touch target as canvas fractions. The handle takes every tap there, so a control wholly inside it could never be selected. */
    var handleReach by mutableStateOf<SurfaceBounds?>(null)
    var sizeOpen by mutableStateOf(false)
    /** Controls the last add() placed or restored, for the canvas pop-in. */
    var placed by mutableStateOf<List<String>>(emptyList(), neverEqualPolicy())
        private set
    var snap by mutableStateOf(snap)
    /** The Snap preference when editing began, which discarding puts back. */
    var openedSnap = snap
    /** The aspect of the display this draft was built for; Save stores it even if that display has gone. */
    var frameAspect: Float? = null
    var adding by mutableStateOf(false)
    var history by mutableStateOf<List<SurfaceSnapshot>>(emptyList())
    /** Counts the Undo and Redo steps taken. */
    var restored by mutableIntStateOf(0)
        private set
    var layoutScale by mutableFloatStateOf(layout.scale)
    private var freeScale by mutableFloatStateOf(1f)
    private var scaleStep: ScaleStep? = null
    private var sliding: SliderEdit? = null
    private val rescales get() = gamepad != null && elements.any { it.id in gamepad.ids }
    private var scaleFactor
        get() = if (rescales) layoutScale else freeScale
        set(value) { if (rescales) layoutScale = value else freeScale = value }
    val scale get() = (scaleFactor * 100).roundToInt()
    val positioned get() = (if (rescales) gamepad!!.reset(elements) else defaults?.let { elements.withPositionsOf(it) })
        ?.takeIf { it != elements || rescales && layoutScale != 1f }
    val element get() = elements.firstOrNull { it.id == selected }
    val dirty get() = elements != layout.elements || actions != preset.elements || clicks != preset.clicks || layoutScale != layout.scale ||
        removed != layout.style.removed
    var redoable by mutableStateOf<List<SurfaceSnapshot>>(emptyList())
        private set
    /** Undo steps that changed something, for the discard prompt. */
    val changeCount get() = (history + snapshot()).zipWithNext().count { (a, b) -> a != b }
    private fun snapshot() = SurfaceSnapshot(elements, actions, clicks, layoutScale, freeScale, removed)
    fun checkpoint() {
        sliding = null
        history = (history + snapshot()).takeLast(40)
        redoable = emptyList()
    }
    private fun slide(edit: SliderEdit) { if (sliding != edit) { checkpoint(); sliding = edit } }
    /** Makes the next slider change start its own undo step, as each new press on a slider does. */
    fun endSlide() { sliding = null }
    private fun load(it: SurfaceSnapshot) {
        sliding = null
        restored++
        val stockGroup = visible.selectedGroup(selection) != null
        elements = it.elements; actions = it.actions; clicks = it.clicks; layoutScale = it.scale; freeScale = it.freeScale; removed = it.removed
        val kept = selection.filter { id -> visible.any { it.id == id } }
        select(if (stockGroup && kept.size > 1) visible.groupOf(kept.first()) else kept)
    }
    fun undo() {
        sliding = null
        val previous = history.lastOrNull() ?: return
        redoable = redoable + snapshot()
        history = history.dropLast(1)
        load(previous)
    }
    fun redo() {
        sliding = null
        val next = redoable.lastOrNull() ?: return
        history = (history + snapshot()).takeLast(40)
        redoable = redoable.dropLast(1)
        load(next)
    }
    fun deleteSelection() {
        if (selection.isEmpty()) return
        checkpoint()
        val (stock, added) = selection.partition(::isStock)
        removed = removed + stock
        elements = elements.filterNot { it.id in added }; actions = actions - added.toSet(); clicks = clicks - added.toSet()
        select(emptyList())
    }
    val full get() = visible.size >= MaxSurfaceControls
    val canAdd get() = !full
    private fun hidden(choice: InputAddChoice) = choice.stock.filter { id -> id in removed && elements.any { it.id == id } }
    fun available(choice: InputAddChoice) = !full
    fun add(choice: InputAddChoice, aspect: Float) {
        if (full) return
        val hidden = hidden(choice)
        checkpoint()
        if (hidden.isNotEmpty()) {
            removed = removed - hidden.toSet()
            select(hidden)
            placed = hidden
            return
        }
        val height = when (choice.kind) {
            InputElementKind.STICK -> .36f
            InputElementKind.TRACKPAD -> .5f
            else -> .16f
        }
        val width = (height / aspect * if (choice.pill) 2f else 1f).coerceIn(.03f, 1f)
        val (x, y) = freeSurfaceSpot(visible.map { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) }, width, height, area)
        put(listOf(choice to SurfaceBounds(x, y, x + width, y + height)))
    }
    private fun hidden(group: InputAddGroup) = group.members.flatMap(::hidden)
    fun available(group: InputAddGroup) = visible.size + hidden(group).ifEmpty { group.members }.size <= MaxSurfaceControls
    /** Brings back the group's deleted stock controls, or else adds a new set laid out like the stock one, selected as one. */
    fun add(group: InputAddGroup, aspect: Float) {
        if (!available(group)) return
        checkpoint()
        val hidden = hidden(group)
        if (hidden.isNotEmpty()) {
            removed = removed - hidden.toSet()
            select(visible.groupOf(hidden.first()))
            placed = hidden
            return
        }
        val live = group.members.map { m -> elements.firstOrNull { it.id == m.stock.first() } }
        val stock = if (live.all { it != null }) live else group.members.map { stockGeometry[it.stock.first()] }
        val rects = if (stock.all { it != null }) stock.map { SurfaceBounds(it!!.x, it.y, it.x + it.width, it.y + it.height) }
            else group.arrangement.let { a ->
                val span = minOf(group.size, aspect / (a.maxOf { it.right } - a.minOf { it.left }), 1f / (a.maxOf { it.bottom } - a.minOf { it.top }))
                a.map { SurfaceBounds(it.left * span / aspect, it.top * span, it.right * span / aspect, it.bottom * span) }
            }
        val left = rects.minOf { it.left }
        val top = rects.minOf { it.top }
        val (x, y) = freeSurfaceSpot(visible.map { SurfaceBounds(it.x, it.y, it.x + it.width, it.y + it.height) },
            rects.maxOf { it.right } - left, rects.maxOf { it.bottom } - top, area)
        put(group.members.zip(rects.map { SurfaceBounds(it.left - left + x, it.top - top + y, it.right - left + x, it.bottom - top + y) }), InputControlGroup.newSetKey(group.group))
    }
    private fun put(added: List<Pair<InputAddChoice, SurfaceBounds>>, group: String = "") {
        val stick = elements.firstOrNull { it.kind == InputElementKind.STICK && isStock(it.id) } ?: elements.firstOrNull { it.kind == InputElementKind.STICK }
            ?: InputSurfaceElement(InputControl.LS, 0f, 0f, 0f, 0f, floating = adaptiveSticks, hideWhenIdle = adaptiveSticks, buttonShape = InputButtonShape.CIRCLE)
        val created = added.map { (choice, b) -> choice.element(b, copyId(choice.stock.first()), stick).bounded().copy(group = group) }
        val ids = created.map { it.id }
        elements = (elements + created).moveSelection(ids, 0f, 0f, area)
        select(ids)
        placed = ids
    }
    fun scaleAll(percent: Int) {
        if (percent == scale) return
        slide(SliderEdit.SCALE)
        // `scaled` clamps to the screen, so scaling an already scaled layout is lossy: reuse the pre-scale base while the elements are unchanged.
        val (base, from) = scaleStep?.takeIf { it.result == elements && it.factor == scaleFactor }?.let { it.base to it.baseFactor }
            ?: (elements to scaleFactor)
        val factor = percent / 100f
        elements = (if (rescales) {
            val others = base.filterNot { it.id in gamepad!!.ids }.scaled(factor / from).associateBy { it.id }
            gamepad!!.rescale(base, from, factor).map { others[it.id] ?: it }
        } else base.scaled(factor / from)).keptIn(area)
        scaleFactor = factor
        scaleStep = ScaleStep(base, from, elements, factor)
    }
    /** Moves controls that sit wholly under the handle to just below it, where a tap reaches them. */
    fun clearHandle() {
        val h = handleReach ?: return
        fun InputSurfaceElement.buried() = id !in removed && x >= h.left && x + width <= h.right && y >= h.top && y + height <= h.bottom
        if (elements.none { it.buried() }) return
        elements = elements.map { if (it.buried()) it.copy(y = h.bottom) else it }
    }
    fun select(ids: List<String>) {
        if (ids == selection) return
        sliding = null
        selection = ids; sizeBase = elements.filter { it.id in ids }.associateBy { it.id }
    }
    private val stockGeometry by lazy { gamepad?.geometry.orEmpty() + defaults.orEmpty().associateBy { it.id } }
    private fun sizeReference() = stockGeometry.takeIf { stock -> selection.isNotEmpty() && selection.all { it in stock } } ?: sizeBase
    val selectionSize get() = sizeReference()?.let { elements.sizePercent(selection, it).roundToInt() }
    fun resizeSelection(percent: Int) {
        val reference = sizeReference() ?: return
        if (percent == selectionSize) return
        val resized = elements.scaleSelection(selection, percent / elements.sizePercent(selection, reference), area)
        if (resized.zip(elements).all { (a, b) -> abs(a.x - b.x) < 1e-5f && abs(a.y - b.y) < 1e-5f && abs(a.width - b.width) < 1e-5f &&
                abs(a.height - b.height) < 1e-5f }) return
        slide(SliderEdit.SIZE)
        elements = resized
    }
    /** The group the selection makes up: a stock group, or a set added from Add whose buttons are exactly that group's. */
    val selectionGroup: InputControlGroup? get() = visible.selectedGroup(selection) ?: selection.takeIf { it.size > 1 }?.let { ids ->
        val targets = ids.map { id -> (actions[id] as? InputAction.Native)?.target
            ?: elements.firstOrNull { it.id == id && copiedStock(id) != null }?.control ?: return@let null }
        InputControlGroup.entries.firstOrNull { it.controls.size == targets.size && it.controls == targets.toSet() }
    }
    /** What the selection is called: its group, else its one control's name, else how many controls it holds. */
    fun selectionName(context: android.content.Context): String = selectionGroup?.let { context.getString(it.label()) }
        ?: if (selection.size > 1) context.resources.getQuantityString(R.plurals.input_editor_controls, selection.size, selection.size)
        else elements.firstOrNull { it.id in selection }?.let { e ->
            PadKit.forOverlay(context, layout)?.takeIf { it.native(e) }?.name(e.control) ?: e.name
        }.orEmpty()
    val selectionOpacity get() = elements.firstOrNull { it.id in selection }?.opacity ?: 1f
    fun setSelectionOpacity(percent: Int) {
        if (elements.filter { it.id in selection }.all { (it.opacity * 100).roundToInt() == percent }) return
        slide(SliderEdit.OPACITY)
        elements = elements.map { if (it.id in selection) it.copy(opacity = percent / 100f) else it }
    }
    private val hiddenStock get() = removed.filter { id -> elements.any { it.id == id } }
    private val addedIds get() = elements.map { it.id }.filterNot { isStock(it) || it in stockGeometry }.toSet()
    private val restyled get() = elements.any { e -> stockGeometry[e.id]?.let { it.opacity != e.opacity } == true }
    val resettable get() = positioned != null || hiddenStock.isNotEmpty() || addedIds.isNotEmpty() || restyled
    /** Returns to the stock layout: stock positions, sizes and opacity, every deleted stock control back, added controls gone. */
    fun resetLayout() {
        if (!resettable) return
        checkpoint()
        val added = addedIds
        positioned?.let { elements = it }
        elements = elements.map { e -> stockGeometry[e.id]?.let { e.copy(opacity = it.opacity) } ?: e }
        scaleFactor = 1f
        elements = elements.filterNot { it.id in added }; actions = actions - added; clicks = clicks - added
        removed = removed - hiddenStock.toSet()
        select(selection.filterNot { it in added })
    }
    /** [latest] with this draft's layout and actions written over the saved ones. */
    fun saveTo(latest: InputLibrary, aspect: Float?): InputLibrary {
        val l = latest.layouts.first { it.id == layout.id }
        val p = latest.preset(preset.id)
        val frame = aspect ?: layout.aspect
        val saved = framing?.let { OnScreenLayout.withFrame(it, l, elements, frame, layoutScale) }
            ?: l.copy(elements = elements, aspect = frame, scale = layoutScale)
        val drafted = elements.map { it.id }.toSet()
        val other = (saved.elements + saved.rotated).filter { it.id !in drafted }.associateBy { it.id }
        val elsewhere = l.style.removed.filter { it in other }
        return latest.withLayout(saved.copy(style = l.style.copy(removed = removed.filter { it in drafted }.toSet() + elsewhere))).withPreset(p.copy(
            elements = p.elements.filterKeys { it in other } + actions.filterKeys { it in drafted },
            clicks = p.clicks.filterKeys { other[it]?.hasClick == true } + clicks.filterKeys { id -> elements.any { it.id == id && it.hasClick } }))
    }
}

/** The shape a layout for [display] is stored at; layouts for the device's own screen are landscape even while it is turned. */
@Suppress("DEPRECATION")
private fun displayAspect(display: Display, landscape: Boolean): Float? = android.util.DisplayMetrics().also(display::getRealMetrics)
    .takeIf { it.widthPixels > 0 && it.heightPixels > 0 }?.let {
        val (w, h) = if (landscape) maxOf(it.widthPixels, it.heightPixels) to minOf(it.widthPixels, it.heightPixels) else it.widthPixels to it.heightPixels
        (w.toFloat() / h).coerceIn(.05f, 20f)
    }

internal class HeldSurfaceDrafts : ViewModel() { val drafts = mutableMapOf<String, SurfaceDraft>() }

@Composable
fun InputSurfaceDialog(profile: String, onDismiss: () -> Unit, onChanged: () -> Unit,
    role: InputRole = InputRole.SECONDARY, layoutId: String? = null, bindingsId: String? = null) {
    val context = LocalContext.current
    val host = LocalInputActivity.current ?: context.findActivity() as? ComponentActivity
    val library = remember { InputLibraries.read(context) }
    val savedLayout = remember { library.layouts.first { it.id == (layoutId ?: library.profile(profile).parts.getValue(role).layout) } }
    val savedBindings = remember { library.preset(bindingsId ?: library.profile(profile).binding(role)) }
    val layoutUsers = library.usersOfLayout(savedLayout.id).size
    val held = remember(host) { host?.let { ViewModelProvider(it)[HeldSurfaceDrafts::class.java] } }
    val heldKey = "$profile/$role/${savedLayout.id}/${savedBindings.id}"
    val manager = remember { context.getSystemService(DisplayManager::class.java) }
    var revision by remember { mutableIntStateOf(0) }
    var discard by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val target = remember(revision, role) {
        if (host is SessionActivity) host.inputEditorDisplay(role)
        else if (role == InputRole.PRIMARY) host?.window?.decorView?.display
        else {
            val displays = SecondScreenDisplays.available(manager, host?.window?.decorView?.display?.displayId ?: Display.DEFAULT_DISPLAY)
            manager.getDisplay(displays.firstOrNull { it.id == SessionState.secondScreenDisplay }?.id ?: displays.firstOrNull()?.id ?: -1)
        }
    }
    val remote = target != null && target.displayId != host?.window?.decorView?.display?.displayId
    val kept = remember { arrayOf(held?.drafts?.remove(heldKey)) }
    // The editor turns a portrait screen landscape only after it opens; a draft not yet edited is built again for the turned screen.
    val draft = remember(LocalConfiguration.current.orientation.takeUnless { remote }) {
        kept[0]?.takeIf { it.history.isNotEmpty() || it.redoable.isNotEmpty() }
            ?: surfaceDraft(context, library, savedLayout, savedBindings, role, target = target).also { fresh -> kept[0]?.let { fresh.openedSnap = it.openedSnap }; kept[0] = fresh }
    }
    // A font or language change restarts the activity; the next editor picks the unsaved draft up from here.
    DisposableEffect(draft) { onDispose { if (host?.isChangingConfigurations == true) held?.drafts?.set(heldKey, draft) } }
    if (draft.frameAspect == null) draft.frameAspect = target?.let { displayAspect(it, landscape = !remote) }
    val aspect = draft.frameAspect
    if (target != null && !remote && host != null) DisposableEffect(host) { onDispose(holdLandscape(host)) }
    fun leave() { if (draft.dirty) discard = true else onDismiss() }
    fun discardChanges() { ControllerPrefs.setSnap(context, draft.openedSnap); onDismiss() }
    fun delete() {
        if (draft.selection.isEmpty()) return
        val name = draft.selectionName(context)
        draft.deleteSelection()
        Toast.makeText(context, context.getString(R.string.input_editor_deleted, name), Toast.LENGTH_SHORT).show()
    }
    fun save() {
        runCatching {
            InputLibraries.update(context) { draft.saveTo(it, aspect) }
        }.onSuccess {
            onChanged()
            Toast.makeText(context.applicationContext, R.string.input_editor_saved, Toast.LENGTH_SHORT).show()
            onDismiss()
        }.onFailure { failure = it.message ?: context.getString(R.string.input_save_failed) }
    }
    DisposableEffect(manager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { revision++ }
            override fun onDisplayRemoved(id: Int) { revision++ }
            override fun onDisplayChanged(id: Int) = Unit
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    if (remote && host != null) DisposableEffect(target!!.displayId, host) {
        val presentation = SurfaceEditorPresentation(host, target, transparent = host is SessionActivity && role == InputRole.PRIMARY) {
            SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY, onDelete = ::delete)
        }
        (host as? SessionActivity)?.inputWindowShown(target.displayId)
        runCatching { presentation.show() }.onFailure { failure = it.message }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !host.screenIsOff()) presentation.hide()
            if (event == Lifecycle.Event.ON_START) runCatching { presentation.show() }
        }
        host.lifecycle.addObserver(observer)
        onDispose {
            host.lifecycle.removeObserver(observer)
            presentation.dismiss()
            (host as? SessionActivity)?.inputWindowClosed(target.displayId)
        }
    }
    // The editor's function references stay bound to the draft they were first composed with, so a rebuilt draft gets a fresh editor.
    key(draft) {
        // Compose 1.6 custom dialog sizing subtracts system bars even when they are hidden.
        Dialog(onDismissRequest = ::leave, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
            val editorWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            DisposableEffect(editorWindow) {
                editorWindow?.prepareInputEditor()
                editorWindow?.setWindowAnimations(0)
                if (host is SessionActivity && role == InputRole.PRIMARY) editorWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                onDispose { }
            }
            val paneName = stringResource(R.string.input_edit_layout)
            Surface(Modifier.fillMaxSize().semantics { paneTitle = paneName }, color = if (host is SessionActivity && role == InputRole.PRIMARY && !remote)
                androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) {
                BackHandler {
                    when {
                        draft.sizeOpen -> draft.sizeOpen = false
                        draft.selection.isNotEmpty() -> draft.select(emptyList())
                        else -> leave()
                    }
                }
                when {
                    target == null -> SettingsPage(rememberMenuHost(), savedLayout.name, ::leave) {
                        Text(stringResource(R.string.input_display_unavailable))
                        PrimaryButton(stringResource(R.string.gesture_save_changes)) { save() }
                    }
                    remote -> SettingsPage(rememberMenuHost(), stringResource(R.string.input_editor_remote), ::leave,
                        lede = if (layoutUsers > 1) stringResource(R.string.input_editor_shared, layoutUsers) else savedLayout.name) {
                        EditorMenuBar(draft, ::save, ::leave)
                        SurfaceSelection(draft, ::delete)
                    }
                    else -> Box(Modifier.fillMaxSize()) {
                        SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY, onDelete = ::delete)
                        FloatingSelectionSliders(draft)
                        FloatingSurfaceTools(draft, ::save, ::leave)
                        LaunchedEffect(Unit) { Toast.makeText(context, R.string.input_editor_tap_hint, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
    }
    if (draft.adding) AddControlDialog(draft, aspect ?: (16f / 9f))
    if (discard) ConfirmDialog(stringResource(R.string.input_editor_discard_title),
        draft.changeCount.let { if (it > 0) pluralStringResource(R.plurals.input_editor_discard_count, it, it) else stringResource(R.string.input_editor_discard_unsaved) },
        stringResource(R.string.input_editor_discard_confirm), onConfirm = ::discardChanges, onDismiss = { discard = false })
    failure?.let { AlertDialog(onDismissRequest = { failure = null }, title = { Text(stringResource(R.string.input_editor_save_failed)) }, text = { Text(it) },
        confirmButton = { PadTextButton(onClick = { failure = null }) { Text(stringResource(R.string.common_ok)) } }) }
}

@Composable
private fun AddControlDialog(draft: SurfaceDraft, aspect: Float) {
    val context = LocalContext.current
    val trackpads = draft.layout.supports(InputRole.SECONDARY) || draft.elements.any { it.kind == InputElementKind.TRACKPAD }
    val choices = remember(trackpads) { inputAddChoices(context, trackpads) }
    val groups = remember { inputAddGroups(context) }
    AddControlPanel(choices, groups, draft::available, draft::available, draft.layout.style,
        onAdd = { draft.add(it, aspect); draft.toolsExpanded = false }, onAddGroup = { draft.add(it, aspect); draft.toolsExpanded = false },
        onClose = { draft.adding = false })
}

@Composable
private fun SurfaceCanvas(draft: SurfaceDraft, transparent: Boolean = false, onDelete: () -> Unit) {
    val popped = remember { arrayOf(draft.placed) }
    AndroidView(factory = { InputSurfaceEditorView(it) }, modifier = Modifier.fillMaxSize().testTag("input-layout-canvas"),
        update = { view ->
            view.setBackgroundColor(if (transparent) android.graphics.Color.TRANSPARENT else android.graphics.Color.rgb(10, 11, 13))
            view.artwork = PadKit.forOverlay(view.context, draft.layout)
            view.style = draft.layout.style.copy(removed = emptySet()); view.elements = draft.visible; view.selection = draft.selection; view.snap = draft.snap
            view.restored = draft.restored
            view.isEnabled = !draft.adding
            view.sizeReadout = { draft.selectionSize }
            if (draft.placed !== popped[0]) { popped[0] = draft.placed; view.popIn(draft.placed) }
            view.onSelect = { draft.select(it) }
            view.onBeginEdit = draft::checkpoint
            view.onDelete = onDelete
            view.onChange = { changed ->
                val byId = changed.associateBy { it.id }
                draft.elements = draft.elements.map { byId[it.id] ?: it }
            }
        })
}

internal const val MaxSurfaceControls = 64

private val PanelShape = RoundedCornerShape(18.dp)
private val SliderWidth = 200.dp
private val HandleSize = 40.dp
private val ToolsMargin = 8.dp
private val TouchTarget = 48.dp
/** Matches the disabled Add and Save buttons beside the icons. */
private val DisabledAlpha = 0.5f
private val SnapStart = 4.dp
private val SnapEnd = 10.dp
/** How far the selection outline sits outside its controls, and how far the badge and resize handles reach past its corners. */
private val OutlinePad = 10.dp
private val CornerReach = 18.dp

/** The editor's panel surface, the one its tools have always floated on. */
@Composable
private fun EditorPanel(modifier: Modifier = Modifier, shape: Shape = PanelShape, content: @Composable () -> Unit) =
    Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 10.dp, content = content)

/** Reports whether a finger is down anywhere inside, without taking the touch from what is under it. */
private fun Modifier.pressing(onChange: (Boolean) -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onChange(true)
        try {
            do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
        } finally { onChange(false) }
    }
}

@Composable
private fun FloatingSurfaceTools(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit) {
    val open = draft.toolsExpanded
    val handleFocus = remember { FocusRequester() }
    val collapseFocus = remember { FocusRequester() }
    var menuFocused by remember { mutableStateOf(false) }
    var moveFocus by remember { mutableStateOf(false) }
    fun toggle() { moveFocus = menuFocused; draft.sizeOpen = false; draft.toolsExpanded = !open }
    val handleShown = remember { MutableTransitionState(false) }
    handleShown.targetState = !open
    LaunchedEffect(open) {
        if (moveFocus) { withFrameNanos { }; runCatching { (if (open) collapseFocus else handleFocus).requestFocus() } }
        moveFocus = false
    }
    val inputMode = LocalInputModeManager.current
    val wasAdding = remember { booleanArrayOf(false) }
    LaunchedEffect(draft.adding) {
        // Adding collapses the menu, taking the focused Add button with it, so a controller lands on the handle instead.
        val added = wasAdding[0] && !draft.adding && !draft.toolsExpanded
        wasAdding[0] = draft.adding
        if (added && inputMode.inputMode == InputMode.Keyboard) repeat(10) {
            withFrameNanos { }
            if (menuFocused) return@LaunchedEffect
            runCatching { handleFocus.requestFocus() }
        }
    }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(open) { grow.animateTo(if (open) 1f else 0f, Motion.tw(if (open) 240 else 160)) }
    val barShown by remember(draft) { derivedStateOf { draft.toolsExpanded || grow.value > 0f } }
    val reach = with(LocalDensity.current) { TouchTarget.toPx() }
    LaunchedEffect(draft) { snapshotFlow { Triple(draft.handleReach, draft.elements, draft.removed) }.collect { draft.clearHandle() } }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(ToolsMargin), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(handleShown, Modifier.onPlaced { draft.handleArea = it.area(); draft.handleReach = it.touchArea(reach) }.testTag("input-editor-panel"),
            enter = fadeIn(Motion.tw(180, 120)) + scaleIn(Motion.tw(220, 120), initialScale = 0.9f),
            exit = fadeOut(Motion.tw(140)), label = "editor-handle") {
            Box(contentAlignment = Alignment.Center) {
                EditorPanel(Modifier.size(HandleSize), shape = CircleShape) { }
                HeaderIconButton(stringResource(R.string.input_editor_open_menu), ChevronDownIcon, "input-editor-open",
                    Modifier.testTag("input-editor-tools").focusRequester(handleFocus).onFocusChanged { menuFocused = it.isFocused },
                    touchSize = TouchTarget, disabledAlpha = DisabledAlpha, onClick = ::toggle)
            }
        }
        if (barShown) EditorMenuBar(draft, onSave, onLeave, Modifier.padding(top = (TouchTarget - HandleSize) / 2).onPlaced { draft.menuArea = it.area() }.graphicsLayer {
            // Grows out of the handle's circle and shrinks back into it.
            val t = grow.value
            val handle = HandleSize.toPx()
            if (size.width > 0f && size.height > 0f) {
                scaleX = handle / size.width + (1 - handle / size.width) * t
                scaleY = handle / size.height + (1 - handle / size.height) * t
            }
            alpha = (t * 2).coerceAtMost(1f)
            transformOrigin = TransformOrigin(0.5f, 0f)
        }.testTag("input-editor-panel"), collapse = ::toggle,
            collapseModifier = Modifier.focusRequester(collapseFocus).onFocusChanged { menuFocused = it.isFocused })
    }
}

private fun LayoutCoordinates.area() = boundsInRoot().let { SurfaceBounds(it.left, it.top, it.right, it.bottom) }

/** A [side] pixel square on this node's centre, as fractions of the window; measured from the centre because the handle scales in. */
private fun LayoutCoordinates.touchArea(side: Float): SurfaceBounds? {
    val root = findRootCoordinates().size
    if (root.width == 0 || root.height == 0) return null
    val c = boundsInRoot().center
    return SurfaceBounds((c.x - side / 2) / root.width, (c.y - side / 2) / root.height, (c.x + side / 2) / root.width, (c.y + side / 2) / root.height)
}

@Composable
private fun FloatingSelectionSliders(draft: SurfaceDraft) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        val density = LocalDensity.current
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        val margin = with(density) { ToolsMargin.toPx() }
        val outline = draft.visible.boundsOf(draft.selection)?.let {
            val pad = with(density) { OutlinePad.toPx() }
            SurfaceBounds(it.left * screenWidth - pad, it.top * screenHeight - pad, it.right * screenWidth + pad, it.bottom * screenHeight + pad)
        }
        val corners = outline?.let { o ->
            val r = with(density) { CornerReach.toPx() }
            listOf(o.left to o.top, o.right to o.top, o.left to o.bottom, o.right to o.bottom).map { (x, y) -> SurfaceBounds(x - r, y - r, x + r, y + r) }
        }.orEmpty() + listOfNotNull(if (draft.toolsExpanded) draft.menuArea else draft.handleArea)
        val sliderWidth = with(density) { SliderWidth.toPx() }
        var sliderHeight by remember { mutableIntStateOf(0) }
        var sliding by remember { mutableStateOf(false) }
        val spots = outline?.let {
            placeSelectionSliders(screenWidth, screenHeight, sliderWidth, sliderHeight.toFloat().takeIf { h -> h > 0f } ?: with(density) { 64.dp.toPx() },
                margin, with(density) { 8.dp.toPx() }, it, corners)
        }
        val held = remember { arrayOfNulls<SelectionSliderSpots>(1) }
        if (!sliding || held[0] == null || spots == null) held[0] = spots
        val shown = held[0]
        if (shown != null && outline != null) key(draft.selection) {
            val appear = remember { Animatable(0f) }
            LaunchedEffect(Unit) { appear.animateTo(1f, Motion.tw(220, 60)) }
            val shift = with(density) { 8.dp.toPx() }
            fun from(at: Pair<Float, Float>) = if (at.second + sliderHeight / 2 < outline.centerY) shift else -shift
            draft.selectionSize?.let { size ->
                SelectionSliderPanel(shown.size, { appear.value }, from(shown.size), "input-selection-size-panel", { sliderHeight = it }, { sliding = it; if (it) draft.endSlide() }) {
                    SizeSlider(draft, size)
                }
            }
            SelectionSliderPanel(shown.opacity, { appear.value }, from(shown.opacity), "input-selection-opacity-panel", { sliderHeight = it }, { sliding = it; if (it) draft.endSlide() }) {
                OpacitySlider(draft)
            }
        }
    }
}

/** One selection slider at [at], drawn as a thin row over the controls; it fades in from [shift] pixels toward the selection. */
@Composable
private fun SelectionSliderPanel(at: Pair<Float, Float>, appear: () -> Float, shift: Float, tag: String, onHeight: (Int) -> Unit,
    onPress: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.absoluteOffset { IntOffset(at.first.roundToInt(), at.second.roundToInt()) }.width(SliderWidth).onSizeChanged { onHeight(it.height) }
        .graphicsLayer { alpha = appear(); translationY = (1 - appear()) * shift }.testTag(tag).pressing(onPress)) {
        CompositionLocalProvider(LocalHudPill provides if (shift > 0) 1f else 0f) { content() }
    }
}

/** Where a selection slider draws its row inside its touch area: 0 at the top, 1 at the bottom, null for the settings slider. */
private val LocalHudPill = compositionLocalOf<Float?> { null }

private val HudText = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .08.em, color = Color.White.copy(alpha = .72f))
private val HudRow = 26.dp
private val HudRing = 3.dp
private val HudTrackInset = 7.dp

/**
 * A thin slider row over the controls: caps label, a hairline track with a ring at each end, a round thumb and the value, on a faint
 * black pill. It takes touches across [TouchTarget] and draws its row at the [pill] end of that height, the end nearest the selection.
 */
@Composable
private fun HudSlider(label: String, value: Int, range: IntRange, step: Int, pill: Float, modifier: Modifier, onChange: (Int) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hot = source.collectIsFocusedAsState().value || source.collectIsHoveredAsState().value
    val currentOnChange by rememberUpdatedState(onChange)
    val currentRange by rememberUpdatedState(range)
    val text = "$value%"
    val fraction = ((value - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)).coerceIn(0f, 1f)
    val placed = remember { arrayOfNulls<LayoutCoordinates>(2) }
    val inset = with(LocalDensity.current) { HudTrackInset.toPx() }
    fun stepped(v: Int) = currentRange.let { r -> (((v - r.first).toFloat() / step).roundToInt() * step + r.first).coerceIn(r.first, r.last) }
    fun fromX(x: Float) {
        val row = placed[0]?.takeIf { it.isAttached } ?: return
        val track = placed[1]?.takeIf { it.isAttached } ?: return
        val left = row.localPositionOf(track, Offset.Zero).x + inset
        val width = track.size.width - 2 * inset
        val r = currentRange
        if (width > 0) currentOnChange(stepped(r.first + (((x - left) / width).coerceIn(0f, 1f) * (r.last - r.first)).roundToInt()))
    }
    Box(modifier.fillMaxWidth().height(TouchTarget).onPlaced { placed[0] = it }
        .hoverable(source).focusable(interactionSource = source)
        .onPreviewKeyEvent { event ->
            val key = event.nativeKeyEvent
            val left = key.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
            if (!left && key.keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) false
            else {
                if (key.action == android.view.KeyEvent.ACTION_DOWN) stepped(value + if (left) -step else step).let { if (it != value) currentOnChange(it) }
                true
            }
        }
        .clearAndSetSemantics {
            contentDescription = label
            stateDescription = text
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(),
                ((range.last - range.first) / step - 1).coerceAtLeast(0))
            setProgress { currentOnChange(stepped(it.roundToInt())); true }
        }
        .pointerInput(Unit) { detectTapGestures { fromX(it.x) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> fromX(change.position.x); change.consume() } }) {
        Row(Modifier.fillMaxWidth().height(HudRow).offset { IntOffset(0, ((TouchTarget - HudRow).toPx() * pill).roundToInt()) }
            .background(Color.Black.copy(alpha = if (hot) .45f else .28f), CircleShape)
            .then(if (hot) Modifier.border(1.dp, Color.White.copy(alpha = .6f), CircleShape) else Modifier)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label.uppercase(), style = HudText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(56.dp))
            Canvas(Modifier.weight(1f).fillMaxHeight().onPlaced { placed[1] = it }) {
                val y = size.height / 2
                val ring = HudRing.toPx()
                val stroke = 1.5.dp.toPx()
                val start = HudTrackInset.toPx()
                val end = size.width - start
                val thumb = Offset(start + (end - start) * fraction, y)
                for (x in listOf(ring, size.width - ring)) drawCircle(Color.White.copy(alpha = .6f), ring - stroke / 2, Offset(x, y), style = Stroke(stroke))
                drawLine(Color.White.copy(alpha = .28f), Offset(start, y), Offset(end, y), 2.dp.toPx())
                if (fraction > 0) drawLine(Color.White.copy(alpha = .75f), Offset(start, y), thumb, 2.dp.toPx())
                drawCircle(Color.White.copy(alpha = .18f), 10.dp.toPx(), thumb)
                drawCircle(Color.White, 7.dp.toPx(), thumb)
            }
            Text(text, style = HudText, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorMenuBar(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier,
    collapse: (() -> Unit)? = null, collapseModifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizeLabel = stringResource(R.string.input_editor_layout_size)
    val sizeState = stringResource(if (draft.sizeOpen) R.string.widgets_expanded else R.string.widgets_collapsed)
    // Drags and sliders replace the controls every frame; the bar recomposes only when what it shows changes.
    val hasControls by remember(draft) { derivedStateOf { draft.elements.isNotEmpty() } }
    val canAdd by remember(draft) { derivedStateOf { draft.canAdd } }
    val resettable by remember(draft) { derivedStateOf { draft.resettable } }
    val dirty by remember(draft) { derivedStateOf { draft.dirty } }
    val scale by remember(draft) { derivedStateOf { draft.scale } }
    var snapWidth by remember { mutableIntStateOf(0) }
    EditorPanel(modifier) {
        FlowRow(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                if (collapse != null) HeaderIconButton(stringResource(R.string.input_editor_hide_menu), ChevronUpIcon, "input-editor-hide",
                    collapseModifier.testTag("input-editor-tools"), touchSize = TouchTarget, disabledAlpha = DisabledAlpha, onClick = collapse)
                HeaderIconButton(stringResource(R.string.common_close), Icons.Filled.Close, "input-editor-close", Modifier.testTag("input-editor-close"), touchSize = TouchTarget, disabledAlpha = DisabledAlpha, onClick = onLeave)
                HeaderIconButton(stringResource(R.string.input_editor_undo), Icons.AutoMirrored.Filled.Undo, "input-editor-undo", Modifier.testTag("input-editor-undo"),
                    enabled = draft.history.isNotEmpty(), touchSize = TouchTarget, disabledAlpha = DisabledAlpha) { draft.undo() }
                HeaderIconButton(stringResource(R.string.input_editor_redo), Icons.AutoMirrored.Filled.Redo, "input-editor-redo", Modifier.testTag("input-editor-redo"),
                    enabled = draft.redoable.isNotEmpty(), touchSize = TouchTarget, disabledAlpha = DisabledAlpha) { draft.redo() }
                if (draft.defaults != null) HeaderIconButton(stringResource(R.string.input_reset_layout), Icons.Filled.RestartAlt, "input-editor-reset",
                    Modifier.testTag("input-editor-reset"), enabled = resettable, touchSize = TouchTarget, disabledAlpha = DisabledAlpha) { draft.resetLayout() }
            }
            Row(Modifier.align(Alignment.CenterVertically).onSizeChanged { snapWidth = it.width }.testTag("input-editor-snap").clip(CircleShape).focusGlide(CircleShape)
                .toggleable(draft.snap, role = Role.Checkbox) { draft.snap = it; ControllerPrefs.setSnap(context, it) }
                .heightIn(min = TouchTarget).padding(start = SnapStart, end = SnapEnd, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(draft.snap, onCheckedChange = null)
                Text(stringResource(R.string.ctrl_editor_snap), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SecondaryButton(stringResource(R.string.input_editor_add), enabled = canAdd, compact = true, modifier = Modifier.align(Alignment.CenterVertically)) {
                draft.adding = true
            }
            if (hasControls) Box(Modifier.align(Alignment.CenterVertically)) {
                CompositionLocalProvider(LocalChipMinWidth provides 0.dp) {
                    ValueChip("$scale%", draft.sizeOpen, modifier = Modifier.semantics { contentDescription = sizeLabel; stateDescription = sizeState }.testTag("input-editor-size")) {
                        draft.sizeOpen = !draft.sizeOpen
                    }
                }
                AnchoredMenu(draft.sizeOpen, onDismiss = { draft.sizeOpen = false }, compact = true, minWidth = 240.dp) { first ->
                    ValueSlider(scale, 80..125, 5, true, sizeLabel, { "$it%" },
                        Modifier.focusRequester(first).testTag("input-editor-scale").pressing { if (it) draft.endSlide() }) {
                        draft.scaleAll(it)
                    }
                }
            }
            // As wide as the Snap checkbox and its label; the checkbox draws its box 2dp inside its bounds.
            val saveWidth = with(LocalDensity.current) { snapWidth.toDp() } - SnapStart - SnapEnd - 2.dp
            PrimaryButton(stringResource(R.string.input_editor_save), enabled = dirty, compact = true, centred = true,
                modifier = Modifier.align(Alignment.CenterVertically).then(if (snapWidth > 0) Modifier.width(saveWidth) else Modifier).testTag("input-editor-save"),
                onClick = onSave)
        }
    }
}

@Composable
private fun SurfaceSelection(draft: SurfaceDraft, onDelete: () -> Unit) {
    Column(Modifier.padding(12.dp).pressing { if (it) draft.endSlide() }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (draft.selection.isEmpty()) Text(stringResource(R.string.input_editor_tap_hint))
        else {
            Text(draft.selectionName(LocalContext.current), style = MaterialTheme.typography.titleMedium)
            if (draft.selection.size > 1) Text(stringResource(R.string.input_group_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton(stringResource(R.string.input_delete_element), compact = true, modifier = Modifier.testTag("input-remove-selection"), onClick = onDelete)
            SelectionSliders(draft)
        }
    }
}

@Composable
private fun SelectionSliders(draft: SurfaceDraft) {
    CompositionLocalProvider(LocalCompactSettings provides true) {
        draft.selectionSize?.let { SizeSlider(draft, it) }
        OpacitySlider(draft)
    }
}

@Composable
private fun SizeSlider(draft: SurfaceDraft, size: Int) =
    SelectionSlider(stringResource(R.string.input_selection_size), size, selectionSizeRange(size), "input-selection-size") { draft.resizeSelection(it) }

@Composable
private fun OpacitySlider(draft: SurfaceDraft) =
    SelectionSlider(stringResource(R.string.ctrl_opacity), (draft.selectionOpacity * 100).roundToInt().coerceIn(20, 100), 20..100,
        "input-selection-opacity") { draft.setSelectionOpacity(it) }

@Composable
private fun SelectionSlider(label: String, value: Int, range: IntRange, tag: String, onChange: (Int) -> Unit) {
    val pill = LocalHudPill.current
    if (pill != null) HudSlider(label, value, range, 5, pill, Modifier.testTag(tag), onChange)
    else ValueSlider(value, range, 5, true, label, { "$it%" }, Modifier.testTag(tag), onChange = onChange)
}

internal fun InputControlGroup.label() = when (this) {
    InputControlGroup.DPAD -> R.string.input_group_dpad
    InputControlGroup.FACE -> R.string.input_group_face
    InputControlGroup.LEFT_SHOULDER -> R.string.input_group_left_shoulder
    InputControlGroup.RIGHT_SHOULDER -> R.string.input_group_right_shoulder
    InputControlGroup.LEFT_STICK -> R.string.input_group_left_stick
    InputControlGroup.RIGHT_STICK -> R.string.input_group_right_stick
}

private class SurfaceEditorPresentation(private val host: ComponentActivity, display: Display, private val transparent: Boolean, private val content: @Composable () -> Unit) : Presentation(host, display) {
    private var view: ComposeView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window?.prepareInputEditor()
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(host); it.setViewTreeViewModelStoreOwner(host)
            it.setViewTreeSavedStateRegistryOwner(host); it.setViewTreeOnBackPressedDispatcherOwner(host)
        }
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        view = ComposeView(context).also { it.setContent { DroidDeckTheme {
            Surface(Modifier.fillMaxSize(), color = if (transparent) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) { content() }
        } }; setContentView(it) }
    }
    override fun dismiss() { view?.disposeComposition(); view = null; super.dismiss() }
}

@Suppress("DEPRECATION")
internal fun Window.prepareInputEditor() {
    addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    // The game's window covers the cutout too; a letterboxed canvas would map the same fractions to other pixels.
    if (Build.VERSION.SDK_INT >= 28) attributes = attributes.apply {
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}
