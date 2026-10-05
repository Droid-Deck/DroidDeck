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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.session.WinComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Windows components for one added game: each one a switch, downloaded the first time it is
// turned on for any game. The launch puts them into the game's prefix (droiddeck-wincomponents).

@Composable
fun WinComponentsRow(appId: Long, gameName: String) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var byPad by remember { mutableStateOf(false) }
    var picks by remember(appId) { mutableStateOf(WinComponents.picks(context, appId)) }
    val inputMode = LocalInputModeManager.current
    val hint = if (picks.isEmpty()) stringResource(R.string.wincomp_none) else picks.joinToString(", ")
    SettingsRow(stringResource(R.string.wincomp_title), hint) {
        SecondaryButton(stringResource(R.string.wincomp_edit)) {
            byPad = inputMode.inputMode == InputMode.Keyboard
            open = true
        }
    }
    if (open) WinComponentsEditor(appId, gameName, byPad) {
        open = false
        picks = WinComponents.picks(context, appId)
    }
}

@Composable
private fun WinComponentsEditor(appId: Long, gameName: String, byPad: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    // null while the catalog loads; empty when it could not be had.
    var catalog by remember { mutableStateOf<List<DesktopCatalog.Entry>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(emptyMap<String, String>()) }
    var picks by remember { mutableStateOf(WinComponents.picks(context, appId)) }
    var progress by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }

    fun refreshInstalled(entries: List<DesktopCatalog.Entry>) {
        installed = (entries.map { it.id } + picks).distinct()
            .mapNotNull { id -> WinComponents.installed(context, id)?.let { id to it } }.toMap()
    }
    LaunchedEffect(Unit) {
        val entries = withContext(Dispatchers.IO) { WinComponents.fetch() }
        offline = entries == null
        catalog = entries.orEmpty()
        withContext(Dispatchers.IO) { refreshInstalled(entries.orEmpty()) }
    }
    fun setPicks(next: List<String>) {
        picks = next
        coroutine.launch(Dispatchers.IO) { WinComponents.setPicks(context, appId, next) }
    }
    fun toggle(entry: DesktopCatalog.Entry?, id: String, on: Boolean) {
        error = null
        if (!on) { setPicks(picks - id); return }
        if (installed.containsKey(id) || entry == null) { setPicks(picks + id); return }
        progress = entry.name to -1
        coroutine.launch {
            val problem = withContext(Dispatchers.IO) {
                WinComponents.install(context, entry) { stage, percent -> progress = stage to percent }
            }
            progress = null
            if (problem != null) { error = problem; return@launch }
            withContext(Dispatchers.IO) { refreshInstalled(catalog.orEmpty()) }
            setPicks(picks + id)
        }
    }

    AppDialog(shown, close, "winComponents", wide = false) {
        PadFocus(byPad, firstFocus)
        DialogHeader(gameName, stringResource(R.string.wincomp_title))
        Small(stringResource(R.string.wincomp_applies))
        val entries = catalog
        if (entries == null) Small(stringResource(R.string.wincomp_loading))
        if (offline) Small(stringResource(R.string.wincomp_offline), error = true)
        // Offline, the ones already downloaded can still be switched; the catalog adds the rest.
        val rows = entries.orEmpty().map { it.id to it } +
            installed.keys.filter { id -> entries.orEmpty().none { it.id == id } }.map { it to null }
        if (rows.isNotEmpty()) Column(
            Modifier.fillMaxWidth().clip(Shape14).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line, Shape14),
        ) {
            rows.forEachIndexed { i, (id, entry) ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(min = 1.dp, max = 1.dp).background(LocalPalette.current.line))
                val have = installed[id]
                val status = when {
                    have != null -> stringResource(R.string.wincomp_downloaded)
                    entry != null -> stringResource(R.string.wincomp_download_size, FileUtils.sizeToString(entry.size))
                    else -> null
                }
                ComponentRow(
                    entry?.name ?: id, listOfNotNull(entry?.notes?.takeIf { it.isNotEmpty() }, status).joinToString(" · "),
                    checked = id in picks, enabled = progress == null,
                    modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                ) { on -> toggle(entry, id, on) }
            }
        }
        progress?.let { (stage, percent) ->
            Small(if (percent >= 0) "$stage · $percent%" else stage)
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { Small(it, error = true) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Small(stringResource(R.string.wincomp_next_launch))
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.game_env_done), enabled = progress == null, onClick = close)
        }
    }
}

@Composable
private fun ComponentRow(name: String, detail: String, checked: Boolean, enabled: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
        }
        ToggleSwitch(checked, enabled, name, modifier, onChange)
    }
}
