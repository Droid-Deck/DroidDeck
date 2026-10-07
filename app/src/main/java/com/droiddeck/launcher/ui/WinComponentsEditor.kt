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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalInspectionMode
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.DependencyDetector
import com.droiddeck.launcher.frontend.PrefixInstalledDetector
import com.droiddeck.launcher.frontend.SteamRedists
import com.droiddeck.launcher.session.WinComponentNames
import com.droiddeck.launcher.session.WinComponents
import com.droiddeck.launcher.session.WinComponents.Support
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun WinComponentsDialog(
    appKey: String, gameName: String, gameDir: File?, byPad: Boolean,
    /** compatdata/<id>: what the prefix already has. [steamAppId]: a Steam title, whose appmanifest lists Steam's own redists. */
    compat: File? = null, steamAppId: Int? = null,
    previewComponents: List<WinComponents.Component> = emptyList(),
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val inspecting = LocalInspectionMode.current
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    // null while the catalog loads; empty when it could not be had.
    var catalog by remember { mutableStateOf<Map<String, WinComponents.Component>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var recommended by remember { mutableStateOf(emptyList<DependencyDetector.Recommendation>()) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    // Detector names (oalinst, physx...) already in the prefix from elsewhere.
    var present by remember { mutableStateOf(emptySet<String>()) }
    var picks by remember { mutableStateOf(if (inspecting) emptyList() else WinComponents.picks(context, appKey)) }
    var progress by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The component the error is about, so it shows on its own row and not only at the bottom.
    var failed by remember { mutableStateOf<String?>(null) }
    var showWaiting by remember { mutableStateOf(false) }
    // Done holds focus while the list loads, then the top switch takes it - opened by touch or by
    // pad, so the d-pad always has somewhere to start; LB and RB jump between the sections, since
    // the full list runs past sixty switches.
    val recFocus = remember { FocusRequester() }
    val allFocus = remember { FocusRequester() }
    val waitFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var switchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (inspecting) { catalog = previewComponents.associateBy { it.name }; return@LaunchedEffect }
        val (entries, found) = withContext(Dispatchers.IO) {
            // The folder's own installers first, then what Steam installs with the game.
            val own = gameDir?.let { DependencyDetector.detect(it) } ?: emptyList()
            val steam = steamAppId?.let { SteamRedists.detect(gameDir, it) } ?: emptyList()
            WinComponents.fetch() to (own + steam)
        }
        offline = entries == null
        catalog = entries.orEmpty().associateBy { it.name }
        recommended = found
        present = withContext(Dispatchers.IO) { PrefixInstalledDetector.detect(compat) }
        installed = withContext(Dispatchers.IO) { WinComponents.installedIds(context).toSet() }
    }
    fun setPicks(next: List<String>) {
        picks = next
        if (!inspecting) coroutine.launch(Dispatchers.IO) { WinComponents.setPicks(context, appKey, next) }
    }
    fun toggle(id: String, on: Boolean) {
        if (inspecting) { picks = if (on) picks + id else picks - id; return }
        // Switches stay enabled during a download - a disabled one drops the pad's focus - so a press
        // then is ignored here.
        if (progress != null) return
        error = null
        failed = null
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
            if (problem != null) { error = problem; failed = id } else setPicks(picks + id)
        }
    }

    val all = catalog
    val here = if (all == null) emptySet() else present.map { installable(it, all) }.toSet()
    fun supportOf(id: String): Support =
        all?.get(id)?.let { WinComponents.support(it, all) } ?: if (id in installed) Support.READY else Support.UNSUPPORTED
    val recIds = if (all == null) emptyList() else recommended.distinctBy { installable(it.componentName, all) }
    val ready = all.orEmpty().values.filter { WinComponents.support(it, all.orEmpty()) == Support.READY }.map { it.name }
    val extra = (installed + picks).filter { all?.containsKey(it) != true }
    val list = (ready + extra).distinct().sortedBy { WinComponentNames.of(it).lowercase() }
    val waiting = all.orEmpty().values.filter { WinComponents.support(it, all.orEmpty()) == Support.NEEDS_INSTALLER }
        .map { it.name }.sortedBy { WinComponentNames.of(it).lowercase() }
    val hasRec = all != null && recIds.any { supportOf(installable(it.componentName, all)) == Support.READY }
    val sections = listOfNotNull(recFocus.takeIf { hasRec }, allFocus.takeIf { list.isNotEmpty() },
        waitFocus.takeIf { waiting.isNotEmpty() }, doneFocus)
    var section by remember { mutableStateOf(0) }
    fun jump(step: Int) {
        section = (section + step + sections.size) % sections.size
        runCatching { sections[section].requestFocus() }
    }
    LaunchedEffect(all != null) {
        // The dialog's window starts in touch mode, where a switch cannot hold focus at all.
        inputMode.requestInputMode(InputMode.Keyboard)
        section = 0
        val target = if (all == null) doneFocus else sections.first()
        switchFocused = false
        // It animates in and the list composes over a few frames: ask until the switch has it.
        repeat(60) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
            withFrameNanos { }
            if (switchFocused || (target !== recFocus && target !== allFocus)) return@LaunchedEffect
        }
    }

    AppDialog(shown, close, "winComponents", wide = true, modifier = Modifier.bumpers({ jump(-1) }, { jump(1) })) {
        DialogHeader(gameName, stringResource(R.string.wincomp_title))
        Small(stringResource(R.string.wincomp_applies))
        if (byPad) Small(stringResource(R.string.wincomp_pad_hint))
        if (all == null) Small(stringResource(R.string.wincomp_loading))
        if (offline) Small(stringResource(R.string.wincomp_offline), error = true)
        @Composable
        fun Item(id: String, reason: String?, focus: FocusRequester?) {
            val c = all?.get(id)
            val support = supportOf(id)
            val status = when {
                id == failed && error != null -> stringResource(R.string.wincomp_failed, error.orEmpty())
                id in here && id !in picks -> stringResource(R.string.wincomp_already_here)
                id in installed -> stringResource(R.string.wincomp_downloaded)
                support == Support.NEEDS_INSTALLER -> stringResource(R.string.wincomp_needs_installer)
                support == Support.UNSUPPORTED -> stringResource(R.string.wincomp_unsupported)
                else -> null
            }
            // The catalog key stays in the detail: it is what a log or a bug report names.
            val detail = listOfNotNull(reason, c?.description?.takeIf { it.isNotEmpty() }, status, id).joinToString(" · ")
            val usable = support == Support.READY
            ComponentRow(
                WinComponentNames.of(id), detail, checked = id in picks, enabled = usable || id in picks,
                dim = !usable, modifier = (if (usable && focus != null) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { if (it.isFocused) switchFocused = true },
            ) { on -> toggle(id, on) }
        }
        /** The section's requester goes to its first switch that can take focus. */
        fun firstUsable(ids: List<String>, focus: FocusRequester): (String) -> FocusRequester? {
            val first = ids.firstOrNull { supportOf(it) == Support.READY }
            return { id -> focus.takeIf { id == first } }
        }

        if (all != null && recIds.isNotEmpty()) {
            Section(stringResource(R.string.wincomp_recommended))
            val focusOf = firstUsable(recIds.map { installable(it.componentName, all) }, recFocus)
            Panel {
                recIds.forEachIndexed { i, rec ->
                    if (i > 0) Divider()
                    val reason = stringResource(
                        when (rec.kind) {
                            DependencyDetector.Kind.BUNDLED -> R.string.wincomp_found_bundled
                            DependencyDetector.Kind.SHIPPED -> R.string.wincomp_found_shipped
                            DependencyDetector.Kind.STEAM -> R.string.wincomp_found_steam
                        }, rec.reason,
                    )
                    val id = installable(rec.componentName, all)
                    Item(id, reason, focusOf(id))
                }
            }
        } else if (all != null && gameDir != null) Small(stringResource(R.string.wincomp_no_recommendation))

        if (list.isNotEmpty()) {
            Section(stringResource(R.string.wincomp_all, list.size))
            val focusOf = firstUsable(list, allFocus)
            Panel { list.forEachIndexed { i, id -> if (i > 0) Divider(); Item(id, null, focusOf(id)) } }
        }
        if (waiting.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section(stringResource(R.string.wincomp_installers, waiting.size))
                Spacer(Modifier.weight(1f))
                SecondaryButton(
                    stringResource(if (showWaiting) R.string.wincomp_hide else R.string.wincomp_show), compact = true,
                    modifier = Modifier.focusRequester(waitFocus),
                ) { showWaiting = !showWaiting }
            }
            Small(stringResource(R.string.wincomp_installers_note))
            if (showWaiting) Panel { waiting.forEachIndexed { i, id -> if (i > 0) Divider(); Item(id, null, null) } }
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
            PrimaryButton(stringResource(R.string.game_env_done), enabled = progress == null, modifier = Modifier.focusRequester(doneFocus), onClick = close)
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
