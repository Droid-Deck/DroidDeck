package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.session.SessionPrefs

/** The in-game Remap buttons page. */
@Composable
fun InputSettingsDialog(onBack: () -> Unit, onChanged: () -> Unit, desktop: Boolean = false) {
    val context = LocalContext.current
    val activity = context.findActivity() as? com.droiddeck.launcher.SessionActivity
    val target = activity?.inputEditorDisplay(InputRole.SECONDARY)
    if (activity != null && target != null && target.displayId != activity.window.decorView.display?.displayId) {
        InputDisplayContent(activity, target, onBack) { InputSettingsPane(onBack, onChanged, desktop) }
    } else Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        InputSettingsPane(onBack, onChanged, desktop)
    }
}

@Composable
private fun InputSettingsPane(onBack: () -> Unit, onChanged: () -> Unit, desktop: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
        CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                InputSettingsPage(onBack, onChanged, remapOnly = true, desktop = desktop)
            }
        }
    }
}

private enum class InputPage { HOME, REMAP }
private data class InputBindingEdit(val preset: String, val control: InputControl, val key: PhysicalInputKey? = null)

/** Settings › Controller: the in-game Controller page for the default, or with [remapOnly] just the physical controller's buttons. */
@Composable
fun InputSettingsPage(onBack: () -> Unit, onChanged: () -> Unit = {}, embedded: Boolean = false, onSurfaceEditing: (Boolean) -> Unit = {},
    remapOnly: Boolean = false, desktop: Boolean = false) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        InputSettingsContent(onBack, onChanged, embedded, onSurfaceEditing, remapOnly, desktop)
    }
}

@Composable
private fun InputSettingsContent(onBack: () -> Unit, onChanged: () -> Unit, embedded: Boolean, onSurfaceEditing: (Boolean) -> Unit,
    remapOnly: Boolean, desktop: Boolean) {
    val context = LocalContext.current
    val host = rememberMenuHost()
    var library by remember { mutableStateOf(InputLibraries.read(context)) }
    var page by remember { mutableStateOf(if (remapOnly) InputPage.REMAP else InputPage.HOME) }
    var bindingsTab by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<InputBindingEdit?>(null) }
    var capture by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(surface) { onSurfaceEditing(surface != null) }
    DisposableEffect(Unit) { onDispose { onSurfaceEditing(false) } }
    var error by remember { mutableStateOf<String?>(null) }
    val controller = rememberControllerSettings()
    fun changed() { library = InputLibraries.read(context); revision++; onChanged() }
    fun update(change: (InputLibrary) -> InputLibrary): Boolean = runCatching { InputLibraries.update(context, change) }
        .onSuccess { library = it; onChanged() }.onFailure { error = it.message ?: context.getString(R.string.input_save_failed) }.isSuccess
    fun go(next: InputPage) { page = next; host.open = null }
    fun back() {
        when {
            edit != null -> edit = null
            page == InputPage.REMAP && !remapOnly -> go(InputPage.HOME)
            else -> onBack()
        }
    }
    BackHandler(enabled = !embedded || page != InputPage.HOME || edit != null, onBack = ::back)
    val profile = library.profile(library.activeId(desktop))
    val preset = library.preset(library.activePart(desktop, InputRole.PHYSICAL).bindings)
    val pageFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(page, edit != null, surface) {
        if (!settled) { settled = true; return@LaunchedEffect }
        if (surface != null || inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { pageFocus.requestFocus() }
    }
    val editing = edit
    Box(Modifier.focusRequester(pageFocus).focusGroup()) {
    if (editing != null) {
        val b = library.preset(editing.preset)
        val current = editing.key?.let { k -> b.keys.firstOrNull { it.key == k }?.action ?: InputAction.Disabled }
            ?: b.controls[editing.control] ?: InputAction.Native()
        InputActionEditor(editing.control, current, editing.key?.label ?: editing.control.label,
            onBack = { edit = null }, nativeTargets = editing.key == null, detail = stringResource(R.string.input_remap_buttons), onSave = { action ->
                if (update { l -> l.withPreset(when {
                    editing.key != null -> b.copy(keys = b.keys.filterNot { it.key == editing.key } + PhysicalInputBinding(editing.key, action))
                    else -> b.copy(controls = b.controls + (editing.control to action))
                }) }) edit = null
            })
    } else if (surface == null) key(page) {
        SettingsPage(host, title = when (page) {
            InputPage.HOME -> if (embedded) null else stringResource(R.string.setup_controller)
            InputPage.REMAP -> stringResource(R.string.input_remap_buttons)
        }, onBack = ::back, showBack = !(embedded && page == InputPage.HOME), compactLayout = true) {
            when (page) {
                InputPage.HOME -> {
                    if (InputLibraries.hasPendingScreenKeyMigration(context)) InputHint(stringResource(R.string.input_import_failed))
                    if (InputLibraries.hasLibraryRecovery(context)) InputHint(stringResource(R.string.input_library_recovered))
                    DefaultControlsGroup(host, revision, onEdit = { surface = it }, onChanged = ::changed,
                        onFailure = { error = it.message ?: context.getString(R.string.input_save_failed) })
                    SettingsGroup(stringResource(R.string.drawer_page_controller)) {
                        var padType by remember { mutableStateOf(SessionPrefs.steamController(context)) }
                        ChoiceRow(host, "pad-type", stringResource(R.string.input_virtual_controller), null,
                            listOf(SessionPrefs.CONTROLLER_DECK to stringResource(R.string.mode_controller_deck),
                                SessionPrefs.CONTROLLER_STEAM to stringResource(R.string.mode_controller_steam),
                                SessionPrefs.CONTROLLER_DS4 to stringResource(R.string.mode_controller_ds4),
                                SessionPrefs.CONTROLLER_DUALSENSE to stringResource(R.string.mode_controller_dualsense),
                                SessionPrefs.CONTROLLER_XBOX360 to stringResource(R.string.mode_controller_x360)),
                            padType) { SessionPrefs.setSteamController(context, it); padType = it; onChanged() }
                        ToggleRow(host, "controller-rumble", stringResource(R.string.ctrl_rumble), null, controller.rumble) { ControllerPrefs.setRumble(context, it); onChanged() }
                        ChoiceRow(host, "controller-dead-zone", stringResource(R.string.ctrl_dead_zone), null, ControllerPrefs.deadZones.map { it to stringResource(R.string.ctrl_percent, it) }, controller.deadZone) { ControllerPrefs.setDeadZone(context, it); onChanged() }
                        ToggleRow(host, "controller-joycon-vr", stringResource(R.string.ctrl_joycon_vr), null, controller.joyConVr) { ControllerPrefs.setJoyConVr(context, it); onChanged() }
                        ActionRow(stringResource(R.string.input_remap_buttons), null, stringResource(R.string.ctrl_edit), { go(InputPage.REMAP) },
                            modifier = Modifier.testTag("input-remap"))
                    }
                    val games = remember(revision) { ControlsChoice.games(context) }
                    if (games.isNotEmpty()) SettingsGroup(stringResource(R.string.osc_games_own)) {
                        for (game in games) ActionRow(game.name, game.layout, stringResource(R.string.osc_use_default), {
                            ControlsChoice.useDefault(context, game.id); changed()
                        }, modifier = Modifier.testTag("osc-use-default-${game.id}"))
                    }
                    SettingsGroup(stringResource(R.string.shortcuts_title)) { PhysicalShortcutRows() }
                    SettingsGroup(stringResource(R.string.input_latency)) {
                        var measuring by remember { mutableStateOf(InputTiming.enabled) }
                        var timing by remember { mutableStateOf(InputTiming.report()) }
                        InputHint(stringResource(R.string.input_latency_hint))
                        ToggleRow(host, "input-measure", stringResource(R.string.input_measure), null, measuring) { measuring = it; InputTiming.enabled = it; if (it) InputTiming.reset() }
                        var reading by remember { mutableStateOf(false) }
                        ActionRow(stringResource(R.string.input_measurements), stringResource(R.string.input_measure_instructions),
                            stringResource(R.string.input_read_timing), { timing = InputTiming.report(); reading = true })
                        if (reading) AlertDialog(onDismissRequest = { reading = false }, title = { Text(stringResource(R.string.input_measurements)) },
                            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(timing)
                                Text(stringResource(R.string.input_measure_instructions), style = MaterialTheme.typography.bodySmall)
                            } }, confirmButton = { PadTextButton(onClick = { reading = false }) { Text(stringResource(R.string.common_close)) } })
                    }
                }
                InputPage.REMAP -> {
                    SettingsGroup(stringResource(R.string.input_physical)) {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            SheetChips(listOf(stringResource(R.string.input_buttons), stringResource(R.string.input_axes), stringResource(R.string.input_extra_keys)), bindingsTab) { bindingsTab = it }
                        }
                        if (bindingsTab != 2) InputControlGrid(preset, InputControl.padControls.asList(), bindingsTab == 1) { edit = InputBindingEdit(preset.id, it) }
                        else {
                            for (b in preset.keys) {
                                InputNavigationRow(b.key.label, actionLabel(b.action)) { edit = InputBindingEdit(preset.id, InputControl.A, key = b.key) }
                                PadTextButton(onClick = { update { it.withPreset(preset.copy(keys = preset.keys - b)) } }) { Text(stringResource(R.string.input_remove_binding)) }
                            }
                            InputNavigationRow(stringResource(R.string.input_capture), null) { capture = true }
                        }
                        val defaults = InputProfile.defaults(profile.base).bindings[InputRole.PHYSICAL].orEmpty()
                        if (preset.controls != defaults || preset.keys.isNotEmpty()) ActionRow(stringResource(R.string.input_reset_actions), null,
                            stringResource(R.string.hud_reset_button), { update { it.withPreset(preset.copy(controls = defaults, keys = emptyList())) } },
                            modifier = Modifier.testTag("input-remap-reset"))
                    }
                }
            }
        }
    }
    }
    if (capture) InputCaptureDialog(onDismiss = { capture = false }) { capture = false; edit = InputBindingEdit(preset.id, InputControl.A, key = it) }
    surface?.let { (profileId, layoutId, bindingsId) ->
        val name = library.layouts.firstOrNull { it.id == layoutId }?.name.orEmpty()
        InputSurfaceDialog(profileId, onDismiss = { surface = null; changed() }, onChanged = ::changed, role = InputRole.PRIMARY,
            layoutId = layoutId, bindingsId = bindingsId, scope = stringResource(R.string.osc_scope_default, name))
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.input_save_failed)) },
        text = { Text(message) }, confirmButton = { PadTextButton(onClick = { error = null }) { Text(stringResource(R.string.common_ok)) } }) }
}

/** The in-game "On-screen controls" group, for games without a layout of their own. */
@Composable
private fun DefaultControlsGroup(host: MenuHost, revision: Int, onEdit: (Triple<String, String, String>) -> Unit, onChanged: () -> Unit,
    onFailure: (Throwable) -> Unit) {
    val context = LocalContext.current
    val options = remember(revision) { ControlsChoice.options(context, null, steamMode = true) }
    val choice = remember(revision) { ControlsChoice.current(context, null, steamMode = true) }
    var oscMode by remember { mutableStateOf(SessionPrefs.oscMode(context)) }
    var touch by remember { mutableStateOf(SessionPrefs.touchMode(context)) }
    var below by remember { mutableStateOf(SessionPrefs.unfoldedControls(context)) }
    SettingsGroup(stringResource(R.string.osc_group_for, stringResource(R.string.osc_default))) {
        ChoiceRow(host, "osc-layout", stringResource(R.string.osc_layout), null, options, choice) { id ->
            runCatching { ControlsChoice.choose(context, null, id, desktop = false) }.onSuccess { onChanged() }.onFailure(onFailure)
        }
        if (choice != GameControls.STEAM) ActionRow(stringResource(R.string.osc_edit_layout), null, stringResource(R.string.ctrl_edit), {
            host.open = null
            ControlsChoice.editTarget(context, null, desktop = false)?.let(onEdit)
        }, modifier = Modifier.testTag("osc-edit"))
        SettingsRow(stringResource(R.string.osc_show), null) {
            SegmentedTabs(listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always),
                SessionPrefs.OSC_NEVER to stringResource(R.string.common_never)), oscMode,
                tabModifier = { Modifier.testTag("osc-$it") }) { SessionPrefs.setOscMode(context, it); oscMode = it; onChanged() }
        }
        ChoiceRow(host, "touch", stringResource(R.string.input_touches), null,
            listOf(SessionPrefs.TOUCH_AUTO to stringResource(R.string.common_auto), SessionPrefs.TOUCH_DIRECT to stringResource(R.string.mode_touch_mouse),
                SessionPrefs.TOUCH_PAD to stringResource(R.string.mode_touch_touchpad), SessionPrefs.TOUCH_GESTURES to stringResource(R.string.mode_touch_gestures),
                SessionPrefs.TOUCH_OFF to stringResource(R.string.widgets_off)), touch) { SessionPrefs.setTouchMode(context, it); touch = it; onChanged() }
        ToggleRow(host, "unfolded-controls", stringResource(R.string.unfolded_controls), null, below) {
            SessionPrefs.setUnfoldedControls(context, it); below = it; onChanged()
        }
    }
}

@Composable
private fun InputControlGrid(preset: SavedInputBindings, available: List<InputControl>, axes: Boolean, onPick: (InputControl) -> Unit) {
    val controls = available.filter { (it.dimensions == 2 || it == InputControl.LT || it == InputControl.RT) == axes }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
        val columns = if (maxWidth < 480.dp) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in controls.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (control in row) OutlinedCard(onClick = { onPick(control) }, modifier = Modifier.weight(1f).controllerConfirm { onPick(control) }.testTag("input-binding-${control.id}")) {
                    Column(Modifier.padding(12.dp).heightIn(min = 48.dp)) {
                        Text(control.label, style = MaterialTheme.typography.titleMedium)
                        Text(actionLabel(preset.controls[control] ?: InputAction.Native()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun InputNavigationRow(title: String, detail: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().focusRow(RoundedCornerShape(0.dp), onClick = onClick).heightIn(min = 64.dp).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InputHint(text: String) {
    Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
