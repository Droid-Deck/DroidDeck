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
import androidx.compose.runtime.key
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
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
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.DependencyDetector
import com.droiddeck.launcher.frontend.PrefixInstalledDetector
import com.droiddeck.launcher.frontend.SteamRedists
import com.droiddeck.launcher.frontend.WinCompSources
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
private fun installable(name: String, all: Map<String, WinComponents.Component>): String = WinComponents.installable(name, all)

/** A store as a list's owner names it in a reason line. */
@Composable
private fun originName(origin: String): String = stringResource(
    when (origin) {
        WinCompSources.Origin.GOG.name -> R.string.wincomp_src_gog
        WinCompSources.Origin.EPIC.name -> R.string.wincomp_src_epic
        WinCompSources.Origin.AMAZON.name -> R.string.wincomp_src_amazon
        else -> R.string.wincomp_src_steam
    },
)

/** Why a component was turned on by itself, from [WinComponents.AutoReason] (args: what the list called it, then the origin's own). */
@Composable
private fun autoReason(r: WinComponents.AutoReason): String = when (r.kind) {
    WinCompSources.Origin.STEAM.name -> stringResource(R.string.wincomp_auto_steam, r.args.getOrElse(1) { "" }, r.args.getOrElse(2) { "" }, r.args.getOrElse(3) { "" })
    WinCompSources.Origin.GOG.name -> stringResource(R.string.wincomp_auto_gog)
    WinCompSources.Origin.EPIC.name -> stringResource(R.string.wincomp_auto_epic)
    WinCompSources.Origin.AMAZON.name -> stringResource(R.string.wincomp_auto_amazon)
    else -> ""
}

/** The reason line of a finding that is only recommended. */
@Composable
private fun findingReason(f: WinCompSources.Finding): String = when (f.origin) {
    WinCompSources.Origin.FOLDER -> stringResource(
        if (f.args.getOrNull(1) == DependencyDetector.Kind.SHIPPED.name) R.string.wincomp_found_shipped else R.string.wincomp_found_bundled,
        f.args.getOrElse(0) { f.original },
    )
    WinCompSources.Origin.STEAM_NAME -> stringResource(R.string.wincomp_steam_by_name, f.args.getOrElse(0) { f.original }, f.args.getOrElse(2) { "" })
    else -> autoReason(WinComponents.AutoReason(f.origin.name, listOf(f.original) + f.args))
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun WinComponentsDialog(
    appKey: String, gameName: String, gameDir: File?, byPad: Boolean,
    /** compatdata/<id>: what the prefix already has. [steamAppId]: a Steam title, whose appmanifest lists Steam's own redists. */
    compat: File? = null, steamAppId: Int? = null,
    /** The game as the Games tab has it: its source decides whose list is trusted ([WinCompSources]). */
    game: com.droiddeck.launcher.frontend.Library.SteamGame? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val shown = rememberShown(onClose)
    val close = { shown.targetState = false }
    // null while the catalog loads; empty when it could not be had.
    var catalog by remember { mutableStateOf<Map<String, WinComponents.Component>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var findings by remember { mutableStateOf(emptyList<WinCompSources.Finding>()) }
    // Whether the game's list has been worked out at least once (kept from an earlier time counts).
    var findingsKnown by remember { mutableStateOf(game == null) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    // Detector names (oalinst, physx...) already in the prefix from elsewhere.
    var present by remember { mutableStateOf(emptySet<String>()) }
    var selection by remember { mutableStateOf(WinComponents.selection(context, appKey)) }
    val picks = selection.effective
    // The row being worked on: its id, the latest progress (a dependency's while that installs
    // first), and whether it is turning off rather than on. The row greys out and shows the bar
    // the moment the switch is pressed, and only that row; the rest of the page stays as it is.
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<WinComponents.Progress?>(null) }
    var reverting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The component the error is about, so it shows on its own row and not only at the bottom.
    var failed by remember { mutableStateOf<String?>(null) }
    var showWaiting by remember { mutableStateOf(false) }
    // Done holds focus while the list loads, then the top switch takes it - opened by touch or by
    // pad, so the d-pad always has somewhere to start; LB and RB jump between the sections, since
    // the full list runs past sixty switches.
    val recFocus = remember { FocusRequester() }
    val recFocusAfterAuto = remember { FocusRequester() }
    val activeFocus = remember { FocusRequester() }
    val allFocus = remember { FocusRequester() }
    val waitFocus = remember { FocusRequester() }
    val doneFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    var switchFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (entries, found) = withContext(Dispatchers.IO) {
            val list = WinComponents.fetch(context)
            // The source's list first (and what it turns on by itself, already installed), then the folder scan.
            if (game != null) {
                // The list kept from last time, at once; it is worked out again (and its components
                // queued) on the background worker, which this page then follows.
                com.droiddeck.launcher.frontend.AutoComponents.queueGame(context, game, asked = true)
                val kept = com.droiddeck.launcher.frontend.AutoComponents.cachedFindings(context, appKey)
                if (kept != null) findingsKnown = true
                list to kept.orEmpty()
            } else {
                val own = gameDir?.let { DependencyDetector.detect(it) } ?: emptyList()
                val steam = steamAppId?.let { SteamRedists.detect(gameDir, it) } ?: emptyList()
                list to (own + steam).map { WinCompSources.Finding(it.componentName, it.reason, WinCompSources.Origin.FOLDER, false, listOf(it.reason, it.kind.name)) }
            }
        }
        offline = entries == null
        catalog = entries.orEmpty().associateBy { it.name }
        findings = found
        selection = withContext(Dispatchers.IO) { WinComponents.selection(context, appKey) }
        present = withContext(Dispatchers.IO) { PrefixInstalledDetector.detect(compat) }
        installed = withContext(Dispatchers.IO) { WinComponents.installedIds(context).toSet() }
    }
    /** The user's own switch for [id]: kept apart from what was turned on by itself, and over it. */
    // Automatic picks still downloading show on their rows; when one lands the page reads the picks again.
    val autoDownloading by com.droiddeck.launcher.frontend.AutoComponents.downloading.collectAsState()
    val autoChanges by com.droiddeck.launcher.frontend.AutoComponents.changes.collectAsState()
    val pendingGames by com.droiddeck.launcher.frontend.AutoComponents.pendingGames.collectAsState()
    val pendingComponents by com.droiddeck.launcher.frontend.AutoComponents.pendingComponents.collectAsState()
    LaunchedEffect(autoChanges) {
        if (game != null) withContext(Dispatchers.IO) { com.droiddeck.launcher.frontend.AutoComponents.cachedFindings(context, appKey) }
            ?.let { findings = it; if (appKey !in pendingGames) findingsKnown = true }
        selection = withContext(Dispatchers.IO) { WinComponents.selection(context, appKey) }
        installed = withContext(Dispatchers.IO) { WinComponents.installedIds(context).toSet() }
    }
    fun setUser(id: String, on: Boolean) {
        selection = WinComponents.Selection(selection.auto, selection.user + (id to on))
        coroutine.launch(Dispatchers.IO) { WinComponents.setUser(context, appKey, id, on) }
    }
    fun toggle(id: String, on: Boolean) {
        // Switches stay enabled during a download - a disabled one drops the pad's focus - so a press
        // then is ignored here; the busy row is greyed instead.
        if (busyId != null) return
        error = null
        failed = null
        if (!on) {
            // Turning off is a saved pick, quick, but the row greys out until it is written so the
            // press is seen to land; the files leave the prefix at the game's next launch.
            busyId = id; reverting = true; progress = null
            selection = WinComponents.Selection(selection.auto, selection.user + (id to false))
            coroutine.launch {
                withContext(Dispatchers.IO) { WinComponents.setUser(context, appKey, id, false) }
                busyId = null; reverting = false
            }
            return
        }
        val all = catalog.orEmpty()
        val c = all[id]
        if (id in installed || c == null) { setUser(id, true); return }
        busyId = id; reverting = false
        progress = WinComponents.Progress(id, "", WinComponents.Phase.DOWNLOAD, -1)
        coroutine.launch {
            val problem = withContext(Dispatchers.IO) {
                WinComponents.install(context, c, all) { p -> progress = p }
            }
            progress = null
            installed = withContext(Dispatchers.IO) { WinComponents.installedIds(context).toSet() }
            if (problem != null) { error = problem; failed = id } else setUser(id, true)
            busyId = null
        }
    }

    val all = catalog
    // The first data is in: rows that show up from now on ease in.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(all != null && findingsKnown) { if (all != null && findingsKnown) { withFrameNanos { }; arrived = true } }
    val here = if (all == null) emptySet() else present.map { installable(it, all) }.toSet()
    fun supportOf(id: String): Support =
        all?.get(id)?.let { WinComponents.support(it, all) } ?: if (id in installed) Support.READY else Support.UNSUPPORTED
    // Turned on by itself for this game, each with its reason; then what is only recommended (the
    // folder scan, a name match, an auto pick that cannot install here); then what the lists name
    // that is no component here, by its own name.
    // Still being worked out on the background worker: the page says so instead of "nothing found".
    val checking = game != null && (appKey in pendingGames || !findingsKnown)
    // Automatic picks on their way (queued or downloading) sit in their section already, with
    // their progress, and settle there when they land.
    val turningOn = if (all == null) emptyMap() else findings.filter { it.auto && it.component != null }
        .associateBy { installable(it.component!!, all) }
        .filterKeys { it !in selection.auto && selection.user[it] != false && (it in pendingComponents || it in autoDownloading) }
    val autoIds = selection.auto.keys.toList() + turningOn.keys
    val recIds = if (all == null) emptyList() else findings.filter { it.component != null }
        .filter { installable(it.component!!, all).let { id -> id !in selection.auto && id !in turningOn } }
        .distinctBy { installable(it.component!!, all) }
    val suggestions = findings.filter { it.component == null }.distinctBy { it.origin to it.original }
    val ready = all.orEmpty().values.filter { WinComponents.support(it, all.orEmpty()) == Support.READY }.map { it.name }
    val extra = (installed + picks).filter { all?.containsKey(it) != true }
    // What is turned on for this game gets its own section under the recommendations, which stay
    // where they are; the rest of the list is everything else, so a component turned off goes back
    // to its place in the alphabet. A component is listed once: the recommendations are left out
    // of the full list too.
    val recSet = recIds.map { installable(it.component!!, all.orEmpty()) }.toSet() + autoIds
    val active = picks.filter { it !in recSet && (all?.containsKey(it) == true || it in installed) }.distinct()
        .sortedBy { WinComponentNames.of(it).lowercase() }
    val list = (ready + extra).distinct().filter { it !in active && it !in recSet }.sortedBy { WinComponentNames.of(it).lowercase() }
    val waiting = all.orEmpty().values.filter { WinComponents.support(it, all.orEmpty()) == Support.NEEDS_INSTALLER }
        .map { it.name }.sortedBy { WinComponentNames.of(it).lowercase() }
    val hasRec = all != null && (autoIds.isNotEmpty() || recIds.any { supportOf(installable(it.component!!, all)) == Support.READY })
    val sections = listOfNotNull(recFocus.takeIf { hasRec }, activeFocus.takeIf { active.isNotEmpty() },
        allFocus.takeIf { list.isNotEmpty() }, waitFocus.takeIf { waiting.isNotEmpty() }, doneFocus)
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
            if (switchFocused || (target !== recFocus && target !== activeFocus && target !== allFocus)) return@LaunchedEffect
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
            val autoProgress = autoDownloading[id]
            val busy = id == busyId || autoProgress != null
            val shownProgress = if (id == busyId) progress else autoProgress
            val caption = when {
                !busy -> null
                id == busyId && reverting -> stringResource(R.string.wincomp_reverting)
                else -> shownProgress?.let { p ->
                    val stage = if (p.component != id && p.stage.isNotEmpty()) "${WinComponentNames.of(p.component)}: ${p.stage}" else p.stage
                    val text = when (p.phase) {
                        WinComponents.Phase.DOWNLOAD -> stringResource(R.string.wincomp_downloading, stage)
                        WinComponents.Phase.INSTALL -> stringResource(R.string.wincomp_installing, stage)
                    }
                    if (p.percent >= 0) "$text · ${p.percent}%" else text
                }
            }
            ComponentRow(
                WinComponentNames.of(id), detail, checked = id in picks, enabled = usable || id in picks,
                dim = !usable || busy, busy = busy, caption = caption,
                percent = if (busy && !reverting) shownProgress?.percent ?: -1 else -1,
                modifier = (if (usable && focus != null) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { if (it.isFocused) switchFocused = true },
            ) { on -> toggle(id, on) }
        }
        /** The section's requester goes to its first switch that can take focus. */
        fun firstUsable(ids: List<String>, focus: FocusRequester): (String) -> FocusRequester? {
            val first = ids.firstOrNull { supportOf(it) == Support.READY }
            return { id -> focus.takeIf { id == first } }
        }

        if (checking) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.padding(start = 2.dp).size(12.dp), strokeWidth = 1.5.dp)
            Small(stringResource(R.string.wincomp_checking))
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = all != null && autoIds.isNotEmpty(),
            enter = androidx.compose.animation.fadeIn(Motion.tw(260)) + androidx.compose.animation.expandVertically(Motion.tw(260)),
            exit = androidx.compose.animation.fadeOut(Motion.tw(180)) + androidx.compose.animation.shrinkVertically(Motion.tw(200)),
            label = "autoSection",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section(stringResource(R.string.wincomp_auto_section))
                if (turningOn.isNotEmpty()) Small(pluralStringResource(R.plurals.wincomp_turning_on, turningOn.size, turningOn.size))
                val focusOf = firstUsable(autoIds, recFocus)
                Panel {
                    autoIds.forEachIndexed { i, id ->
                        key(id) {
                            Arrive(arrived) {
                                Column {
                                    if (i > 0) Divider()
                                    val reason = selection.auto[id] ?: turningOn[id]?.let { f -> WinComponents.AutoReason(f.origin.name, listOf(f.original) + f.args) }
                                    Item(id, reason?.let { autoReason(it) }, focusOf(id))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (all != null && recIds.isNotEmpty()) {
            Section(stringResource(R.string.wincomp_recommended))
            val focusOf = firstUsable(recIds.map { installable(it.component!!, all) }, if (autoIds.isEmpty()) recFocus else recFocusAfterAuto)
            Panel {
                recIds.forEachIndexed { i, rec ->
                    if (i > 0) Divider()
                    val id = installable(rec.component!!, all)
                    Item(id, findingReason(rec), focusOf(id))
                }
            }
        } else if (all != null && gameDir != null && autoIds.isEmpty() && !checking) Small(stringResource(R.string.wincomp_no_recommendation))
        if (suggestions.isNotEmpty()) {
            Section(stringResource(R.string.wincomp_suggestions))
            Panel {
                suggestions.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(f.original, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                        Text(stringResource(R.string.wincomp_suggestion_reason, originName(f.origin.name)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (active.isNotEmpty()) {
            Section(stringResource(R.string.wincomp_active, active.size))
            val focusOf = firstUsable(active, activeFocus)
            Panel { active.forEachIndexed { i, id -> if (i > 0) Divider(); Item(id, null, focusOf(id)) } }
        }
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

        error?.let { Small(it, error = true) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Small(stringResource(R.string.wincomp_next_launch))
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.game_env_done), enabled = busyId == null, modifier = Modifier.focusRequester(doneFocus), onClick = close)
        }
    }
}

/** Once the page has its first data ([arrived]), a row that shows up eases in; the first ones are simply there. */
@Composable
private fun Arrive(arrived: Boolean, content: @Composable () -> Unit) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(!arrived) }.apply { targetState = true }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = state,
        enter = androidx.compose.animation.fadeIn(Motion.tw(240)) + androidx.compose.animation.expandVertically(Motion.tw(240)) +
            androidx.compose.animation.slideInVertically(Motion.tw(240)) { -it / 4 },
        exit = androidx.compose.animation.fadeOut(Motion.tw(160)),
        label = "arrive",
    ) { content() }
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

/**
 * One component: name, detail and its switch; while [busy] the row is greyed and a thin bar runs
 * across it with [caption] above (the download, then the install, each 0..100 as [percent], or
 * indeterminate at -1). The switch stays enabled while busy so a pad's focus does not drop; the
 * press is ignored by the caller instead.
 */
@Composable
private fun ComponentRow(
    name: String, detail: String, checked: Boolean, enabled: Boolean, dim: Boolean, busy: Boolean = false,
    caption: String? = null, percent: Int = -1, modifier: Modifier, onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().alpha(if (dim) 0.55f else 1f),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                if (detail.isNotEmpty()) Text(detail, fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
            }
            ToggleSwitch(checked, enabled, name, modifier.alpha(if (busy) 0.5f else 1f), onChange)
        }
        if (busy) {
            if (!caption.isNullOrEmpty()) Text(caption, fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            val bar = Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 4.dp, max = 4.dp)
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = bar)
            else LinearProgressIndicator(modifier = bar)
        }
    }
}
