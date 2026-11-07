package com.droiddeck.launcher.ui

import android.app.Presentation
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

private class SurfaceDraft(val layout: SavedInputLayout, val preset: SavedInputBindings) {
    var elements by mutableStateOf(layout.elements)
    var actions by mutableStateOf(preset.elements)
    var clicks by mutableStateOf(preset.clicks)
    var selected by mutableStateOf<String?>(null)
    var toolsExpanded by mutableStateOf(false)
    var toolsX by mutableFloatStateOf(.02f)
    var toolsY by mutableFloatStateOf(.02f)
    var snap by mutableStateOf(true)
    var adding by mutableStateOf(false)
    var properties by mutableStateOf(false)
    var clickProperties by mutableStateOf(false)
    var history by mutableStateOf<List<Triple<List<InputSurfaceElement>, Map<String, InputAction>, Map<String, InputAction>>>>(emptyList())
    val element get() = elements.firstOrNull { it.id == selected }
    val dirty get() = elements != layout.elements || actions != preset.elements || clicks != preset.clicks
    fun checkpoint() { history = (history + Triple(elements, actions, clicks)).takeLast(40) }
    fun undo() { history.lastOrNull()?.let { elements = it.first; actions = it.second; clicks = it.third; history = history.dropLast(1) } }
    fun action(e: InputSurfaceElement) = preset.copy(elements = actions).action(e)
    fun clickAction(e: InputSurfaceElement) = preset.copy(elements = actions, clicks = clicks).clickAction(e)
}

@Suppress("DEPRECATION")
private fun displayAspect(display: Display): Float? = android.util.DisplayMetrics().also(display::getRealMetrics)
    .takeIf { it.widthPixels > 0 && it.heightPixels > 0 }?.let { (it.widthPixels.toFloat() / it.heightPixels).coerceIn(.05f, 20f) }

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
    val draft = remember { SurfaceDraft(savedLayout, savedBindings) }
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
                latest.withLayout(l.copy(elements = draft.elements, aspect = target?.let(::displayAspect) ?: l.aspect)).withPreset(p.copy(elements = draft.actions.filterKeys { id -> draft.elements.any { it.id == id } },
                    clicks = draft.clicks.filterKeys { id -> draft.elements.any { it.id == id && it.hasClick } }))
            }
        }.onSuccess { onChanged(); onDismiss() }.onFailure { failure = it.message ?: context.getString(R.string.input_save_failed) }
    }
    DisposableEffect(manager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { revision++ }
            override fun onDisplayRemoved(id: Int) { revision++ }
            override fun onDisplayChanged(id: Int) { revision++ }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.unregisterDisplayListener(listener) }
    }
    if (remote && host != null) DisposableEffect(target!!.displayId, host) {
        val presentation = SurfaceEditorPresentation(host, target, transparent = host is SessionActivity && role == InputRole.PRIMARY) {
            SurfaceCanvas(draft, transparent = host is SessionActivity && role == InputRole.PRIMARY)
        }
        runCatching { presentation.show() }.onFailure { failure = it.message }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) presentation.hide()
            if (event == Lifecycle.Event.ON_START) runCatching { presentation.show() }
        }
        host.lifecycle.addObserver(observer)
        onDispose { host.lifecycle.removeObserver(observer); presentation.dismiss() }
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
                        properties = {
                            if (!draft.clickProperties) InputElementProperties(properties) { properties = it }
                            if (layoutUsers > 1) Text(stringResource(R.string.input_editor_shared, layoutUsers),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 12.dp))
                        }, detail = if (bindingUsers > 1) stringResource(R.string.input_shared_detail, savedBindings.name, bindingUsers)
                            else "${library.profile(profile).name} · ${roleLabel(role)}", onSave = { action ->
                            draft.checkpoint()
                            if (draft.clickProperties) draft.clicks = draft.clicks + (element.id to action) else draft.actions = draft.actions + (element.id to action)
                            draft.elements = draft.elements.map { if (it.id == element.id) properties.copy(name = properties.name.trim().ifBlank { element.name }) else it }
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
    if (draft.adding) AlertDialog(onDismissRequest = { draft.adding = false }, title = { Text(stringResource(R.string.input_add_element)) }, confirmButton = {}, text = { Column {
        for (kind in InputElementKind.entries) PadTextButton(onClick = { draft.checkpoint()
            val w = if (kind.control.dimensions == 2) .4f else .2f
            val h = .3f
            val name = context.getString(kind.label()) + " " + (draft.elements.count { it.kind == kind } + 1)
            val element = InputSurfaceElement(kind.control, (1f - w) / 2, (1f - h) / 2, w, h, newInputId(), kind, name)
            draft.elements = draft.elements + element; draft.selected = element.id
            draft.adding = false; draft.toolsExpanded = false }) { Text(stringResource(kind.label())) }
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
            view.style = draft.layout.style; view.elements = draft.elements; view.selected = draft.selected; view.snap = draft.snap
            view.isEnabled = !draft.properties && !draft.adding
            view.onSelect = { if (!draft.properties) draft.selected = it }
            view.onBeginEdit = draft::checkpoint
            view.onChange = { e -> if (!draft.properties) draft.elements = draft.elements.map { if (it.id == e.id) e else it } }

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
        val x = (draft.toolsX * screenWidth).coerceIn(margin, (screenWidth - panelSize.width - margin).coerceAtLeast(margin))
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
    FlowRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onLeave)
        SecondaryButton(stringResource(R.string.input_editor_add), enabled = draft.elements.size < 64, compact = true) { draft.adding = true }
        SecondaryButton(stringResource(R.string.input_editor_undo), enabled = draft.history.isNotEmpty(), compact = true) { draft.undo() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(draft.snap, { draft.snap = it })
            Text(stringResource(R.string.ctrl_editor_snap))
        }
        PrimaryButton(stringResource(R.string.input_editor_done), compact = true, onClick = onSave)
    }
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
                SecondaryButton(stringResource(R.string.input_remove_element), compact = true) {
                    draft.checkpoint(); draft.elements = draft.elements.filterNot { it.id == e.id }; draft.actions = draft.actions - e.id; draft.clicks = draft.clicks - e.id; draft.selected = null
                }
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
