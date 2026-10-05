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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.DependencyDetector
import com.droiddeck.launcher.session.WinComponents
import com.droiddeck.launcher.session.WinComponents.Support
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Windows components for one added game, Bannerlator's list: what the game's folder suggests first,
// then everything that installs here, then the ones that need a Windows installer (not yet). Each
// one is a switch, downloaded the first time it is turned on for any game; the launch puts the
// picked ones into the game's prefix (droiddeck-wincomponents).

/** Bannerlator's detector names installers; here the DLL-copy twin is the one that installs. */
private fun installable(name: String, all: Map<String, WinComponents.Component>): String {
    val dll = when (name) {
        "oalinst" -> "oalinst_dll"
        else -> "${name}_dll"
    }
    val twin = all[dll]
    return if (twin != null && WinComponents.support(twin, all) == Support.READY) dll else name
}

@Composable
internal fun WinComponentsDialog(appKey: String, gameName: String, gameDir: File?, byPad: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    // null while the catalog loads; empty when it could not be had.
    var catalog by remember { mutableStateOf<Map<String, WinComponents.Component>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var recommended by remember { mutableStateOf(emptyList<DependencyDetector.Recommendation>()) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    var picks by remember { mutableStateOf(WinComponents.picks(context, appKey)) }
    var progress by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showWaiting by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val (entries, found) = withContext(Dispatchers.IO) {
            WinComponents.fetch() to (gameDir?.let { DependencyDetector.detect(it) } ?: emptyList())
        }
        offline = entries == null
        catalog = entries.orEmpty().associateBy { it.name }
        recommended = found
        installed = withContext(Dispatchers.IO) { WinComponents.installedIds(context).toSet() }
    }
    fun setPicks(next: List<String>) {
        picks = next
        coroutine.launch(Dispatchers.IO) { WinComponents.setPicks(context, appKey, next) }
    }
    fun toggle(id: String, on: Boolean) {
        error = null
        if (!on) { setPicks(picks - id); return }
        val all = catalog.orEmpty()
        val c = all[id]
        if (id in installed || c == null) { setPicks(picks + id); return }
        progress = id to -1
        coroutine.launch {
            val problem = withContext(Dispatchers.IO) {
                WinComponents.install(context, c, all) { stage, percent -> progress = stage to percent }
            }
            progress = null
            installed = withContext(Dispatchers.IO) { WinComponents.installedIds(context).toSet() }
            if (problem != null) error = problem else setPicks(picks + id)
        }
    }

    AppDialog(shown, close, "winComponents", wide = true) {
        PadFocus(byPad, firstFocus)
        DialogHeader(gameName, stringResource(R.string.wincomp_title))
        Small(stringResource(R.string.wincomp_applies))
        val all = catalog
        if (all == null) Small(stringResource(R.string.wincomp_loading))
        if (offline) Small(stringResource(R.string.wincomp_offline), error = true)
        var focusGiven = false
        fun focusFirst(): Modifier = if (focusGiven) Modifier else { focusGiven = true; Modifier.focusRequester(firstFocus) }
        @Composable
        fun Item(id: String, reason: String?) {
            val c = all?.get(id)
            val support = c?.let { WinComponents.support(it, all) } ?: if (id in installed) Support.READY else Support.UNSUPPORTED
            val status = when {
                id in installed -> stringResource(R.string.wincomp_downloaded)
                support == Support.NEEDS_INSTALLER -> stringResource(R.string.wincomp_needs_installer)
                support == Support.UNSUPPORTED -> stringResource(R.string.wincomp_unsupported)
                else -> null
            }
            val detail = listOfNotNull(reason, c?.description?.takeIf { it.isNotEmpty() }, status).joinToString(" · ")
            val usable = support == Support.READY
            ComponentRow(
                id, detail, checked = id in picks, enabled = progress == null && (usable || id in picks),
                dim = !usable, modifier = if (usable) focusFirst() else Modifier,
            ) { on -> toggle(id, on) }
        }

        if (all != null && recommended.isNotEmpty()) {
            Section(stringResource(R.string.wincomp_recommended))
            Panel {
                recommended.distinctBy { installable(it.componentName, all) }.forEachIndexed { i, rec ->
                    if (i > 0) Divider()
                    val reason = stringResource(
                        if (rec.kind == DependencyDetector.Kind.BUNDLED) R.string.wincomp_found_bundled else R.string.wincomp_found_shipped, rec.reason,
                    )
                    Item(installable(rec.componentName, all), reason)
                }
            }
        } else if (all != null && gameDir != null) Small(stringResource(R.string.wincomp_no_recommendation))

        val ready = all.orEmpty().values.filter { WinComponents.support(it, all.orEmpty()) == Support.READY }.map { it.name }
        val extra = (installed + picks).filter { all?.containsKey(it) != true }
        val list = (ready + extra).distinct().sortedBy { it.lowercase() }
        if (list.isNotEmpty()) {
            Section(stringResource(R.string.wincomp_all, list.size))
            Panel { list.forEachIndexed { i, id -> if (i > 0) Divider(); Item(id, null) } }
        }
        val waiting = all.orEmpty().values.filter { WinComponents.support(it, all.orEmpty()) == Support.NEEDS_INSTALLER }
            .map { it.name }.sortedBy { it.lowercase() }
        if (waiting.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section(stringResource(R.string.wincomp_installers, waiting.size))
                Spacer(Modifier.weight(1f))
                SecondaryButton(stringResource(if (showWaiting) R.string.wincomp_hide else R.string.wincomp_show), compact = true) { showWaiting = !showWaiting }
            }
            Small(stringResource(R.string.wincomp_installers_note))
            if (showWaiting) Panel { waiting.forEachIndexed { i, id -> if (i > 0) Divider(); Item(id, null) } }
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
private fun Section(title: String) =
    Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Panel(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(Shape14).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line, Shape14), content = content)

@Composable
private fun Divider() =
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).heightIn(min = 1.dp, max = 1.dp).background(LocalPalette.current.line))

@Composable
private fun ComponentRow(name: String, detail: String, checked: Boolean, enabled: Boolean, dim: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).alpha(if (dim) 0.55f else 1f),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
        }
        ToggleSwitch(checked, enabled, name, modifier, onChange)
    }
}
