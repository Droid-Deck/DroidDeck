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

private class SurfaceDraft(val layout: SavedInputLayout, val preset: SavedInputBindings, val defaults: List<InputSurfaceElement>? = null,
    private val defaultsFrame: Float = 0f, snap: Boolean = true) {
    var elements by mutableStateOf(layout.elements)
    var actions by mutableStateOf(preset.elements)
    var clicks by mutableStateOf(preset.clicks)
    var selected by mutableStateOf<String?>(null)
    var snap by mutableStateOf(snap)
    var popover by mutableStateOf<EditorPopover?>(null)
    var confirmDiscard by mutableStateOf(false)
    var properties by mutableStateOf(false)
    var clickProperties by mutableStateOf(false)
    var history by mutableStateOf<List<SurfaceSnapshot>>(emptyList())
    var layoutScale by mutableFloatStateOf(layout.scale)
    var aspect by mutableFloatStateOf(layout.aspect)
    private var scaleBase by mutableStateOf<Pair<List<InputSurfaceElement>, List<InputSurfaceElement>>?>(null)
    private var scaleValue by mutableIntStateOf(100)
    val scale get() = if (scaleBase?.second == elements) scaleValue else 100
    val positioned get() = defaults?.let { elements.withPositionsOf(refit(it, defaultsFrame)) }?.takeIf { it != elements }

    private fun refit(placed: List<InputSurfaceElement>, from: Float): List<InputSurfaceElement> {
        if (from <= 0f || aspect <= 0f || abs(from / aspect - 1f) < .01f) return placed
        return OnScreenLayout.fit(placed, from, 1f, (aspect * 1000).roundToInt().coerceAtLeast(1), 1000, Rect())
    }
    val element get() = elements.firstOrNull { it.id == selected }
    val dirty get() = elements != layout.elements || actions != preset.elements || clicks != preset.clicks || layoutScale != layout.scale || aspect != layout.aspect
    fun checkpoint() { history = (history + SurfaceSnapshot(elements, actions, clicks, layoutScale, aspect)).takeLast(40) }
    fun undo() { history.lastOrNull()?.let { elements = it.elements; actions = it.actions; clicks = it.clicks; layoutScale = it.scale; aspect = it.aspect; history = history.dropLast(1) } }
    fun beginEdit(displayed: List<InputSurfaceElement>, aspect: Float) {
        checkpoint()
        elements = OnScreenLayout.editorFrame(layout.copy(elements = elements), displayed)
        this.aspect = aspect
    }
    fun scaleAll(percent: Int) {
        val base = scaleBase?.takeIf { it.second == elements } ?: (elements to elements).also { checkpoint() }
        elements = base.first.scaled(percent / 100f)
        scaleValue = percent; scaleBase = base.first to elements
    }
    fun resetPositions() { positioned?.let { checkpoint(); elements = it } }
    private val restored get() = defaults?.let { refit(it, defaultsFrame) }
    private fun matches(defaults: List<InputSurfaceElement>, placed: List<InputSurfaceElement>) = defaults.size == placed.size && defaults.all { a ->
        placed.any { e -> e.copy(x = a.x, y = a.y, width = a.width, height = a.height) == a &&
            abs(e.x - a.x) < .002f && abs(e.y - a.y) < .002f && abs(e.width - a.width) < .002f && abs(e.height - a.height) < .002f }
    }
    val atDefaults get() = restored?.let { matches(it, elements) } == true
    /** The kit's own controls are where the kit puts them, so only the added ones need saving. */
    val kitAtDefaults get() = restored?.let { matches(it, elements.filter { e -> KitLayout.isKit(e.id) }) } == true
    fun reset() {
        val d = restored?.takeIf { !atDefaults } ?: return
        checkpoint(); elements = d; selected = null
        actions = actions.filterKeys { id -> KitLayout.bindsTo(id, d) }; clicks = clicks.filterKeys { id -> d.any { it.id == id } }
    }
    fun hiddenDefaults(): List<InputSurfaceElement> = defaults?.let { refit(it, defaultsFrame) }?.filter { d -> elements.none { it.id == d.id } }.orEmpty()
    fun add(element: InputSurfaceElement) { checkpoint(); elements = elements + element; selected = element.id; popover = null }
    fun remove(id: String) {
        checkpoint(); elements = elements.filterNot { it.id == id }; selected = null
        if (!KitLayout.isKit(id)) { actions = actions - id; clicks = clicks - id }
    }
    fun resizeSelected(factor: Float) {
        val e = element ?: return
        checkpoint()
        val w = (e.width * factor).coerceIn(InputSurfaceElement.MIN_SIZE, 1f); val h = (e.height * factor).coerceIn(InputSurfaceElement.MIN_SIZE, 1f)
        val cx = e.x + e.width / 2; val cy = e.y + e.height / 2
        elements = elements.map { if (it.id == e.id) e.copy(x = (cx - w / 2).coerceIn(0f, 1f - w), y = (cy - h / 2).coerceIn(0f, 1f - h), width = w, height = h).bounded() else it }
    }
    fun action(e: InputSurfaceElement) = preset.copy(elements = actions).action(e)
    fun clickAction(e: InputSurfaceElement) = preset.copy(elements = actions, clicks = clicks).clickAction(e)
}

/** Offered when the layout being edited is shared: [create] makes the copy that Done then saves into, and [scope] describes it. */
/** Turns an edit of a shared layout into a copy for one game: [copy] adds it to the library being saved, [use] picks it once that save succeeded. */
class InputEditorSplit(val label: String, val scope: String, val copy: (InputLibrary, String, String) -> InputLibrary, val use: (String) -> Unit)

@Composable
fun InputSurfaceDialog(profile: String, onDismiss: () -> Unit, onChanged: () -> Unit,
    role: InputRole = InputRole.SECONDARY, layoutId: String? = null, bindingsId: String? = null, scope: String? = null,
    split: InputEditorSplit? = null) {
    val context = LocalContext.current
    val host = LocalInputActivity.current ?: context.findActivity() as? ComponentActivity
    val library = remember { InputLibraries.read(context) }
    val savedLayout = remember { library.layouts.first { it.id == (layoutId ?: library.profile(profile).parts.getValue(role).layout) } }
    val savedBindings = remember { library.preset(bindingsId ?: library.profile(profile).binding(role)) }
    val layoutUsers = library.usersOfLayout(savedLayout.id).size
    var splitting by remember { mutableStateOf(false) }
    val draft = remember {
        val defaults = InputLibraries.defaultPositions(context, library, savedLayout)
        val start = if (OnScreenLayout.generated(savedLayout) && defaults != null) savedLayout.copy(elements = defaults.first +
            OnScreenLayout.fit(savedLayout.elements, savedLayout.aspect.takeIf { it > 0f } ?: defaults.second, 1f, (defaults.second * 1000).roundToInt(), 1000, Rect()),
            aspect = defaults.second) else savedLayout
        SurfaceDraft(start, savedBindings, defaults?.first, defaults?.second ?: 0f, ControllerPrefs.snap(context))
    }
    val manager = remember { context.getSystemService(DisplayManager::class.java) }
    var revision by remember { mutableIntStateOf(0) }
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
    fun leave() { if (draft.dirty) draft.confirmDiscard = true else onDismiss() }
    fun save() {
        runCatching {
            val copy = split?.takeIf { splitting }
            val (layoutTarget, bindingsTarget) = if (copy != null) newInputId() to newInputId() else savedLayout.id to savedBindings.id
            InputLibraries.update(context) { saved ->
                val latest = copy?.copy?.invoke(saved, layoutTarget, bindingsTarget) ?: saved
                val l = latest.layouts.first { it.id == layoutTarget }
                val p = latest.preset(bindingsTarget)
                val generated = draft.kitAtDefaults && OnScreenLayout.generated(l.copy(elements = emptyList(), kitless = false))
                val extras = draft.elements.filterNot { KitLayout.isKit(it.id) }
                val edited = if (generated) l.copy(elements = extras, aspect = if (extras.isEmpty()) 0f else draft.aspect, scale = draft.layoutScale, kitless = false)
                    else l.copy(elements = draft.elements, aspect = draft.aspect, scale = draft.layoutScale, kitless = draft.elements.none { KitLayout.isKit(it.id) })
                latest.withLayout(edited).withPreset(p.copy(elements = draft.actions.filterKeys { id -> edited.element(id) != null },
                    clicks = draft.clicks.filterKeys { id -> edited.element(id)?.hasClick == true }))
            }
            copy?.use?.invoke(bindingsTarget)
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
            val editorScope = split?.scope?.takeIf { splitting } ?: scope ?: if (layoutUsers > 1) stringResource(R.string.input_editor_shared, layoutUsers) else savedLayout.name
            val splitLabel = split?.label?.takeIf { !splitting }
            val propertiesEditor: @Composable (InputSurfaceElement) -> Unit = { element ->
                var properties by remember(element.id) { mutableStateOf(element) }
                InputActionEditor(if (draft.clickProperties) InputControl.A else element.control,
                    if (draft.clickProperties) draft.clickAction(element) else draft.action(element),
                    if (draft.clickProperties) stringResource(R.string.input_click_name, element.name) else element.name,
                    onBack = { draft.properties = false }, element = element, click = draft.clickProperties,
                    properties = { action ->
                        if (!draft.clickProperties) InputElementProperties(properties, action) { properties = it }
                    }, detail = editorScope, onSave = { action ->
                        draft.checkpoint()
                        if (draft.clickProperties) draft.clicks = draft.clicks + (element.id to action) else draft.actions = draft.actions + (element.id to action)
                        draft.elements = draft.elements.map { if (it.id == element.id) properties.copy(name = properties.name.trim().ifBlank { element.name }) else it }
                        draft.properties = false
                    })
            }
            when {
                (remote || target == null) && draft.properties && element != null -> propertiesEditor(element)
                target == null -> SettingsPage(rememberMenuHost(), savedLayout.name, ::leave) {
                    Text(stringResource(R.string.input_display_unavailable))
                    failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (draft.confirmDiscard) {
                        Text(stringResource(R.string.input_editor_discard), style = MaterialTheme.typography.titleSmall)
                        SecondaryButton(stringResource(R.string.input_keep_editing)) { draft.confirmDiscard = false }
                        SecondaryButton(stringResource(R.string.input_discard), onClick = onDismiss)
                    }
                    PrimaryButton(stringResource(R.string.input_editor_done)) { save() }
                }
                remote -> SettingsPage(rememberMenuHost(), stringResource(R.string.input_editor_remote), ::leave, lede = editorScope) {
                    splitLabel?.let { SecondaryButton(it, compact = true, modifier = Modifier.padding(horizontal = 8.dp).testTag("input-editor-split")) { splitting = true } }
                    SurfaceToolbar(draft, ::save, ::leave, onDismiss)
                    failure?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    SurfaceSelection(draft)
                    when (draft.popover) { EditorPopover.ADD -> AddRows(draft); EditorPopover.HIDDEN -> HiddenRows(draft); null -> Unit }
                }
                else -> Box(Modifier.fillMaxSize()) {
                    SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY)
                    EditorChrome(draft, editorScope, splitLabel, { splitting = true }, failure, ::save, ::leave, onDismiss)
                    if (draft.properties && element != null) Surface(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.44f),
                        color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                        propertiesEditor(element)
                    }
                }
            }
        }
    }
}

@Composable
private fun SurfaceCanvas(draft: SurfaceDraft, transparent: Boolean = false) {
    AndroidView(factory = { InputSurfaceEditorView(it) }, modifier = Modifier.fillMaxSize().testTag("input-layout-canvas"),
        update = { view ->
            view.setBackgroundColor(if (transparent) android.graphics.Color.TRANSPARENT else android.graphics.Color.rgb(10, 11, 13))
            view.style = draft.layout.style.copy(hidden = emptySet()); view.sourceLayout = draft.layout; view.aspect = draft.aspect; view.elements = draft.elements; view.selected = draft.selected; view.snap = draft.snap
            view.isEnabled = !draft.properties && draft.popover == null && !draft.confirmDiscard
            view.onSelect = { if (!draft.properties) draft.selected = it }
            view.onBeginEdit = { e, w, h -> draft.beginEdit(e, w.toFloat() / h) }
            view.onChange = { e -> if (!draft.properties) draft.elements = draft.elements.map { if (it.id == e.id) e else it } }

        })
}

private enum class EditorPopover { ADD, HIDDEN }

@Composable
private fun EditorBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 8.dp) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** The editor's tools over the canvas: one toolbar, a bar on the selected control, and the Add and Hidden lists. */
@Composable
private fun EditorChrome(draft: SurfaceDraft, scope: String, splitLabel: String?, onSplit: () -> Unit, failure: String?,
    onSave: () -> Unit, onLeave: () -> Unit, onDiscard: () -> Unit) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).widthIn(max = maxWidth - 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EditorBar(Modifier.testTag("input-editor-tools")) {
                if (draft.confirmDiscard) {
                    Text(stringResource(R.string.input_editor_discard), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
                    SecondaryButton(stringResource(R.string.input_keep_editing), compact = true) { draft.confirmDiscard = false }
                    PrimaryButton(stringResource(R.string.input_discard), compact = true, onClick = onDiscard)
                } else {
                    SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onLeave)
                    SecondaryButton(stringResource(R.string.input_editor_undo), enabled = draft.history.isNotEmpty(), compact = true) { draft.undo() }
                    SecondaryButton(stringResource(R.string.input_editor_add), enabled = draft.elements.size < 64, compact = true) {
                        draft.popover = if (draft.popover == EditorPopover.ADD) null else EditorPopover.ADD
                    }
                    if (draft.defaults != null) SecondaryButton(stringResource(R.string.input_hidden_count, draft.hiddenDefaults().size), compact = true) {
                        draft.popover = if (draft.popover == EditorPopover.HIDDEN) null else EditorPopover.HIDDEN
                    }
                    if (draft.defaults != null) SecondaryButton(stringResource(R.string.hud_reset_button), enabled = !draft.atDefaults, compact = true) { draft.reset() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.snap, { draft.snap = it; ControllerPrefs.setSnap(context, it) })
                        Text(stringResource(R.string.ctrl_editor_snap))
                    }
                    if (draft.elements.isNotEmpty()) {
                        SecondaryButton("−", enabled = draft.scale > 80, compact = true) { draft.scaleAll((draft.scale - 5).coerceAtLeast(80)) }
                        Text("${draft.scale}%", Modifier.widthIn(min = 44.dp).testTag("input-editor-scale"), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SecondaryButton("+", enabled = draft.scale < 150, compact = true) { draft.scaleAll((draft.scale + 5).coerceAtMost(150)) }
                    }
                    PrimaryButton(stringResource(R.string.input_editor_done), compact = true, onClick = onSave)
                }
            }
            Row(Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = .7f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                splitLabel?.let { SecondaryButton(it, compact = true, modifier = Modifier.testTag("input-editor-split"), onClick = onSplit) }
            }
            failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            when (draft.popover) {
                EditorPopover.ADD -> EditorList { AddRows(draft) }
                EditorPopover.HIDDEN -> EditorList { HiddenRows(draft) }
                null -> Unit
            }
        }
        val e = draft.element
        if (e != null && !draft.properties && draft.popover == null && !draft.confirmDiscard) {
            val density = LocalDensity.current
            var size by remember { mutableStateOf(IntSize.Zero) }
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            val gap = with(density) { 12.dp.toPx() }
            val margin = with(density) { 8.dp.toPx() }
            val top = h * e.y - gap - size.height
            val above = top > with(density) { 110.dp.toPx() }
            val x = (w * (e.x + e.width / 2) - size.width / 2).coerceIn(margin, (w - size.width - margin).coerceAtLeast(margin))
            val y = if (above) top else (h * (e.y + e.height) + gap).coerceAtMost(h - size.height - margin)
            EditorBar(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.onSizeChanged { size = it }.testTag("input-editor-selection")) {
                SecondaryButton("−", compact = true) { draft.resizeSelected(1f / 1.1f) }
                SecondaryButton("+", compact = true) { draft.resizeSelected(1.1f) }
                PrimaryButton(stringResource(R.string.input_options), compact = true) { draft.clickProperties = false; draft.properties = true }
                if (e.hasClick) SecondaryButton(stringResource(R.string.input_click_short), compact = true) { draft.clickProperties = true; draft.properties = true }
                SecondaryButton(stringResource(R.string.input_remove_element), compact = true) { draft.remove(e.id) }
            }
        }
    }
}

@Composable
private fun EditorList(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.widthIn(min = 260.dp, max = 360.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .97f),
        contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 8.dp) {
        Column(Modifier.padding(6.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun EditorRow(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        SecondaryButton(action, compact = true, onClick = onClick)
    }
}

@Composable
private fun CloseRow(draft: SurfaceDraft) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        SecondaryButton(stringResource(R.string.common_close), compact = true) { draft.popover = null }
    }
}

@Composable
private fun AddRows(draft: SurfaceDraft) {
    val context = LocalContext.current
    CloseRow(draft)
    for (control in listOf(InputControl.L3, InputControl.R3).filter { c -> draft.elements.none { it.id == "legacy:${c.id}" } })
        EditorRow(control.label, stringResource(R.string.input_editor_add)) { draft.add(InputSurfaceElement(control, .47f, .45f, .06f, .1f, buttonShape = InputButtonShape.CIRCLE)) }
    for (kind in InputElementKind.entries) EditorRow(stringResource(kind.label()), stringResource(R.string.input_editor_add)) {
        val w = if (kind.control.dimensions == 2) .2f else .1f
        val h = if (kind.control.dimensions == 2) .35f else .16f
        val name = context.getString(kind.label()) + " " + (draft.elements.count { it.kind == kind } + 1)
        draft.add(InputSurfaceElement(kind.control, (1f - w) / 2, (1f - h) / 2, w, h, newInputId(), kind, name))
    }
}

@Composable
private fun HiddenRows(draft: SurfaceDraft) {
    val hidden = draft.hiddenDefaults()
    CloseRow(draft)
    if (hidden.isEmpty()) Text(stringResource(R.string.input_nothing_hidden), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (e in hidden) EditorRow(e.name, stringResource(R.string.input_restore)) { draft.add(e) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurfaceToolbar(draft: SurfaceDraft, onSave: () -> Unit, onLeave: () -> Unit, onDiscard: () -> Unit) {
    val context = LocalContext.current
    if (draft.confirmDiscard) {
        FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.input_editor_discard), style = MaterialTheme.typography.titleSmall)
            SecondaryButton(stringResource(R.string.input_keep_editing), compact = true) { draft.confirmDiscard = false }
            PrimaryButton(stringResource(R.string.input_discard), compact = true, onClick = onDiscard)
        }
        return
    }
    FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onLeave)
        SecondaryButton(stringResource(R.string.input_editor_add), enabled = draft.elements.size < 64, compact = true) { draft.popover = EditorPopover.ADD }
        if (draft.defaults != null) SecondaryButton(stringResource(R.string.input_hidden_count, draft.hiddenDefaults().size), compact = true) { draft.popover = EditorPopover.HIDDEN }
        SecondaryButton(stringResource(R.string.input_editor_undo), enabled = draft.history.isNotEmpty(), compact = true) { draft.undo() }
        if (draft.defaults != null) SecondaryButton(stringResource(R.string.input_reset_positions), enabled = draft.positioned != null, compact = true) { draft.resetPositions() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(draft.snap, { draft.snap = it; ControllerPrefs.setSnap(context, it) })
            Text(stringResource(R.string.ctrl_editor_snap))
        }
        PrimaryButton(stringResource(R.string.input_editor_done), compact = true, onClick = onSave)
    }
    if (draft.elements.isNotEmpty()) SliderRow(stringResource(R.string.ctrl_size), null, draft.scale, 80..125, step = 5,
        format = { "$it%" }, modifier = Modifier.testTag("input-editor-scale")) { draft.scaleAll(it) }
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
