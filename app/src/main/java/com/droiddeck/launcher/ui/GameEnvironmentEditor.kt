package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalInspectionMode
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.core.GameEnvironmentOptions
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Environment variables for Proton games: one shared profile and one per game, in the app's own
// dialog, with each variable's actions in a menu off its value as everywhere else.

@Composable
fun GameEnvironmentRow(modifier: Modifier = Modifier, showHint: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    // Opened with a pad, the editor starts with focus in it; the dialog is a window of its own and
    // would otherwise give the pad nothing to move from.
    var byPad by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current
    SettingsRow(stringResource(R.string.game_env_title), if (showHint) stringResource(R.string.game_env_hint) else null) {
        SecondaryButton(stringResource(R.string.game_env_edit), modifier = modifier) {
            byPad = inputMode.inputMode == InputMode.Keyboard
            open = true
        }
    }
    if (open) GameEnvironmentEditor(byPad) { open = false }
}

/** With a pad driving, focus [target] once the dialog is laid out, and keep the dialog in pad mode. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PadFocus(byPad: Boolean, target: FocusRequester) {
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        if (!byPad) return@LaunchedEffect
        inputMode.requestInputMode(InputMode.Keyboard)
        repeat(2) { withFrameNanos { } }
        runCatching { target.requestFocus() }
    }
}

@Composable
internal fun GameEnvironmentEditor(byPad: Boolean, initialConfig: GameEnvironment.Config = GameEnvironment.Config(), onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val inspecting = LocalInspectionMode.current
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    var config by remember { mutableStateOf<GameEnvironment.Config?>(if (inspecting) initialConfig else null) }
    var games by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var scope by remember { mutableStateOf("") }
    var otherId by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val preset = if (inspecting) com.droiddeck.launcher.core.FexPreset.all.first().id else SessionPrefs.fexPreset(context)
    val forceSsbs = !inspecting && SessionPrefs.forceSsbs(context)
    val firstFocus = remember { FocusRequester() }
    var editByPad by remember { mutableStateOf(false) }
    val dialogInput = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        if (inspecting) return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) {
                GameEnvironmentStore.read(context) to Library.steamGames(context).map { it.appId.toString() to it.name }
            }
        }.onSuccess { (settings, installed) -> config = settings; games = installed }
            .onFailure { error = true }
    }
    fun save(next: GameEnvironment.Config) {
        if (inspecting) { config = next; return }
        busy = true
        coroutine.launch {
            val result = runCatching { withContext(Dispatchers.IO) { GameEnvironmentStore.save(context, next) } }
            result.onSuccess { config = next; error = false }.onFailure { error = true }
            busy = false
        }
    }
    val current = config
    val shared = stringResource(R.string.game_env_shared)
    val profileName = if (scope.isEmpty()) shared
        else games.firstOrNull { it.first == scope }?.second ?: stringResource(R.string.game_env_profile, scope)

    AppDialog(shown, close, "gameEnv", wide = false) {
        PadFocus(byPad, firstFocus)
        DialogHeader(stringResource(R.string.game_env_eyebrow), stringResource(R.string.game_env_title))
        Small(stringResource(R.string.game_env_applies))
        // Which profile: everything shared, or one game's.
        Panel {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.game_env_scope), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                Box {
                    ValueChip(profileName, menu == "scope", modifier = Modifier.focusRequester(firstFocus).widthIn(max = 260.dp)) {
                        menu = if (menu == "scope") null else "scope"
                    }
                    AnchoredMenu(menu == "scope", onDismiss = { if (menu == "scope") menu = null }, title = stringResource(R.string.game_env_scope)) { first ->
                        MenuItem(shared, checked = scope.isEmpty(), focusRequester = first) { scope = ""; otherId = null; menu = null }
                        val profiles = (games + current?.games?.keys.orEmpty().filter { id -> games.none { it.first == id } }.map { it to it }).distinctBy { it.first }
                        for ((id, name) in profiles) MenuItem(name, checked = scope == id) { scope = id; otherId = null; menu = null }
                        MenuItem(stringResource(R.string.game_env_other), checked = false) { otherId = ""; menu = null }
                    }
                }
            }
        }
        otherId?.let { id ->
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AdbTextField(
                    id, { otherId = it.filter(Char::isDigit) }, stringResource(R.string.game_env_app_id), KeyboardType.Number, ImeAction.Done,
                    modifier = Modifier.weight(1f), compact = true,
                    onDone = { if (GameEnvironment.validScope(id)) { scope = id.toLong().toString(); otherId = null } },
                )
                SecondaryButton(stringResource(R.string.game_env_open), enabled = id.isNotEmpty() && GameEnvironment.validScope(id)) {
                    scope = id.toLong().toString(); otherId = null
                }
            }
        }
        if (error) Small(stringResource(R.string.game_env_error), error = true)
        if (current == null && !error) Small(stringResource(R.string.game_env_loading))
        if (current != null) {
            val own = current.entries(scope)
            val entries = GameEnvironment.effective(current, preset, scope, forceSsbs).toSortedMap()
            if (entries.isEmpty()) Small(stringResource(R.string.game_env_empty))
            else Panel {
                entries.entries.forEachIndexed { i, (name, value) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(min = 1.dp, max = 1.dp).background(LocalPalette.current.line))
                    VariableRow(
                        name, value, inherited = !own.containsKey(name), open = menu == "var:$name", enabled = !busy,
                        onToggle = { menu = if (menu == "var:$name") null else "var:$name" }, onDismiss = { if (menu == "var:$name") menu = null },
                        onEdit = { menu = null; editByPad = dialogInput.inputMode == InputMode.Keyboard; editing = name to (value ?: "") },
                        // A value set here or inherited can be switched off for this profile; one set here can go back to inheriting.
                        onUnset = if (value != null) ({ menu = null; save(current.withEntries(scope, own + (name to null))) }) else null,
                        onRestore = if (own.containsKey(name)) ({ menu = null; save(current.withEntries(scope, own - name)) }) else null,
                        shared = scope.isEmpty(),
                    )
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Footer: add a variable, reset the profile, done.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            // Only once the settings are in: a button drawn disabled first comes up without its box.
            if (current != null) Box {
                SecondaryButton(stringResource(R.string.game_env_add), enabled = !busy) { menu = if (menu == "add") null else "add" }
                AnchoredMenu(menu == "add", onDismiss = { if (menu == "add") menu = null }, title = stringResource(R.string.game_env_add)) { first ->
                    for ((i, option) in GameEnvironmentOptions.all.withIndex()) MenuItem(option.name, checked = false, focusRequester = if (i == 0) first else null) {
                        menu = null; editByPad = dialogInput.inputMode == InputMode.Keyboard; editing = option.name to option.value
                    }
                    MenuItem(stringResource(R.string.game_env_custom), checked = false) { menu = null; editByPad = dialogInput.inputMode == InputMode.Keyboard; editing = "" to "" }
                }
            }
            if (current != null && current.entries(scope).isNotEmpty()) FocusText(stringResource(R.string.game_env_reset), LocalPalette.current.signal) {
                save(current.withEntries(scope, emptyMap()))
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.game_env_done), enabled = !busy, onClick = close)
        }
    }
    editing?.let { (name, value) ->
        VariableDialog(name, value, profileName, editByPad, onDismiss = { editing = null }) { key, content ->
            config?.let {
                val entries = it.entries(scope).toMutableMap()
                if (name.isNotEmpty() && name != key) entries[name] = null
                entries[key] = content
                save(it.withEntries(scope, entries))
            }
            editing = null
        }
    }
}

/** The panel the profile and the variables sit in, as a settings group does. */
@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(Shape14).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line, Shape14), content = content)

/** One variable: its name, what it is set to (and whether that comes from the shared profile), and a menu of what to do with it. */
@Composable
private fun VariableRow(
    name: String, value: String?, inherited: Boolean, open: Boolean, enabled: Boolean, shared: Boolean,
    onToggle: () -> Unit, onDismiss: () -> Unit, onEdit: () -> Unit, onUnset: (() -> Unit)?, onRestore: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (inherited) Text(stringResource(if (shared) R.string.game_env_default else R.string.game_env_inherited), fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        Box {
            ValueChip(value ?: stringResource(R.string.game_env_off), open, enabled = enabled, modifier = Modifier.widthIn(max = 200.dp), onClick = onToggle)
            AnchoredMenu(open, onDismiss = onDismiss, title = name) { first ->
                MenuItem(stringResource(R.string.game_env_change), checked = false, focusRequester = first, onClick = onEdit)
                if (onUnset != null) MenuItem(stringResource(if (shared) R.string.game_env_remove else R.string.game_env_unset), checked = false, onClick = onUnset)
                if (onRestore != null) MenuItem(stringResource(if (shared) R.string.game_env_restore_default else R.string.game_env_restore), checked = false, onClick = onRestore)
            }
        }
    }
}

@Composable
private fun VariableDialog(initialName: String, initialValue: String, profile: String, byPad: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var name by remember { mutableStateOf(initialName) }
    var value by remember { mutableStateOf(initialValue) }
    val valid = GameEnvironment.validName(name) && GameEnvironment.validValue(value)
    val option = GameEnvironmentOptions.find(name)
    val nameFocus = remember { FocusRequester() }
    AppDialog(shown, close, "gameEnvVar", wide = false) {
        PadFocus(byPad, nameFocus)
        DialogHeader(profile, stringResource(if (initialName.isEmpty()) R.string.game_env_add else R.string.game_env_change_title))
        VariableNamePicker(name, nameFocus) { selected ->
            name = selected
            value = GameEnvironmentOptions.find(selected)?.value.orEmpty()
        }
        VariableValueEditor(name, value) { value = it }
        if (!GameEnvironment.supported(name)) Small(stringResource(R.string.game_env_unused))
        if (!valid && name.isNotEmpty()) Small(stringResource(R.string.game_env_invalid), error = true)
        DialogFooter(
            note = option?.takeIf { it.detail != 0 }?.let { stringResource(it.detail) },
            confirm = stringResource(R.string.game_env_save), enabled = valid, onCancel = close,
        ) { onSave(name, value); close() }
    }
}

@Composable
private fun VariableNamePicker(name: String, focus: FocusRequester, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(name.isEmpty() || GameEnvironmentOptions.find(name) == null) }
    SettingsRow(stringResource(R.string.game_env_name), null, highlighted = open) {
        Box {
            ValueChip(if (custom) stringResource(R.string.game_env_custom) else name, open, modifier = Modifier.focusRequester(focus).widthIn(max = 260.dp)) { open = !open }
            AnchoredMenu(open, onDismiss = { open = false }, title = stringResource(R.string.game_env_name)) { first ->
                for ((i, option) in GameEnvironmentOptions.all.sortedBy { it.name.uppercase(java.util.Locale.ROOT) }.withIndex()) {
                    MenuItem(option.name, checked = !custom && option.name == name, focusRequester = if (i == 0) first else null) {
                        custom = false; onChange(option.name); open = false
                    }
                }
                MenuItem(stringResource(R.string.game_env_custom), checked = custom) { custom = true; onChange(""); open = false }
            }
        }
    }
    if (custom) AdbTextField(name, onChange, stringResource(R.string.game_env_name), KeyboardType.Ascii, ImeAction.Next, compact = true)
}

@Composable
private fun VariableValueEditor(name: String, value: String, onChange: (String) -> Unit) {
    val option = GameEnvironmentOptions.find(name)
    var open by remember(name) { mutableStateOf(false) }
    var custom by remember(name) { mutableStateOf(option == null || value !in option.choices && option.type != GameEnvironmentOptions.Type.MULTIPLE) }
    when {
        // On/off variables pick from their two values like any other choice: one way to set a value.
        option != null && option.choices.isNotEmpty() -> {
            SettingsRow(stringResource(R.string.game_env_value), null, highlighted = open) {
                Box {
                    ValueChip(if (custom) stringResource(R.string.game_env_custom_value) else value.ifEmpty { stringResource(R.string.game_env_select_value) }, open, modifier = Modifier.widthIn(max = 260.dp)) { open = !open }
                    AnchoredMenu(open, onDismiss = { open = false }, title = name) { first ->
                        val multiple = option.type == GameEnvironmentOptions.Type.MULTIPLE
                        for ((i, choice) in option.choices.withIndex()) {
                            MenuItem(choice, checked = if (multiple) choice in value.split(',') else !custom && choice == value, focusRequester = if (i == 0) first else null) {
                                custom = false
                                if (multiple) onChange(GameEnvironmentOptions.toggle(value, choice)) else { onChange(choice); open = false }
                            }
                        }
                        MenuItem(stringResource(R.string.game_env_custom_value), checked = custom) { custom = true; open = false }
                    }
                }
            }
            if (custom) AdbTextField(value, onChange, stringResource(R.string.game_env_value), KeyboardType.Ascii, ImeAction.Done, compact = true)
        }
        else -> AdbTextField(
            value, onChange, stringResource(R.string.game_env_value),
            if (option?.type == GameEnvironmentOptions.Type.NUMBER) KeyboardType.Number else KeyboardType.Ascii, ImeAction.Done, compact = true,
        )
    }
}
