package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.*
import com.droiddeck.launcher.session.SessionPrefs

@Composable
fun InputSettingsDialog(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity() as? com.droiddeck.launcher.SessionActivity
    val target = activity?.inputEditorDisplay(InputRole.SECONDARY)
    if (activity != null && target != null && target.displayId != activity.window.decorView.display?.displayId) {
        InputDisplayContent(activity, target, onBack) { InputSettingsPane(onBack, onChanged) }
    } else InputSettingsWindow(onBack, onChanged)
}

@Composable
private fun InputSettingsWindow(onBack: () -> Unit, onChanged: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        InputSettingsPane(onBack, onChanged)
    }
}

@Composable
private fun InputSettingsPane(onBack: () -> Unit, onChanged: () -> Unit) {
    var editingSurface by remember { mutableStateOf(false) }
    val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    DisposableEffect(window, editingSurface) {
        window?.setDimAmount(if (editingSurface) 0f else .32f)
        onDispose { }
    }
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
        CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
            Surface(Modifier.fillMaxSize(), color = if (editingSurface) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surface) {
                InputSettingsPage(onBack, onChanged, onSurfaceEditing = { editingSurface = it })
            }
        }
    }
}

private enum class InputPage { HOME, PROFILE, SOURCE, LAYOUTS, REUSE }
private data class InputBindingEdit(val preset: String, val control: InputControl, val element: InputSurfaceElement? = null, val key: PhysicalInputKey? = null, val click: Boolean = false)
private data class InputNameRequest(val title: Int, val initial: String, val sources: List<Pair<String, String>> = emptyList(),
    val source: String = "", val offerSharing: Boolean = false, val save: (String, String, Boolean) -> Unit)
private data class InputConfirmation(val title: String, val detail: String, val action: () -> Unit)
private data class InputReuseRequest(val name: String, val layoutOnly: Boolean, val apply: (Boolean) -> Unit)

@Composable
fun InputSettingsPage(onBack: () -> Unit, onChanged: () -> Unit = {}, embedded: Boolean = false, onSurfaceEditing: (Boolean) -> Unit = {}) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        InputSettingsContent(onBack, onChanged, embedded, onSurfaceEditing)
    }
}

@Composable
private fun InputSettingsContent(onBack: () -> Unit, onChanged: () -> Unit, embedded: Boolean, onSurfaceEditing: (Boolean) -> Unit) {
    val context = LocalContext.current
    val host = rememberMenuHost()
    var library by remember { mutableStateOf(InputLibraries.read(context)) }
    var page by remember { mutableStateOf(InputPage.HOME) }
    var bindingsTab by remember { mutableIntStateOf(0) }
    var profileId by remember { mutableStateOf(InputProfile.GAME) }
    var role by remember { mutableStateOf(InputRole.PHYSICAL) }
    var edit by remember { mutableStateOf<InputBindingEdit?>(null) }
    var naming by remember { mutableStateOf<InputNameRequest?>(null) }
    var confirmation by remember { mutableStateOf<InputConfirmation?>(null) }
    var reuse by remember { mutableStateOf<InputReuseRequest?>(null) }
    var capture by remember { mutableStateOf(false) }
    var surface by remember { mutableStateOf(false) }
    LaunchedEffect(surface) { onSurfaceEditing(surface) }
    DisposableEffect(Unit) { onDispose { onSurfaceEditing(false) } }
    var managingLayout by remember { mutableStateOf<SavedInputLayout?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val controller = rememberControllerSettings()
    var oscMode by remember { mutableStateOf(SessionPrefs.oscMode(context)) }
    fun update(change: (InputLibrary) -> InputLibrary): Boolean = runCatching { InputLibraries.update(context, change) }
        .onSuccess { library = it; onChanged() }.onFailure { error = it.message ?: context.getString(R.string.input_save_failed) }.isSuccess
    fun go(next: InputPage) { page = next; host.open = null }
    fun back() {
        when {
            edit != null -> edit = null
            page == InputPage.HOME -> onBack()
            page == InputPage.PROFILE -> go(InputPage.HOME)
            page == InputPage.SOURCE -> go(InputPage.PROFILE)
            else -> go(InputPage.SOURCE)
        }
    }
    BackHandler(enabled = !embedded || page != InputPage.HOME || edit != null, onBack = ::back)
    val profile = library.profile(profileId)
    val preset = library.preset(profile.binding(role))
    val layout = library.layout(profileId, role)
    fun rename(title: Int, name: String, save: (String) -> Unit) {
        naming = InputNameRequest(title, name) { value, _, _ -> save(value) }
    }
    fun newProfile(copy: Boolean) {
        naming = InputNameRequest(if (copy) R.string.input_copy_profile else R.string.input_new_profile,
            if (copy) profile.name else "", library.profiles.map { it.id to it.name }, profileId, offerSharing = true) { name, source, shared ->
            val id = newInputId()
            if (update { it.duplicateProfile(source, name, id, sharePhysical = shared) }) { profileId = id; go(InputPage.PROFILE) }
        }
    }
    fun editPart(nextRole: InputRole) { role = nextRole; bindingsTab = 0; go(InputPage.SOURCE) }
    val pageFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(page, role, profileId, edit != null, surface) {
        if (!settled) { settled = true; return@LaunchedEffect }
        if (surface || inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { pageFocus.requestFocus() }
    }
    val editing = edit
    Box(Modifier.focusRequester(pageFocus).focusGroup()) {
    if (editing != null) {
        val b = library.preset(editing.preset)
        var elementProperties by remember(editing) { mutableStateOf(editing.element) }
        val current = editing.element?.let { if (editing.click) b.clickAction(it) else b.action(it) }
            ?: editing.key?.let { k -> b.keys.firstOrNull { it.key == k }?.action ?: InputAction.Disabled }
            ?: b.controls[editing.control] ?: InputAction.Native()
        InputActionEditor(editing.control, current, if (editing.click) stringResource(R.string.input_click_name, editing.element!!.name)
            else editing.element?.name ?: editing.key?.label ?: editing.control.label,
            properties = { if (!editing.click) elementProperties?.let { e -> InputElementProperties(e) { elementProperties = it } } },
            onBack = { edit = null }, element = editing.element, nativeTargets = editing.key == null, click = editing.click,
            detail = if (library.usersOfBindings(b.id).size > 1) stringResource(R.string.input_shared_detail, b.name, library.usersOfBindings(b.id).size)
                else "${profile.name} · ${roleLabel(role)}", onSave = { action ->
                if (update { l ->
                    val geometry = if (!editing.click && elementProperties != null && layout != null) l.withLayout(layout.copy(elements = layout.elements.map {
                        if (it.id == elementProperties!!.id) elementProperties!!.copy(name = elementProperties!!.name.trim().ifBlank { it.name }) else it
                    })) else l
                    geometry.withPreset(when {
                    editing.element != null && editing.click -> b.copy(clicks = b.clicks + (editing.element.id to action))
                    editing.element != null -> b.copy(elements = b.elements + (editing.element.id to action))
                    editing.key != null -> b.copy(keys = b.keys.filterNot { it.key == editing.key } + PhysicalInputBinding(editing.key, action))
                    else -> b.copy(controls = b.controls + (editing.control to action))
                }) }) edit = null
            })
    } else if (!surface) key(page, profileId, role) {
        SettingsPage(host, title = when (page) {
            InputPage.HOME -> if (embedded) null else stringResource(R.string.setup_controller)
            InputPage.PROFILE -> profile.name
            InputPage.SOURCE -> roleLabel(role)
            InputPage.LAYOUTS -> stringResource(R.string.input_saved_layouts)
            InputPage.REUSE -> stringResource(R.string.input_use_settings_from)
        }, eyebrow = when (page) {
                InputPage.SOURCE -> profile.name
                InputPage.LAYOUTS, InputPage.REUSE -> "${profile.name} · ${roleLabel(role)}"
                else -> null
            },
            onBack = ::back, showBack = !(embedded && page == InputPage.HOME), compactLayout = true) {
            when (page) {
                InputPage.HOME -> {
                    SettingsGroup(stringResource(R.string.input_default_profiles)) {
                        ChoiceRow(host, "auto-desktop", stringResource(R.string.input_desktop_mode), null,
                            library.profiles.map { it.id to it.name }, library.desktop) { id -> update { it.copy(desktop = id) } }
                        ChoiceRow(host, "auto-game", stringResource(R.string.input_game_mode), null,
                            library.profiles.map { it.id to it.name }, library.steam) { id -> update { it.copy(steam = id) } }
                        for (desktop in listOf(true, false)) {
                            val selection = library.selection(desktop)
                            if (selection.profile != InputProfiles.AUTO || selection.parts.isNotEmpty()) InputHint(stringResource(R.string.input_quick_override_hint,
                                stringResource(if (desktop) R.string.input_desktop_mode else R.string.input_game_mode)))
                        }
                    }
                    if (InputLibraries.hasPendingScreenKeyMigration(context)) InputHint(stringResource(R.string.input_import_failed))
                    if (InputLibraries.hasLibraryRecovery(context)) InputHint(stringResource(R.string.input_library_recovered))
                    if (library.profiles.any { it.id.startsWith("migrated.screenkeys.") }) Text(stringResource(R.string.input_imported_keys_hint),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SettingsGroup(stringResource(R.string.input_profiles)) {
                        for (p in library.profiles) InputNavigationRow(p.name, when {
                            p.id == library.steam && p.id == library.desktop -> stringResource(R.string.input_default_both)
                            p.id == library.steam -> stringResource(R.string.input_default_game)
                            p.id == library.desktop -> stringResource(R.string.input_default_desktop)
                            else -> null
                        }, Modifier.testTag("input-profile-${p.id}")) { profileId = p.id; go(InputPage.PROFILE) }
                        PadTextButton(onClick = { newProfile(false) }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.input_new_profile)) }
                    }
                    SettingsGroup(stringResource(R.string.input_device_settings)) {
                        ToggleRow(host, "controller-rumble", stringResource(R.string.ctrl_rumble), null, controller.rumble) { ControllerPrefs.setRumble(context, it); onChanged() }
                        ChoiceRow(host, "controller-dead-zone", stringResource(R.string.ctrl_dead_zone), null, ControllerPrefs.deadZones.map { it to stringResource(R.string.ctrl_percent, it) }, controller.deadZone) { ControllerPrefs.setDeadZone(context, it); onChanged() }
                        ToggleRow(host, "controller-joycon-vr", stringResource(R.string.ctrl_joycon_vr), stringResource(R.string.ctrl_joycon_vr_hint), controller.joyConVr) { ControllerPrefs.setJoyConVr(context, it); onChanged() }
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
                InputPage.PROFILE -> {
                    SettingsGroup(stringResource(R.string.input_profile_controls)) {
                        for (r in InputRole.entries) InputNavigationRow(roleLabel(r),
                            if (r == InputRole.PHYSICAL) stringResource(R.string.input_physical_hint) else library.layout(profileId, r)?.name,
                            Modifier.testTag("input-part-${r.name.lowercase()}"), leading = {
                                library.layout(profileId, r)?.let { InputLayoutThumbnail(it, r) }
                            }) { editPart(r) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Actions {
                        SecondaryButton(stringResource(R.string.input_rename)) { rename(R.string.input_rename, profile.name) { name -> update { it.withProfile(profile.copy(name = name)) } } }
                        SecondaryButton(stringResource(R.string.input_copy_profile)) { newProfile(true) }
                        SecondaryButton(stringResource(R.string.input_reset_actions)) {
                            confirmation = InputConfirmation(context.getString(R.string.input_reset_actions), context.getString(R.string.input_reset_profile_detail, profile.name)) { update { it.resetProfile(profileId) } }
                        }
                        if (profileId !in listOf(InputProfile.GAME, InputProfile.DESKTOP)) SecondaryButton(stringResource(R.string.input_delete)) {
                            confirmation = InputConfirmation(context.getString(R.string.input_delete), context.getString(R.string.input_delete_profile_detail, profile.name)) {
                                if (update { it.removeProfile(profileId) }) { profileId = InputProfile.GAME; go(InputPage.HOME) }
                            }
                        }
                    }
                }
                InputPage.SOURCE -> {
                    if (role == InputRole.PRIMARY) SettingsGroup(stringResource(R.string.input_screen_visibility)) {
                        ChoiceRow(host, "controller-osc", stringResource(R.string.mode_osc), stringResource(R.string.input_visibility_shared),
                            listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always),
                                SessionPrefs.OSC_STEAM_TOUCH to stringResource(R.string.osc_steam_touch), SessionPrefs.OSC_STEAM_QAM to stringResource(R.string.mode_osc_qam), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never)),
                            oscMode) { SessionPrefs.setOscMode(context, it); oscMode = it; onChanged() }
                    }
                    if (layout != null) SettingsGroup(stringResource(R.string.ctrl_layout)) {
                        if (layout.editable) InputNavigationRow(stringResource(R.string.input_edit_layout), layout.name, Modifier.testTag("input-surface-${role.name.lowercase()}")) { surface = true }
                        else InputHint(layout.name)
                        InputNavigationRow(stringResource(R.string.input_saved_layouts), stringResource(R.string.input_saved_layouts_hint)) { go(InputPage.LAYOUTS) }
                        if (layout.editable) {
                            InputColorRow(host, layout.style.tint) { color -> update { it.withLayout(layout.copy(style = layout.style.copy(tint = color))) } }
                            SliderRow(stringResource(R.string.ctrl_opacity), null, layout.style.opacity, 1..100, step = 1,
                                format = { "$it%" }) { opacity -> update { it.withLayout(layout.copy(style = layout.style.copy(opacity = opacity))) } }
                        }
                        if (role == InputRole.SECONDARY) InputHint(stringResource(R.string.input_second_screen_enable))
                    }
                    SettingsGroup(stringResource(R.string.input_assign_actions)) {
                        val users = library.usersOfBindings(preset.id)
                        if (users.size > 1) InputHint(stringResource(R.string.input_shared_with, users.filterNot { it.id == profileId }.joinToString { it.name }))
                        val elements = layout?.takeIf { it.editable }?.elements
                        if (elements != null) {
                            if (elements.isEmpty()) InputHint(stringResource(R.string.input_empty_elements))
                            for (e in elements) {
                                InputNavigationRow(e.name, actionLabel(preset.action(e)), Modifier.testTag("input-element-${e.id}")) { edit = InputBindingEdit(preset.id, e.control, e) }
                                if (e.hasClick) InputNavigationRow(stringResource(R.string.input_click_name, e.name), actionLabel(preset.clickAction(e)), Modifier.testTag("input-click-${e.id}")) { edit = InputBindingEdit(preset.id, InputControl.A, e, click = true) }
                            }
                        } else {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            SheetChips(listOf(stringResource(R.string.input_buttons), stringResource(R.string.input_axes)) +
                                if (preset.physical) listOf(stringResource(R.string.input_extra_keys)) else emptyList(), bindingsTab) { bindingsTab = it }
                            }
                            val controls = InputControl.padControls.asList()
                            if (bindingsTab != 2) InputControlGrid(preset, controls, bindingsTab == 1) { edit = InputBindingEdit(preset.id, it) }
                            else {
                                for (b in preset.keys) {
                                    InputNavigationRow(b.key.label, actionLabel(b.action)) { edit = InputBindingEdit(preset.id, InputControl.A, key = b.key) }
                                    PadTextButton(onClick = { update { it.withPreset(preset.copy(keys = preset.keys - b)) } }) { Text(stringResource(R.string.input_remove_binding)) }
                                }
                                InputNavigationRow(stringResource(R.string.input_capture), stringResource(R.string.input_capture_hint)) { capture = true }
                            }
                        }
                    }
                    SettingsGroup(stringResource(R.string.input_reuse)) {
                        InputNavigationRow(stringResource(R.string.input_use_settings_from), stringResource(R.string.input_reuse_hint)) { go(InputPage.REUSE) }
                        if (library.usersOfBindings(preset.id).size > 1 || layout?.let { it.editable && library.usersOfLayout(it.id).size > 1 } == true)
                            InputNavigationRow(stringResource(R.string.input_make_independent), stringResource(R.string.input_independent_hint), Modifier.testTag("input-make-independent")) {
                                update { it.usePart(profileId, role, profileId, shared = false) }
                            }
                    }
                }
                InputPage.LAYOUTS -> {
                    InputHint(stringResource(R.string.input_layout_geometry_hint))
                    SettingsGroup(stringResource(R.string.input_saved_layouts)) {
                        var showOther by remember { mutableStateOf(false) }
                        val available = library.layouts.filter { it.supports(role) }
                        val other = available.filter { saved -> !saved.id.startsWith("builtin.") && saved.id != layout?.id &&
                            library.usersOfLayout(saved.id).isEmpty() && library.bindings.none { it.layoutId == saved.id && library.selectedBindings(it.id) } }
                        for (saved in available.filter { showOther || it !in other }) {
                            InputLayoutRow(saved, role, if (saved.id == layout?.id) stringResource(R.string.input_current_layout) else null,
                                onManage = { managingLayout = saved }) {
                                reuse = InputReuseRequest(saved.name, layoutOnly = true) { shared -> if (update { it.useLayout(profileId, role, saved.id, shared) }) go(InputPage.SOURCE) }
                            }
                        }
                        if (other.isNotEmpty()) PadTextButton(onClick = { showOther = !showOther }, modifier = Modifier.padding(8.dp)) {
                            Text(stringResource(if (showOther) R.string.input_hide_other_layouts else R.string.input_other_layouts, other.size))
                        }
                        PadTextButton(onClick = { naming = InputNameRequest(R.string.input_new_layout, "") { name, _, _ ->
                            val created = SavedInputLayout(newInputId(), name, screen = if (role == InputRole.PRIMARY) InputLayoutScreen.MAIN else InputLayoutScreen.SECONDARY)
                            if (update { it.withLayout(created).useLayout(profileId, role, created.id, shared = true) }) { go(InputPage.SOURCE); surface = true }
                        } }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.input_new_layout)) }
                    }

                }
                InputPage.REUSE -> {
                    InputHint(stringResource(R.string.input_reuse_explanation, roleLabel(role)))
                    SettingsGroup(stringResource(R.string.input_profiles)) {
                        for (p in library.profiles.filterNot { it.id == profileId }) InputNavigationRow(p.name, library.layout(p.id, role)?.name) {
                            reuse = InputReuseRequest(p.name, layoutOnly = false) { shared -> if (update { it.usePart(profileId, role, p.id, shared) }) go(InputPage.SOURCE) }
                        }
                    }
                }

            }
        }
    }
    }
    managingLayout?.let { saved -> AlertDialog(onDismissRequest = { managingLayout = null }, title = { Text(saved.name) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.input_layout_count, saved.elements.size))
            SecondaryButton(stringResource(R.string.input_rename)) {
                managingLayout = null
                rename(R.string.input_rename_layout, saved.name) { name -> update { it.withLayout(saved.copy(name = name)) } }
            }
            val used = library.usersOfLayout(saved.id).isNotEmpty() || library.bindings.any { it.layoutId == saved.id && library.selectedBindings(it.id) }
            if (!used && saved.editable) SecondaryButton(stringResource(R.string.input_delete)) {
                managingLayout = null
                confirmation = InputConfirmation(context.getString(R.string.input_delete), context.getString(R.string.input_delete_layout_detail, saved.name)) { update { it.removeLayout(saved.id) } }
            }
        } }, confirmButton = { PadTextButton(onClick = { managingLayout = null }) { Text(stringResource(R.string.common_close)) } }) }
    if (capture) InputCaptureDialog(onDismiss = { capture = false }) { capture = false; edit = InputBindingEdit(preset.id, InputControl.A, key = it) }
    if (surface) InputSurfaceDialog(profileId, onDismiss = { surface = false; library = InputLibraries.read(context) }, onChanged = {
        library = InputLibraries.read(context); onChanged()
    }, role = role, layoutId = layout?.id, bindingsId = preset.id)
    naming?.let { request -> key(request) { InputNameDialog(request) { naming = null } } }
    reuse?.let { request -> key(request) { InputReuseDialog(request) { reuse = null } } }
    confirmation?.let { request -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(request.title) },
        text = { Text(request.detail) }, confirmButton = { PadTextButton(onClick = { confirmation = null; request.action() }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { PadTextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.common_cancel)) } }) }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(R.string.input_save_failed)) },
        text = { Text(message) }, confirmButton = { PadTextButton(onClick = { error = null }) { Text(stringResource(R.string.common_ok)) } }) }
}

@Composable
private fun InputNameDialog(request: InputNameRequest, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(request.initial) }
    var source by remember { mutableStateOf(request.source) }
    var shared by remember { mutableStateOf(false) }
    val host = rememberMenuHost()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(request.title)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.input_name)) }, singleLine = true, modifier = Modifier.testTag("input-name"))
        if (request.sources.isNotEmpty()) ChoiceRow(host, "input-copy-source", stringResource(R.string.input_start_from), null, request.sources, source) { source = it }
        if (request.offerSharing) {
            Row(Modifier.fillMaxWidth().focusRow(role = Role.Checkbox, checked = shared) { shared = !shared }.testTag("input-share-physical"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(shared, null)
                Text(stringResource(R.string.input_share_physical))
            }
            Text(stringResource(R.string.input_new_independent_hint), style = MaterialTheme.typography.bodySmall)
        }
    } }, confirmButton = { PadTextButton(enabled = name.isNotBlank(), onClick = { request.save(name.trim(), source, shared); onDismiss() }) { Text(stringResource(R.string.gesture_save_changes)) } },
        dismissButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun InputReuseDialog(request: InputReuseRequest, onDismiss: () -> Unit) {
    var shared by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(request.name) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(if (request.layoutOnly) R.string.input_reuse_layout_detail else R.string.input_reuse_part_detail))
        for (link in listOf(false, true)) Row(Modifier.fillMaxWidth().focusRow(role = Role.RadioButton, checked = shared == link) { shared = link }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(shared == link, null)
            Text(stringResource(if (link) R.string.input_keep_shared else R.string.input_independent_copy))
        }
    } }, confirmButton = { PadTextButton(onClick = { request.apply(shared); onDismiss() }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
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
private fun InputNavigationRow(title: String, detail: String?, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().focusRow(RoundedCornerShape(0.dp), onClick = onClick).heightIn(min = 64.dp).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun roleLabel(role: InputRole) = stringResource(when (role) {
    InputRole.PHYSICAL -> R.string.input_physical; InputRole.PRIMARY -> R.string.input_primary; InputRole.SECONDARY -> R.string.input_secondary
})

@Composable
private fun InputHint(text: String) {
    Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
