package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class VariableOption(val name: String, val value: String, val detail: Int)

private val variableOptions = listOf(
    VariableOption("MESA_SHADER_CACHE_DISABLE", "false", R.string.game_env_cache),
    VariableOption("VKD3D_FEATURE_LEVEL", "12_2", R.string.game_env_feature),
    VariableOption("VKD3D_SHADER_MODEL", "6_9", R.string.game_env_shader),
    VariableOption("VKD3D_CONFIG", "nodxr", R.string.game_env_vkd3d_config),
    VariableOption("MESA_SHADER_CACHE_MAX_SIZE", "1G", R.string.game_env_cache_size),
    VariableOption("PROTON_LOG", "1", R.string.game_env_proton_log),
    VariableOption("PROTON_USE_WINED3D", "1", R.string.game_env_wined3d),
    VariableOption("PROTON_USE_XALIA", "0", R.string.game_env_xalia),
    VariableOption("DXVK_HUD", "fps", R.string.game_env_hud),
    VariableOption("DXVK_CONFIG", "dxvk.maxFrameRate = 60", R.string.game_env_dxvk_config),
    VariableOption("VKD3D_FRAME_RATE", "60", R.string.game_env_vkd3d_limit),
    VariableOption("mesa_glthread", "true", R.string.game_env_glthread),
)

@Composable
fun GameEnvironmentRow(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(stringResource(R.string.game_env_title), stringResource(R.string.game_env_hint)) {
        TextButton(onClick = { open = true }, modifier = modifier.controllerConfirm { open = true }) {
            Text(stringResource(R.string.game_env_edit))
        }
    }
    if (open) GameEnvironmentEditor { open = false }
}

@Composable
private fun GameEnvironmentEditor(onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var config by remember { mutableStateOf<GameEnvironment.Config?>(null) }
    var games by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var scope by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var scopeMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val preset = SessionPrefs.fexPreset(context)
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                GameEnvironmentStore.read(context) to Library.steamGames(context).map { it.appId.toString() to it.name }
            }
        }.onSuccess { (settings, installed) -> config = settings; games = installed }
            .onFailure { error = true }
    }
    fun save(next: GameEnvironment.Config) {
        busy = true
        coroutine.launch {
            val result = runCatching { withContext(Dispatchers.IO) { GameEnvironmentStore.save(context, next) } }
            result.onSuccess { config = next; error = false }.onFailure { error = true }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.game_env_title)) },
        confirmButton = { TextButton(onClick = onClose, enabled = !busy) { Text(stringResource(R.string.game_env_close)) } },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.game_env_hint))
                Text(stringResource(R.string.game_env_automatic), style = MaterialTheme.typography.bodySmall)
                if (error) Text(stringResource(R.string.game_env_error), color = MaterialTheme.colorScheme.error)
                if (config == null && !error) Text(stringResource(R.string.game_env_loading))
                val current = config
                if (current != null) {
                    val profileName = if (scope.isEmpty()) stringResource(R.string.game_env_shared)
                        else games.firstOrNull { it.first == scope }?.second ?: stringResource(R.string.game_env_profile, scope)
                    Text(stringResource(R.string.game_env_scope), style = MaterialTheme.typography.labelMedium)
                    Box {
                        TextButton(onClick = { scopeMenu = true }, enabled = !busy) { Text(profileName) }
                        DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.game_env_shared)) }, onClick = { scope = ""; scopeMenu = false })
                            val profiles = (games + current.games.keys.filter { id -> games.none { it.first == id } }.map { it to it }).distinctBy { it.first }
                            for ((id, name) in profiles) DropdownMenuItem(text = { Text(name) }, onClick = { scope = id; scopeMenu = false })
                        }
                    }
                    OutlinedTextField(value = appId, onValueChange = { appId = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.game_env_app_id)) }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { scope = appId.toLong().toString(); appId = "" }, enabled = !busy && appId.isNotEmpty() && GameEnvironment.validScope(appId)) {
                        Text(stringResource(R.string.game_env_open))
                    }
                    Text(stringResource(R.string.game_env_overrides), style = MaterialTheme.typography.bodySmall)
                    val entries = GameEnvironment.effective(current, preset, scope).toSortedMap()
                    if (entries.isEmpty()) Text(stringResource(R.string.game_env_empty))
                    for ((name, value) in entries) {
                        HorizontalDivider()
                        Text(name, style = MaterialTheme.typography.titleSmall)
                        Text(value ?: stringResource(R.string.game_env_unset))
                        if (!current.entries(scope).containsKey(name)) Text(stringResource(R.string.game_env_inherited), style = MaterialTheme.typography.labelSmall)
                        Row {
                            TextButton(onClick = { editing = name to (value ?: "") }, enabled = !busy) { Text(stringResource(R.string.game_env_edit)) }
                            if (value != null) TextButton(onClick = { save(current.withEntries(scope, current.entries(scope) + (name to null))) }, enabled = !busy) {
                                Text(stringResource(R.string.game_env_remove))
                            }
                        }
                        if (current.entries(scope).containsKey(name)) TextButton(onClick = { save(current.withEntries(scope, current.entries(scope) - name)) }, enabled = !busy) {
                            Text(stringResource(R.string.game_env_restore))
                        }
                    }
                    Box {
                        TextButton(onClick = { addMenu = true }, enabled = !busy) { Text(stringResource(R.string.game_env_add)) }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, modifier = Modifier.heightIn(max = 280.dp)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.game_env_custom)) }, onClick = { editing = "" to ""; addMenu = false })
                            for (option in variableOptions) DropdownMenuItem(text = { Text(option.name) }, onClick = { editing = option.name to option.value; addMenu = false })
                        }
                    }
                    TextButton(onClick = { save(current.withEntries(scope, emptyMap())) }, enabled = !busy && current.entries(scope).isNotEmpty()) {
                        Text(stringResource(R.string.game_env_reset))
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    )
    editing?.let { (name, value) ->
        VariableDialog(name, value, onDismiss = { editing = null }) { key, content ->
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

@Composable
private fun VariableDialog(initialName: String, initialValue: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var value by remember { mutableStateOf(initialValue) }
    val valid = GameEnvironment.validName(name) && GameEnvironment.validValue(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_env_edit)) },
        confirmButton = { TextButton(onClick = { onSave(name, value) }, enabled = valid) { Text(stringResource(R.string.game_env_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.game_env_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.game_env_name)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true,
                    label = { Text(stringResource(R.string.game_env_value)) }, modifier = Modifier.fillMaxWidth())
                variableOptions.firstOrNull { it.name == name }?.let { Text(stringResource(it.detail), style = MaterialTheme.typography.bodySmall) }
                if (!GameEnvironment.supported(name)) Text(stringResource(R.string.game_env_unused))
                if (!valid && name.isNotEmpty()) Text(stringResource(R.string.game_env_invalid), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
