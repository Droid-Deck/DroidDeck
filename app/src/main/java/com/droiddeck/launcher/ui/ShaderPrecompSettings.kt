package com.droiddeck.launcher.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.ShaderPrecompManager
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog

@Composable
internal fun ShaderPrecompGroup(host: MenuHost, games: List<Library.SteamGame>) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(SessionPrefs.shaderPrecompEnabled(context)) }
    var selectedGames by remember { mutableStateOf(SessionPrefs.shaderPrecompGames(context)) }
    var threads by remember { mutableIntStateOf(SessionPrefs.shaderPrecompThreads(context)) }
    var idleOnly by remember { mutableStateOf(SessionPrefs.shaderPrecompIdleOnly(context)) }
    var maxMemoryMb by remember { mutableIntStateOf(SessionPrefs.shaderPrecompMaxMemoryMb(context)) }
    var skipCompiled by remember { mutableStateOf(SessionPrefs.shaderPrecompSkipCompiled(context)) }
    var recordMissing by remember { mutableStateOf(SessionPrefs.shaderPrecompRecordMissing(context)) }
    var autoDriver by remember { mutableStateOf(SessionPrefs.shaderPrecompAutoDriverRebuild(context)) }
    var showPicker by remember { mutableStateOf(false) }

    val state = ShaderPrecompManager.uiState
    LaunchedEffect(enabled, games) {
        if (enabled) ShaderPrecompManager.refreshAsync(context, games)
    }

    val readyCount = state.entries.count { it.key in selectedGames && it.fozFiles.isNotEmpty() }
    val selectedCacheBytes = state.entries.filter { it.key in selectedGames }.sumOf { it.cacheBytes }
    val progress = state.progress

    SettingsGroup(stringResource(R.string.shader_precomp_group)) {
        ToggleRow(
            host,
            "shader-precomp-enabled",
            stringResource(R.string.shader_precomp_title),
            stringResource(R.string.shader_precomp_hint),
            enabled,
        ) { on ->
            enabled = on
            SessionPrefs.setShaderPrecompEnabled(context, on)
            if (on) ShaderPrecompManager.refreshAsync(context, games)
            else ShaderPrecompManager.stop()
        }
        AnimatedVisibility(
            enabled,
            enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)),
            exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                val gamesHint = if (selectedGames.isEmpty()) {
                    stringResource(R.string.shader_precomp_games_none)
                } else {
                    stringResource(R.string.shader_precomp_games_count, selectedGames.size, readyCount)
                }
                ActionRow(
                    label = stringResource(R.string.shader_precomp_games),
                    hint = gamesHint,
                    button = stringResource(R.string.shader_precomp_select_games),
                    onClick = {
                        ShaderPrecompManager.refreshAsync(context, games)
                        showPicker = true
                    },
                )

                val runHint = when {
                    !state.replayAvailable -> stringResource(R.string.shader_precomp_run_missing_replay)
                    progress.running -> progress.stage ?: stringResource(R.string.common_working)
                    progress.lastError != null -> progress.lastError
                    progress.stage != null -> progress.stage
                    selectedGames.isEmpty() -> stringResource(R.string.shader_precomp_games_none)
                    else -> stringResource(R.string.shader_precomp_run_idle, selectedGames.size)
                }
                SettingsRow(stringResource(R.string.shader_precomp_run_title), runHint) {
                    if (progress.running) {
                        SecondaryButton(stringResource(R.string.shader_precomp_stop_button)) {
                            ShaderPrecompManager.stop()
                        }
                    } else {
                        SecondaryButton(
                            stringResource(R.string.shader_precomp_run_button),
                            enabled = state.replayAvailable && readyCount > 0,
                        ) {
                            ShaderPrecompManager.startCompile(context, games, selectedGames)
                        }
                    }
                }
                if (progress.running) {
                    val fraction = if (progress.pipelinesTotal > 0) {
                        (progress.pipelinesDone.toFloat() / progress.pipelinesTotal.toFloat()).coerceIn(0f, 1f)
                    } else if (progress.gameTotal > 0) {
                        ((progress.gameIndex - 1).coerceAtLeast(0).toFloat() / progress.gameTotal.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    if (fraction != null && progress.pipelinesTotal > 0) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }

                ChoiceRow(
                    host,
                    "shader-precomp-threads",
                    stringResource(R.string.shader_precomp_threads),
                    stringResource(R.string.shader_precomp_threads_hint),
                    listOf(
                        0 to stringResource(R.string.shader_precomp_threads_auto),
                        2 to "2",
                        4 to "4",
                        6 to "6",
                        8 to "8",
                    ),
                    threads,
                ) { v ->
                    threads = v
                    SessionPrefs.setShaderPrecompThreads(context, v)
                }

                ToggleRow(
                    host,
                    "shader-precomp-idle",
                    stringResource(R.string.shader_precomp_idle_only),
                    stringResource(R.string.shader_precomp_idle_only_hint),
                    idleOnly,
                ) { on ->
                    idleOnly = on
                    SessionPrefs.setShaderPrecompIdleOnly(context, on)
                }

                ChoiceRow(
                    host,
                    "shader-precomp-memory",
                    stringResource(R.string.shader_precomp_memory),
                    stringResource(R.string.shader_precomp_memory_hint),
                    listOf(
                        0 to stringResource(R.string.common_auto),
                        512 to "512 MB",
                        1024 to "1 GB",
                        2048 to "2 GB",
                        4096 to "4 GB",
                    ),
                    maxMemoryMb,
                ) { mb ->
                    maxMemoryMb = mb
                    SessionPrefs.setShaderPrecompMaxMemoryMb(context, mb)
                }

                ToggleRow(
                    host,
                    "shader-precomp-skip",
                    stringResource(R.string.shader_precomp_skip_compiled),
                    stringResource(R.string.shader_precomp_skip_compiled_hint),
                    skipCompiled,
                ) { on ->
                    skipCompiled = on
                    SessionPrefs.setShaderPrecompSkipCompiled(context, on)
                }

                ToggleRow(
                    host,
                    "shader-precomp-record",
                    stringResource(R.string.shader_precomp_record_missing),
                    stringResource(R.string.shader_precomp_record_missing_hint),
                    recordMissing,
                ) { on ->
                    recordMissing = on
                    SessionPrefs.setShaderPrecompRecordMissing(context, on)
                }

                ToggleRow(
                    host,
                    "shader-precomp-driver",
                    stringResource(R.string.shader_precomp_auto_driver),
                    stringResource(R.string.shader_precomp_auto_driver_hint),
                    autoDriver,
                ) { on ->
                    autoDriver = on
                    SessionPrefs.setShaderPrecompAutoDriverRebuild(context, on)
                    if (on) {
                        SessionPrefs.setShaderPrecompLastDriver(context, ShaderPrecompManager.activeDriverSignature(context))
                    }
                }

                ClearSelectedCachesRow(
                    enabled = !progress.running && selectedGames.isNotEmpty() && selectedCacheBytes > 0L,
                    selectedCount = selectedGames.size,
                    cacheBytes = selectedCacheBytes,
                ) {
                    ShaderPrecompManager.clearCaches(context, games, selectedGames) { cleared, freed ->
                        val sizeText = Formatter.formatShortFileSize(context, freed)
                        Toast.makeText(
                            context,
                            context.getString(R.string.shader_precomp_cleared_toast, cleared, sizeText),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    if (showPicker) {
        ShaderPrecompGamePickerDialog(
            entries = state.entries,
            initialSelected = selectedGames,
            onSave = { next ->
                selectedGames = next
                SessionPrefs.setShaderPrecompGames(context, next)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ClearSelectedCachesRow(
    enabled: Boolean,
    selectedCount: Int,
    cacheBytes: Long,
    onConfirmClear: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val sizeLabel = Formatter.formatShortFileSize(context, cacheBytes)
    val buttonLabel = stringResource(R.string.shader_precomp_clear_button)
    val confirmTitle = stringResource(R.string.shader_precomp_clear_confirm_title)
    val confirmBody = stringResource(R.string.shader_precomp_clear_confirm_body, selectedCount)
    val cancelLabel = stringResource(R.string.common_cancel)
    var at by remember { mutableStateOf<Rect?>(null) }

    SettingsRow(
        stringResource(R.string.shader_precomp_clear_cache),
        stringResource(R.string.shader_precomp_clear_cache_hint, sizeLabel),
    ) {
        Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
            SecondaryButton(buttonLabel, enabled = enabled) {
                val from = at ?: run { onConfirmClear(); return@SecondaryButton }
                Steps.ask(
                    StepAsk(
                        anchor = from,
                        side = StepSide.Left,
                        accent = colors.error,
                        pillCorner = 12.dp,
                        items = 3,
                        handle = {
                            Text(
                                buttonLabel,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.error,
                                maxLines = 1,
                            )
                        },
                    ) {
                        StepTitle(confirmTitle)
                        Text(
                            confirmBody,
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            modifier = Modifier.stepItem(2),
                        ) {
                            StepChoice(cancelLabel, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                            StepChoice(buttonLabel, danger = true, enabled = open) {
                                Steps.fold()
                                onConfirmClear()
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ShaderPrecompGamePickerDialog(
    entries: List<ShaderPrecompManager.GameEntry>,
    initialSelected: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var picked by remember(initialSelected) { mutableStateOf(initialSelected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shader_precomp_games)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    SecondaryButton(
                        stringResource(R.string.shader_precomp_select_all_ready),
                        compact = true,
                        enabled = entries.any { it.fozFiles.isNotEmpty() },
                    ) {
                        picked = entries.filter { it.fozFiles.isNotEmpty() }.mapTo(LinkedHashSet()) { it.key }
                    }
                    SecondaryButton(
                        stringResource(R.string.shader_precomp_clear_selection),
                        compact = true,
                        enabled = picked.isNotEmpty(),
                    ) {
                        picked = emptySet()
                    }
                }
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.shader_precomp_no_games_installed),
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        entries.forEach { entry ->
                            val checked = entry.key in picked
                            val toggle = {
                                picked = if (checked) picked - entry.key else picked + entry.key
                            }
                            val detail = when (entry.status) {
                                ShaderPrecompManager.GameStatus.COMPILING -> stringResource(R.string.shader_precomp_status_compiling)
                                ShaderPrecompManager.GameStatus.ERROR -> stringResource(
                                    R.string.shader_precomp_status_error,
                                    entry.lastError.orEmpty(),
                                )
                                ShaderPrecompManager.GameStatus.COMPILED -> stringResource(
                                    R.string.shader_precomp_status_compiled,
                                    entry.fozFiles.size,
                                    Formatter.formatShortFileSize(context, entry.cacheBytes),
                                )
                                ShaderPrecompManager.GameStatus.READY -> stringResource(
                                    R.string.shader_precomp_status_ready,
                                    entry.fozFiles.size,
                                )
                                ShaderPrecompManager.GameStatus.NEEDS_CAPTURE -> stringResource(
                                    R.string.shader_precomp_status_needs_capture,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .focusRow(role = Role.Checkbox, checked = checked, onClick = toggle)
                                    .padding(vertical = 4.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(
                                        entry.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        detail,
                                        fontSize = 12.sp,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(picked) }, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
