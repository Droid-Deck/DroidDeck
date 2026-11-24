package com.droiddeck.launcher.ui

import android.app.Presentation
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.session.SessionState

private data class SurfaceSnapshot(val elements: List<InputSurfaceElement>, val actions: Map<String, InputAction>,
    val clicks: Map<String, InputAction>, val scale: Float, val aspect: Float)

private class GamepadScaler(private val context: android.content.Context, private val width: Int, private val height: Int,
    private val layout: SavedInputLayout) {
    private fun generator(scale: Float) = LegacyGamepadLayout(context, width, height, scale = scale)
    val ids = generator(1f).elements().map { it.id }.toSet()
    fun stock(scale: Float) = generator(scale).elements()
    private fun placed(scale: Float) = OnScreenLayout.editorElements(context,
        layout.copy(elements = stock(scale), aspect = 0f, rotated = emptyList(), scale = scale), width, height)
    fun rescale(stored: List<InputSurfaceElement>, from: Float, to: Float) =
        if (PadKit.forOverlay(context, layout) == null || OnScreenLayout.generatedSize(context, layout.copy(elements = stored, scale = from), width, height) != null)
            generator(to).rescale(stored, generator(from))
        else rescaleBetween(stored, placed(from), placed(to), stock(from), stock(to))
    fun reset(stored: List<InputSurfaceElement>) = generator(1f).regenerate(stored).associateBy { it.id }.let { placed -> stored.map { placed[it.id] ?: it } }
}

private class SurfaceDraft(val layout: SavedInputLayout, val preset: SavedInputBindings, val defaults: List<InputSurfaceElement>? = null,
    private val defaultsFrame: Float = 0f, private var gamepad: GamepadScaler? = null, snap: Boolean = true) {
    val hasGamepad get() = gamepad != null
    var elements by mutableStateOf(layout.elements)
    var actions by mutableStateOf(preset.elements)
    var clicks by mutableStateOf(preset.clicks)
    var selected by mutableStateOf<String?>(null)
    var toolsExpanded by mutableStateOf(false)
    var toolsX by mutableFloatStateOf(-1f)
    var toolsY by mutableFloatStateOf(.02f)
    var snap by mutableStateOf(snap)
    var adding by mutableStateOf(false)
    var properties by mutableStateOf(false)
    var clickProperties by mutableStateOf(false)
    var history by mutableStateOf<List<SurfaceSnapshot>>(emptyList())
    var canvas by mutableStateOf(IntSize.Zero)
    var layoutScale by mutableFloatStateOf(layout.scale)
    var aspect by mutableFloatStateOf(layout.aspect)
    private var scaleBase by mutableStateOf<Triple<List<InputSurfaceElement>, List<InputSurfaceElement>, Float>?>(null)
    private var scaleValue by mutableIntStateOf(100)
    private var pieceBase by mutableStateOf<Triple<List<InputSurfaceElement>, List<InputSurfaceElement>, Int>?>(null)
    private var pieceLayout: List<InputSurfaceElement>? = null
    private val piece get() = element?.let { e -> (if (elements.any { it.id == e.id }) elements else shown).groupOf(e) }.orEmpty()
    val scaleRange get() = if (piece.isEmpty()) 80..125 else 50..200
    private val rescales get() = gamepad?.let { pad -> elements.any { it.id in pad.ids } } == true
    val scale get() = when {
        piece.isNotEmpty() -> pieceBase?.takeIf { it.second == piece && pieceLayout == elements }?.third ?: 100
        scaleBase?.second == elements -> scaleValue
        rescales -> ((if (canvas.width > 0 && canvas.height > 0 && aspect > 0f && (aspect < 1f) != (canvasAspect < 1f))
            OnScreenLayout.oriented(layout, canvas.width, canvas.height).scale else layoutScale) * 100).roundToInt()
        else -> 100
    }
    val positioned get() = (if (rescales) gamepad!!.reset(elements) else defaults?.let { elements.withPositionsOf(refit(it, defaultsFrame)) })
        ?.takeIf { it != elements || rescales && layoutScale != 1f }

    private fun refit(placed: List<InputSurfaceElement>, from: Float): List<InputSurfaceElement> {
        if (from <= 0f || aspect <= 0f || abs(from / aspect - 1f) < .01f) return placed
        return OnScreenLayout.fit(placed, from, 1f, (aspect * 1000).roundToInt().coerceAtLeast(1), 1000, Rect())
    }
    val element get() = elements.firstOrNull { it.id == selected } ?: shown.firstOrNull { it.id == selected }
    val dirty get() = elements != layout.elements || actions != preset.elements || clicks != preset.clicks || layoutScale != layout.scale || aspect != layout.aspect
    fun checkpoint() { history = (history + SurfaceSnapshot(elements, actions, clicks, layoutScale, aspect)).takeLast(40) }
    fun undo() { history.lastOrNull()?.let { elements = it.elements; actions = it.actions; clicks = it.clicks; layoutScale = it.scale; aspect = it.aspect; history = history.dropLast(1) } }
    private fun adoptDisplayedFrame(context: android.content.Context): Boolean {
        if (canvas.width <= 0 || canvas.height <= 0 || shown.isEmpty() || aspect <= 0f || (aspect < 1f) == (canvasAspect < 1f)) return false
        val open = toolsExpanded
        beginEdit(shown, canvasAspect, gamepad?.let { GamepadScaler(context, canvas.width, canvas.height, layout) })
        toolsExpanded = open
        return true
    }

    fun beginEdit(displayed: List<InputSurfaceElement>, aspect: Float, scaler: GamepadScaler? = gamepad) {
        checkpoint()
        toolsExpanded = false
        elements = OnScreenLayout.editorFrame(layout.copy(elements = elements), displayed)
        if (this.aspect > 0f && aspect > 0f && (this.aspect < 1f) != (aspect < 1f)) layoutScale = OnScreenLayout.oriented(layout, (aspect * 1000).roundToInt(), 1000).scale
        this.aspect = aspect
        scaler?.let { gamepad = it }
    }
    fun scaleAll(context: android.content.Context, percent: Int) {
        val adopted = adoptDisplayedFrame(context)
        val base = scaleBase?.takeIf { it.second == elements } ?: Triple(elements, elements, if (rescales) layoutScale else 0f).also { if (!adopted) checkpoint() }
        val factor = percent / 100f
        val pad = gamepad
        elements = if (base.third > 0f && pad != null) {
            layoutScale = factor
            val others = base.first.filterNot { it.id in pad.ids }.scaled(factor / base.third).associateBy { it.id }
            pad.rescale(base.first, base.third, factor).map { others[it.id] ?: it }
        } else base.first.scaled(factor)
        scaleValue = percent; scaleBase = Triple(base.first, elements, base.third)
    }
    fun scaleSelected(context: android.content.Context, percent: Int) {
        val id = selected ?: return
        val adopted = adoptDisplayedFrame(context)
        val base = pieceBase?.takeIf { it.second == piece && pieceLayout == elements } ?: run {
            if (!adopted && canvas.width > 0 && canvas.height > 0 && shown.isNotEmpty()) {
                val open = toolsExpanded
                beginEdit(shown, canvasAspect)
                toolsExpanded = open
            } else if (!adopted) checkpoint()
            val start = elements.firstOrNull { it.id == id }?.let { elements.groupOf(it) } ?: return
            Triple(start, start, 100)
        }
        val resized = base.first.resizedAroundCentre(percent / 100f)
        val byId = resized.associateBy { it.id }
        elements = elements.map { byId[it.id] ?: it }
        pieceBase = Triple(base.first, resized, percent)
        pieceLayout = elements
    }
    var shown by mutableStateOf<List<InputSurfaceElement>>(emptyList())
    private val canvasAspect get() = canvas.width.toFloat() / canvas.height.coerceAtLeast(1)

    private fun stored(id: String): Boolean {
        if (elements.any { it.id == id }) return true
        if (shown.none { it.id == id }) return false
        beginEdit(shown, canvasAspect)
        return true
    }

    fun missing(context: android.content.Context): List<InputSurfaceElement> {
        val pad = gamepad ?: return emptyList()
        if (canvas.width <= 0 || canvas.height <= 0) return emptyList()
        val ids = shown.map { it.id }.toSet()
        return OnScreenLayout.editorElements(context, layout.copy(elements = pad.stock(layoutScale), aspect = 0f, rotated = emptyList(), scale = layoutScale),
            canvas.width, canvas.height).filter { it.id !in ids }
    }

    fun restore(context: android.content.Context, element: InputSurfaceElement) {
        val legacy = gamepad?.stock(layoutScale)?.firstOrNull { it.id == element.id }
        val generated = OnScreenLayout.generatedSize(context, layout.copy(elements = elements), canvas.width, canvas.height) != null
        if (generated && legacy != null) { checkpoint(); elements = elements + legacy }
        else { beginEdit(shown, canvasAspect); elements = elements + element }
        if (element.id == "builtin:keyboard" && actions[element.id] == null) actions = actions + (element.id to InputAction.Command(InputCommand.KEYBOARD))
        selected = element.id
    }

    fun remove(id: String) {
        if (elements.any { it.id == id }) checkpoint() else stored(id)
        elements = elements.filterNot { it.id == id }; actions = actions - id; clicks = clicks - id; selected = null
    }

    fun update(element: InputSurfaceElement) {
        stored(element.id)
        elements = elements.map { if (it.id == element.id) element else it }
    }

    fun resetPositions(context: android.content.Context) {
        val adopted = adoptDisplayedFrame(context)
        positioned?.let { if (!adopted) checkpoint(); elements = it; if (rescales) layoutScale = 1f }
    }
    fun action(e: InputSurfaceElement) = preset.copy(elements = actions).action(e)
    fun clickAction(e: InputSurfaceElement) = preset.copy(elements = actions, clicks = clicks).clickAction(e)
}

@Composable
fun InputSurfaceDialog(profile: String, onDismiss: () -> Unit, onChanged: () -> Unit,
    role: InputRole = InputRole.SECONDARY, layoutId: String? = null, bindingsId: String? = null) {
    val context = LocalContext.current
    val host = LocalInputActivity.current ?: context.findActivity() as? ComponentActivity
    val library = remember { InputLibraries.read(context) }
    val savedLayout = remember { library.layouts.first { it.id == (layoutId ?: library.profile(profile).parts.getValue(role).layout) } }
    val savedBindings = remember { library.preset(bindingsId ?: library.profile(profile).binding(role)) }
    val layoutUsers = library.usersOfLayout(savedLayout.id).size
    val bindingUsers = library.usersOfBindings(savedBindings.id).size
    val draft = remember {
        val (display, metrics) = InputLibraries.primaryDisplay(context)
        val gamepad = savedLayout.elements.takeIf { e -> e.any { it.id.startsWith("legacy:") } }?.let {
            val (w, h) = OnScreenLayout.generatedSize(context, savedLayout, metrics.widthPixels, metrics.heightPixels) ?: (metrics.widthPixels to metrics.heightPixels)
            GamepadScaler(display, w, h, savedLayout)
        }
        val defaults = InputLibraries.defaultPositions(context, library, savedLayout)
        SurfaceDraft(savedLayout, savedBindings, defaults?.first, defaults?.second ?: 0f, gamepad, ControllerPrefs.snap(context))
    }
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
    fun leave() { if (draft.dirty) discard = true else onDismiss() }
    fun save() {
        runCatching {
            InputLibraries.update(context) { latest ->
                val l = latest.layouts.first { it.id == savedLayout.id }
                val p = latest.preset(savedBindings.id)
                latest.withLayout(OnScreenLayout.withFrame(context, l, draft.elements, draft.aspect, draft.layoutScale)).withPreset(p.copy(elements = draft.actions.filterKeys { id -> draft.elements.any { it.id == id } },
                    clicks = draft.clicks.filterKeys { id -> draft.elements.any { it.id == id && it.hasClick } }))
            }
        }.onSuccess { onChanged(); onDismiss() }.onFailure { failure = it.message ?: context.getString(R.string.input_save_failed) }
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
            SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY)
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
    // Compose 1.6 custom dialog sizing subtracts system bars even when they are hidden.
    Dialog(onDismissRequest = ::leave, properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = false)) {
        val editorWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(editorWindow) {
            editorWindow?.prepareInputEditor()
            if (host is SessionActivity && role == InputRole.PRIMARY) editorWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            onDispose { }
        }
        Surface(Modifier.fillMaxSize(), color = if (host is SessionActivity && role == InputRole.PRIMARY && !draft.properties && !remote)
            androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) {
            val element = draft.element
            BackHandler { if (draft.properties) draft.properties = false else leave() }
            when {
                draft.properties && element != null -> {
                    var properties by remember(element.id) { mutableStateOf(element) }
                    InputActionEditor(if (draft.clickProperties) InputControl.A else element.control,
                        if (draft.clickProperties) draft.clickAction(element) else draft.action(element),
                        if (draft.clickProperties) stringResource(R.string.input_click_name, element.name) else element.name,
                        onBack = { draft.properties = false }, element = element, click = draft.clickProperties,
                        properties = { action ->
                            if (!draft.clickProperties) InputElementProperties(properties, action) { properties = it }
                            if (layoutUsers > 1) Text(stringResource(R.string.input_editor_shared, layoutUsers),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp))
                        }, detail = if (bindingUsers > 1) stringResource(R.string.input_shared_detail, savedBindings.name, bindingUsers)
                            else "${library.profile(profile).name} · ${roleLabel(role)}", onSave = { action ->
                            draft.checkpoint()
                            if (draft.clickProperties) draft.clicks = draft.clicks + (element.id to action) else draft.actions = draft.actions + (element.id to action)
                            draft.update(properties.copy(name = properties.name.trim().ifBlank { element.name }))
                            draft.properties = false
                        })
                }
                target == null -> SettingsPage(rememberMenuHost(), savedLayout.name, ::leave) {
                    Text(stringResource(R.string.input_display_unavailable))
                    PrimaryButton(stringResource(R.string.input_editor_done)) { save() }
                }
                remote -> SettingsPage(rememberMenuHost(), stringResource(R.string.input_editor_remote), ::leave,
                    lede = if (layoutUsers > 1) stringResource(R.string.input_editor_shared, layoutUsers) else savedLayout.name) {
                    SurfaceToolbar(draft, ::save, ::leave)
                    SurfaceSelection(draft)
                }
                else -> Box(Modifier.fillMaxSize()) {
                    SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY)
                    FloatingSurfaceTools(draft, ::save, ::leave)
                }
            }
        }
    }
    if (draft.adding) AlertDialog(onDismissRequest = { draft.adding = false }, title = { Text(stringResource(R.string.input_add_element)) }, confirmButton = {}, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        for (kind in InputElementKind.entries) PadTextButton(onClick = { draft.checkpoint()
            val w = if (kind.control.dimensions == 2) .4f else .2f
            val h = .3f
            val name = context.getString(kind.label()) + " " + (draft.elements.count { it.kind == kind } + 1)
            val element = InputSurfaceElement(kind.control, (1f - w) / 2, (1f - h) / 2, w, h, newInputId(), kind, name)
            draft.elements = draft.elements + element; draft.selected = element.id
            draft.adding = false; draft.toolsExpanded = false }) { Text(stringResource(kind.label())) }
        val missing = remember(draft.elements, draft.canvas) { draft.missing(context) }
        if (missing.isNotEmpty()) HorizontalDivider()
        for (element in missing) PadTextButton(onClick = { draft.restore(context, element); draft.adding = false; draft.toolsExpanded = false }) {
            Text(PadKit.forOverlay(context, savedLayout)?.name(element.control)?.takeIf { element.name == element.control.label } ?: element.name)
        }
    } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.input_editor_discard)) },
        confirmButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { PadTextButton(onClick = { discard = false }) { Text(stringResource(R.string.common_cancel)) } })
    failure?.let { AlertDialog(onDismissRequest = { failure = null }, title = { Text(stringResource(R.string.input_save_failed)) }, text = { Text(it) },
        confirmButton = { PadTextButton(onClick = { failure = null }) { Text(stringResource(R.string.common_ok)) } }) }
}

@Composable
private fun SurfaceCanvas(draft: SurfaceDraft, transparent: Boolean = false) {
    AndroidView(factory = { InputSurfaceEditorView(it) }, modifier = Modifier.fillMaxSize().testTag("input-layout-canvas"),
        update = { view ->
            view.setBackgroundColor(if (transparent) android.graphics.Color.TRANSPARENT else android.graphics.Color.rgb(10, 11, 13))
            view.style = draft.layout.style.copy(hidden = emptySet()); view.sourceLayout = draft.layout.copy(scale = draft.layoutScale); view.aspect = draft.aspect; view.elements = draft.elements; view.selected = draft.selected; view.snap = draft.snap
            view.isEnabled = !draft.properties && !draft.adding
            view.onSelect = { if (!draft.properties) draft.selected = it }
            view.onBeginEdit = { e, w, h -> draft.beginEdit(e, w.toFloat() / h, if (draft.hasGamepad) GamepadScaler(view.context, w, h, draft.layout) else null) }
            view.onResize = { w, h -> draft.canvas = IntSize(w, h) }
            view.onDisplayed = { draft.shown = it }
            view.onChange = { changed -> if (!draft.properties) changed.associateBy { it.id }.let { byId -> draft.elements = draft.elements.map { byId[it.id] ?: it } } }

        })
}

@Composable
private fun FloatingSurfaceTools(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        val margin = with(density) { 8.dp.toPx() }
        var panelSize by remember { mutableStateOf(IntSize.Zero) }
        val x = (if (draft.toolsX < 0f) (screenWidth - panelSize.width) / 2f else draft.toolsX * screenWidth)
            .coerceIn(margin, (screenWidth - panelSize.width - margin).coerceAtLeast(margin))
        val y = (draft.toolsY * screenHeight).coerceIn(margin, (screenHeight - panelSize.height - margin).coerceAtLeast(margin))
        val position by rememberUpdatedState(Offset(x, y))
        val expandedWidth = minOf(420.dp, maxWidth - 16.dp)
        val panelWidth by animateDpAsState(if (draft.toolsExpanded) expandedWidth else 152.dp, label = "input-tools-width")
        Surface(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(panelWidth).clipToBounds()
            .heightIn(max = maxHeight - 16.dp).onSizeChanged { panelSize = it },
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp, shadowElevation = 10.dp) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("input-editor-tools")
                    .pointerInput(screenWidth, screenHeight, panelSize) {
                        var dragPosition = Offset.Zero
                        detectDragGestures(onDragStart = { dragPosition = position }) { change, delta ->
                            change.consume()
                            dragPosition = Offset((dragPosition.x + delta.x).coerceIn(margin, (screenWidth - panelSize.width - margin).coerceAtLeast(margin)),
                                (dragPosition.y + delta.y).coerceIn(margin, (screenHeight - panelSize.height - margin).coerceAtLeast(margin)))
                            draft.toolsX = dragPosition.x / screenWidth
                            draft.toolsY = dragPosition.y / screenHeight
                        }
                    }
                    .clickable { draft.toolsExpanded = !draft.toolsExpanded }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("⠿", color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(if (draft.toolsExpanded) R.string.input_hide_tools else R.string.input_tools), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                AnimatedVisibility(draft.toolsExpanded, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
                    Column(Modifier.requiredWidth(expandedWidth).verticalScroll(rememberScrollState())) {
                        HorizontalDivider()
                        SurfaceToolbar(draft, onSave, onLeave)
                        HorizontalDivider()
                        SurfaceSelection(draft)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceToolbar(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onLeave)
        SecondaryButton(stringResource(R.string.input_editor_add), enabled = draft.elements.size < 64, compact = true) { draft.adding = true }
        SecondaryButton(stringResource(R.string.input_editor_undo), enabled = draft.history.isNotEmpty(), compact = true) { draft.undo() }
        if (draft.defaults != null) SecondaryButton(stringResource(R.string.input_reset_positions), enabled = draft.positioned != null, compact = true) { draft.resetPositions(context) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(draft.snap, { draft.snap = it; ControllerPrefs.setSnap(context, it) })
            Text(stringResource(R.string.ctrl_editor_snap))
        }
        PrimaryButton(stringResource(R.string.input_editor_done), compact = true, onClick = onSave)
    }
    if (draft.elements.isNotEmpty()) SliderRow(stringResource(R.string.ctrl_size), null, draft.scale, draft.scaleRange, step = 5,
        format = { "$it%" }, modifier = Modifier.testTag("input-editor-scale")) { if (draft.element != null) draft.scaleSelected(context, it) else draft.scaleAll(context, it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceSelection(draft: SurfaceDraft) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        draft.element?.let { e ->
            Text(e.name, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PrimaryButton(stringResource(R.string.input_properties), compact = true) { draft.clickProperties = false; draft.properties = true }
                if (e.hasClick) SecondaryButton(stringResource(R.string.input_click_properties), compact = true) { draft.clickProperties = true; draft.properties = true }
                SecondaryButton(stringResource(R.string.input_remove_element), compact = true) { draft.remove(e.id) }
            }
        } ?: Text(stringResource(R.string.input_editor_hint))
    }
}

private fun InputElementKind.label() = when (this) {
    InputElementKind.BUTTON -> R.string.input_element_button
    InputElementKind.STICK -> R.string.input_element_stick
    InputElementKind.TRACKPAD -> R.string.input_element_trackpad
    InputElementKind.DPAD -> R.string.input_element_dpad
    InputElementKind.TRIGGER -> R.string.input_element_trigger
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
    decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}
