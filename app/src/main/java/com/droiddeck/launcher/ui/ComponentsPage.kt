package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.LocalContext
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.ComponentsManager.CatalogItem
import com.droiddeck.launcher.session.ComponentsManager.Snapshot
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.GameEnvironmentStore
import com.droiddeck.launcher.session.GameProfileManager
import com.droiddeck.launcher.session.ProtonDefault
import com.droiddeck.launcher.session.WinComponents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** One installed row: a Proton's original bundle (one per Proton build) or a stored package. */
private class InstalledItem(
    val name: String, val detail: String, val tag: String,
    val selected: Boolean, val removable: Boolean,
    /** Stored package file, or null for an original. */
    val file: String?,
    /** The Proton build an original belongs to. */
    val protonVersion: String?,
)

private data class ProfileEditorState(
    val environment: GameEnvironment.Config,
    val protonChoice: ProtonDefault.GameChoice?,
    val protonId: String?,
    val components: Map<String, String>,
)

private val GOLD = Color(0xFFF2C66D)
private val ROW_SHAPE = RoundedCornerShape(10.dp)

/**
 * FEX, DXVK and VKD3D-Proton per Proton, as a full page opened from the rail's "Components" entry.
 * A title row with the Nightlies refresh, then the Proton and the component (LB / RB turn it), what
 * is in use, and the installed packages beside the ones on the Nightlies - stacked when the page is
 * narrow. Tapping an installed row swaps it in; the refresh button is the only thing that goes online.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ComponentsPage(
    snapshot: Snapshot?,
    catalog: List<CatalogItem>,
    catalogAt: Long,
    protonId: String?,
    comp: String,
    checking: Boolean,
    busy: String?,
    downloads: Map<String, Int>,
    onProton: (String) -> Unit,
    onComp: (String) -> Unit,
    onSwap: (String) -> Unit,
    onRestore: (String) -> Unit,
    onCancelQueued: () -> Unit,
    onDeletePackage: (String) -> Unit,
    onDeleteOriginal: (String) -> Unit,
    onDownload: (CatalogItem) -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    games: List<Library.SteamGame> = emptyList(),
    generalFexPreset: String = "",
    onGeneralFexPreset: (String) -> Unit = {},
    requestInitialFocus: Boolean = true,
    /** The GPU drivers tab ([GPU_TAB]): what it shows and does. */
    gpu: GpuDriversState = GpuDriversState(),
    gpuActions: GpuDriversActions = GpuDriversActions(),
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    BackHandler(onBack = onBack)
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    var about by remember { mutableStateOf(false) }
    var protonMenu by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    var selectedGameKey by remember { mutableStateOf<String?>(null) }
    var fexMenu by remember { mutableStateOf(false) }
    var gameProtonMenu by remember { mutableStateOf(false) }
    var gameProtonId by remember { mutableStateOf<String?>(null) }
    var gameProtonChoice by remember { mutableStateOf<ProtonDefault.GameChoice?>(null) }
    var gameComponents by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var gameComponentMenu by remember { mutableStateOf<String?>(null) }
    var environmentEditor by remember { mutableStateOf(false) }
    var winComponentsOpen by remember { mutableStateOf<Boolean?>(null) }
    var winComponentsRevision by remember { mutableStateOf(0) }
    var environment by remember { mutableStateOf<GameEnvironment.Config?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var profileLoadGeneration by remember { mutableStateOf(0L) }
    var environmentBusy by remember { mutableStateOf(false) }
    var environmentError by remember { mutableStateOf(false) }
    var profileNotice by remember { mutableStateOf<String?>(null) }
    var confirmVerb by remember { mutableStateOf(R.string.comp_swap) }
    val ctx = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val selectedGame = games.firstOrNull { it.profileKey == selectedGameKey }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { GameEnvironmentStore.read(ctx) } }
            .onSuccess { environment = it }
            .onFailure { environmentError = true }
    }
    LaunchedEffect(games, selectedGameKey) {
        if (selectedGameKey != null && games.none { it.profileKey == selectedGameKey }) selectedGameKey = null
    }
    LaunchedEffect(selectedGame?.profileKey, snapshot) {
        profileLoadGeneration++
        val generation = profileLoadGeneration
        val game = selectedGame
        profileNotice = null
        environmentError = false
        gameProtonChoice = null
        gameProtonId = null
        gameComponents = emptyMap()
        gameProtonMenu = false
        gameComponentMenu = null
        fexMenu = false
        if (game == null) {
            profileLoading = false
            return@LaunchedEffect
        }
        profileLoading = true
        try {
            val (choice, id, components) = withContext(Dispatchers.IO) {
                val choice = ProtonDefault.gameChoice(ctx, game.profileKey)
                Triple(
                    choice,
                    ProtonDefault.gameSelectedId(ctx, game.profileKey, snapshot?.protons.orEmpty().map { it.proton }),
                    ComponentsManager.gameComponents(ctx, game.profileKey),
                )
            }
            if (profileLoadGeneration == generation && selectedGameKey == game.profileKey) {
                gameProtonChoice = choice
                gameProtonId = id
                gameComponents = components
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (profileLoadGeneration == generation && selectedGameKey == game.profileKey) {
                environmentError = true
                profileNotice = ctx.getString(
                    R.string.comp_game_load_failed,
                    game.name,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        } finally {
            if (profileLoadGeneration == generation) profileLoading = false
        }
    }
    fun setGameFexPreset(preset: String?) {
        val game = selectedGame ?: return
        val current = environment ?: return
        val next = GameEnvironment.withGameFexPreset(current, game.profileKey, preset)
        environmentBusy = true
        profileNotice = null
        coroutine.launch {
            runCatching { withContext(Dispatchers.IO) { GameEnvironmentStore.save(ctx, next) } }
                .onSuccess {
                    environment = next
                    if (selectedGameKey == game.profileKey) {
                        environmentError = false
                        profileNotice = ctx.getString(R.string.comp_game_saved_next, game.name)
                    }
                }
                .onFailure {
                    if (selectedGameKey == game.profileKey) {
                        environmentError = true
                        profileNotice = ctx.getString(R.string.comp_game_save_failed, it.message ?: it.javaClass.simpleName)
                    }
                }
            environmentBusy = false
        }
    }
    fun setGameProton(id: String?) {
        val game = selectedGame ?: return
        val proton = id?.let { wanted -> snapshot?.protons?.firstOrNull { it.proton.id == wanted }?.proton }
        environmentBusy = true
        profileNotice = null
        coroutine.launch {
            runCatching { withContext(Dispatchers.IO) { ProtonDefault.requestGame(ctx, game.profileKey, proton) } }
                .onSuccess { outcome ->
                    if (selectedGameKey == game.profileKey) {
                        gameProtonChoice = proton?.let { ProtonDefault.GameChoice(it.valve, it.dir.name) }
                        gameProtonId = id
                        environmentError = false
                        profileNotice = ctx.getString(
                            when (outcome) {
                                ProtonDefault.Outcome.LIVE -> R.string.comp_game_proton_live
                                ProtonDefault.Outcome.NEXT_START -> R.string.comp_game_proton_next
                                ProtonDefault.Outcome.NOT_RUNNABLE -> R.string.comp_game_proton_not_runnable
                            },
                            game.name,
                        )
                    }
                }
                .onFailure {
                    if (selectedGameKey == game.profileKey) {
                        environmentError = true
                        profileNotice = ctx.getString(R.string.comp_game_save_failed, it.message ?: it.javaClass.simpleName)
                    }
                }
            environmentBusy = false
        }
    }
    fun setGameComponent(comp: String, file: String?) {
        val game = selectedGame ?: return
        environmentBusy = true
        profileNotice = null
        coroutine.launch {
            runCatching { withContext(Dispatchers.IO) { ComponentsManager.setGameComponent(ctx, game.profileKey, comp, file) } }
                .onSuccess {
                    val components = ComponentsManager.gameComponents(ctx, game.profileKey)
                    if (selectedGameKey == game.profileKey) {
                        gameComponents = components
                        environmentError = false
                        profileNotice = ctx.getString(R.string.comp_game_saved_next, game.name)
                    }
                }
                .onFailure {
                    if (selectedGameKey == game.profileKey) {
                        environmentError = true
                        profileNotice = ctx.getString(R.string.comp_game_save_failed, it.message ?: it.javaClass.simpleName)
                    }
                }
            environmentBusy = false
        }
    }
    fun resetGameProfile() {
        val game = selectedGame ?: return
        environmentBusy = true
        profileNotice = null
        coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resetFailure = runCatching { GameProfileManager.reset(ctx, game.profileKey) }.exceptionOrNull()
                    val config = GameEnvironmentStore.read(ctx)
                    val choice = ProtonDefault.gameChoice(ctx, game.profileKey)
                    val id = ProtonDefault.gameSelectedId(
                        ctx,
                        game.profileKey,
                        snapshot?.protons.orEmpty().map { it.proton },
                    )
                    val components = ComponentsManager.gameComponents(ctx, game.profileKey)
                    ProfileEditorState(config, choice, id, components) to resetFailure
                }
            }.onSuccess { (state, resetFailure) ->
                if (selectedGameKey == game.profileKey) {
                    environment = state.environment
                    gameProtonChoice = state.protonChoice
                    gameProtonId = state.protonId
                    gameComponents = state.components
                    winComponentsRevision++
                    environmentError = resetFailure != null
                    profileNotice = if (resetFailure == null) {
                        ctx.getString(R.string.comp_game_reset_done, game.name)
                    } else {
                        ctx.getString(
                            R.string.comp_game_reset_failed,
                            resetFailure.message ?: resetFailure.javaClass.simpleName,
                        )
                    }
                }
            }.onFailure {
                if (selectedGameKey == game.profileKey) {
                    environmentError = true
                    profileNotice = ctx.getString(R.string.comp_game_reset_failed, it.message ?: it.javaClass.simpleName)
                }
            }
            environmentBusy = false
        }
    }
    fun ask(title: String, body: String, verb: Int, action: () -> Unit) { confirmTitle = title; confirmVerb = verb; confirm = body to action }
    // The GPU drivers tab's Advanced pages: one driver list on its own, full page.
    var driverPage by remember { mutableStateOf<String?>(null) }
    val gpuTab = comp == GPU_TAB
    when (driverPage) {
        "rt" -> {
            DriverPage(
                title = stringResource(R.string.comp_runtime_driver), hint = stringResource(R.string.comp_runtime_driver_hint),
                rows = gpu.linuxRows, selected = gpu.linuxSelected, downloads = gpu.linuxDownloads,
                status = gpu.releaseStatus, checking = gpu.checking, importLabel = stringResource(R.string.comp_import_turnip), canRestore = false,
                onSelect = gpuActions.onSelectLinux, onDelete = gpuActions.onRemoveLinux, onRefresh = gpuActions.onRefresh,
                onDownload = gpuActions.onDownloadDriver, onImport = gpuActions.onImportLinux, onRestore = {}, onBack = { driverPage = null },
            )
            return
        }
        "panel" -> {
            DriverPage(
                title = stringResource(R.string.comp_display_driver), hint = stringResource(R.string.comp_display_driver_hint),
                rows = gpu.androidRows, selected = gpu.androidSelected, downloads = gpu.androidDownloads,
                status = gpu.releaseStatus, checking = gpu.checking, importLabel = stringResource(R.string.comp_import_adrenotools),
                canRestore = gpu.canRestoreBundled,
                onSelect = gpuActions.onSelectAndroid, onDelete = gpuActions.onRemoveAndroid, onRefresh = gpuActions.onRefresh,
                onDownload = gpuActions.onDownloadDriver, onImport = gpuActions.onImportAndroid, onRestore = gpuActions.onRestoreBundled,
                onBack = { driverPage = null },
            )
            return
        }
    }

    val views = snapshot?.protons ?: emptyList()
    val view = views.firstOrNull { it.proton.id == protonId } ?: views.firstOrNull()
    val label = ComponentsManager.LABEL[comp] ?: comp
    val checked = if (catalogAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(catalogAt * 1000)) else stringResource(R.string.comp_never)
    val nightlies = if (gpuTab) gpu.releaseStatus else if (busy != null) "$busy…" else stringResource(R.string.comp_nightlies_checked, checked)

    Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
        // ---- title, and the one thing that goes online -----------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.comp_title), fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                maxLines = 1,
            )
            // The status gives way to the title, not the other way round.
            if (!narrow) Text(
                nightlies, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f),
            ) else Spacer(Modifier.weight(1f))
            if (selectedGame == null && !gpuTab) ToolIcon(Icons.Outlined.Info, stringResource(R.string.comp_about)) { about = true }
            if (selectedGame == null && gpuTab) ToolIcon(Icons.Outlined.Refresh, stringResource(R.string.comp_check_drivers), busy = gpu.checking, enabled = !gpu.checking && gpu.busy == null, onClick = gpuActions.onRefresh)
            else if (selectedGame == null) ToolIcon(Icons.Outlined.Refresh, stringResource(R.string.comp_check_nightlies), busy = checking, enabled = !checking, onClick = onRefresh)
        }
        if (narrow) Text(nightlies, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

        if (games.isNotEmpty()) {
            SettingsGroup(stringResource(R.string.comp_profile_configure)) {
                SettingsRow(
                    stringResource(R.string.comp_profile),
                    if (selectedGame == null) {
                        stringResource(R.string.comp_scope_general_detail)
                    } else {
                        stringResource(R.string.comp_profile_game_detail, selectedGame.name)
                    },
                ) {
                    Box {
                        ValueChip(
                            selectedGame?.name ?: stringResource(R.string.comp_scope_general),
                            scopeMenu,
                            modifier = Modifier.widthIn(max = 320.dp).heightIn(min = 44.dp),
                        ) { scopeMenu = !scopeMenu }
                        AnchoredMenu(
                            scopeMenu,
                            onDismiss = { scopeMenu = false },
                            title = stringResource(R.string.comp_profile_configure),
                        ) { first ->
                            MenuItem(
                                stringResource(R.string.comp_scope_general),
                                checked = selectedGame == null,
                                detail = stringResource(R.string.comp_scope_general_detail),
                                focusRequester = first,
                            ) {
                                selectedGameKey = null
                                scopeMenu = false
                            }
                            games.sortedBy { it.name.lowercase() }.forEach { game ->
                                MenuItem(game.name, checked = selectedGameKey == game.profileKey) {
                                    selectedGameKey = game.profileKey
                                    scopeMenu = false
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- which Proton, which component ------------------------------------------------------
        val comps = ComponentsManager.COMPONENTS
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (selectedGame == null && view != null && !gpuTab) Box {
                ValueChip(view.proton.name, protonMenu, modifier = Modifier.widthIn(max = 300.dp).heightIn(min = 44.dp)) { protonMenu = !protonMenu }
                AnchoredMenu(protonMenu, onDismiss = { protonMenu = false }, title = stringResource(R.string.comp_proton),
                    note = stringResource(R.string.comp_proton_note)) { first ->
                    views.forEachIndexed { i, v ->
                        val swaps = v.components.values.count { it.activeFile != null }
                        MenuItem(
                            v.proton.name, checked = v.proton.id == view.proton.id,
                            detail = (if (swaps > 0) stringResource(R.string.comp_swapped, v.proton.version, swaps) else stringResource(R.string.comp_all_original, v.proton.version))
                                .let {
                                    when (v.sync?.state) {
                                        "pack" -> stringResource(R.string.comp_esync_pack, it)
                                        "wanted" -> stringResource(R.string.comp_esync_wanted, it)
                                        "builtin" -> stringResource(R.string.comp_esync_builtin, it)
                                        else -> it
                                    }
                                }
                                .let { if (v.inUseByGame) stringResource(R.string.comp_game_running_suffix, it) else it },
                            focusRequester = if (i == 0) first else null,
                        ) { onProton(v.proton.id); protonMenu = false }
                    }
                }
            }
            if (selectedGame == null) {
                val tabs = listOf(GPU_TAB) + comps
                TabStrip(tabs.map { ComponentsManager.LABEL[it] ?: stringResource(R.string.comp_gpu_drivers) }, tabs.indexOf(comp).coerceAtLeast(0), { onComp(tabs[it]) })
                if (comp == "fex" && !gpuTab) FexPresetChip(
                    preset = generalFexPreset,
                    inherited = false,
                    open = fexMenu,
                    enabled = true,
                    onToggle = { fexMenu = !fexMenu },
                    onDismiss = { fexMenu = false },
                    onPick = { it?.let(onGeneralFexPreset); fexMenu = false },
                )
            }
        }

        if (selectedGame != null) {
            GameComponentOverrides(
                game = selectedGame,
                config = environment,
                generalFexPreset = generalFexPreset,
                generalProton = view?.proton,
                protons = views.map { it.proton },
                protonId = gameProtonId,
                protonChoice = gameProtonChoice,
                packages = snapshot?.packages.orEmpty(),
                componentChoices = gameComponents,
                componentMenu = gameComponentMenu,
                protonMenu = gameProtonMenu,
                fexMenu = fexMenu,
                busy = environmentBusy || profileLoading,
                error = environmentError,
                notice = profileNotice,
                winComponentsRevision = winComponentsRevision,
                onFexMenu = { fexMenu = it },
                onFexPreset = ::setGameFexPreset,
                onProtonMenu = { gameProtonMenu = it },
                onProton = ::setGameProton,
                onComponentMenu = { gameComponentMenu = it },
                onComponent = ::setGameComponent,
                onEnvironment = { environmentEditor = true },
                onWinComponents = { winComponentsOpen = false },
                onReset = {
                    ask(
                        ctx.getString(R.string.comp_game_reset_title, selectedGame.name),
                        ctx.getString(R.string.comp_game_reset_body),
                        R.string.comp_game_reset,
                        ::resetGameProfile,
                    )
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
        // ---- what is in use -----------------------------------------------------------------------
        if (view != null && !gpuTab) {
            val st = view.components.getValue(comp)
            val reappliedAt = if (view.reappliedAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(view.reappliedAt * 1000)) else null
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
                    .border(1.dp, pal.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 28.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (st.queued != null) Color(0xFFFFB86B) else pal.good, CircleShape))
                Text(
                    (if (st.queued != null) stringResource(R.string.comp_next_queued, st.queued) else stringResource(R.string.comp_in_use, label, st.inUse))
                        .let { if (reappliedAt != null) stringResource(R.string.comp_reapplied, it, reappliedAt) else it },
                    fontSize = 14.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (st.queued != null) FocusText(stringResource(R.string.common_cancel), pal.signal, onClick = onCancelQueued)
                else if (!narrow) Text(
                    if (view.inUseByGame) stringResource(R.string.comp_waits) else stringResource(R.string.comp_applies_next_game),
                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1,
                )
            }
        }

        // ---- the lists ------------------------------------------------------------------------------
        when {
            gpuTab -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                GpuDriversPanel(gpu, gpuActions) { driverPage = it }
                Row(modifier = Modifier.padding(top = 14.dp)) {
                    SmallButton(stringResource(R.string.comp_import_zip), onClick = gpuActions.onImportZip)
                }
            }
            snapshot == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.comp_reading), fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
            view == null -> Text(
                stringResource(R.string.comp_no_proton),
                fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
            )
            else -> {
                val p = view.proton
                val st = view.components.getValue(comp)
                val build = ComponentsManager.safeName(p.version)
                val originals = view.originals.filter { it.comp == comp }
                val stored = snapshot.packages.filter { it.comp == comp }
                val activeIsOriginal = st.activeFile == null
                val rows = originals.map { o ->
                    val isCurrent = o.protonVersion == build
                    InstalledItem(
                        stringResource(R.string.comp_original, o.protonVersion),
                        o.label.substringAfterLast(" · ", "").ifEmpty { o.label }.let { if (isCurrent) stringResource(R.string.comp_this_build, it) else stringResource(R.string.comp_earlier_build, it) },
                        "ORIGINAL", activeIsOriginal && isCurrent, !isCurrent, null, o.protonVersion,
                    )
                } + stored.map { s ->
                    InstalledItem(s.version, stringResource(R.string.common_size_mb, s.size / 1048576.0), "STORED", s.file == st.activeFile, true, s.file, null)
                }
                val storedNames = snapshot.packages.map { it.file }.toSet()
                val available = catalog.filter { it.comp == comp && ComponentsManager.safeName(it.file) !in storedNames }
                val first = remember { FocusRequester() }
                LaunchedEffect(p.id, comp, requestInitialFocus) {
                    if (requestInitialFocus) runCatching { first.requestFocus() }
                }

                val installed: @Composable () -> Unit = {
                    SettingsGroup(stringResource(R.string.comp_installed_count, rows.size)) {
                        if (rows.isEmpty()) Text(
                            stringResource(R.string.comp_nothing_stored, label),
                            fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        rows.forEachIndexed { i, item ->
                            InstalledLine(
                                item, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                onSelect = {
                                    if (item.selected) return@InstalledLine
                                    val waits = if (view.inUseByGame) ctx.getString(R.string.comp_waits_long) else ctx.getString(R.string.comp_applies_long)
                                    if (item.file != null) ask(ctx.getString(R.string.comp_swap_title, label), ctx.getString(R.string.comp_swap_body, item.name, p.name, waits), R.string.comp_swap) { onSwap(item.file) }
                                    else ask(ctx.getString(R.string.comp_restore_title, label), ctx.getString(R.string.comp_restore_body, item.protonVersion, p.name, waits), R.string.common_restore) { onRestore(item.protonVersion!!) }
                                },
                                onDelete = {
                                    if (item.file != null) ask(ctx.getString(R.string.common_delete_named, item.name) + "?", ctx.getString(R.string.comp_delete_package_body), R.string.common_delete) { onDeletePackage(item.file) }
                                    else ask(ctx.getString(R.string.comp_delete_original_title), ctx.getString(R.string.comp_delete_original_body, item.protonVersion, p.name), R.string.common_delete) { onDeleteOriginal(item.protonVersion!!) }
                                },
                            )
                        }
                    }
                }
                val nightly: @Composable () -> Unit = {
                    SettingsGroup(stringResource(R.string.comp_nightlies_count, available.size)) {
                        if (available.isEmpty()) Text(
                            if (catalogAt == 0L) stringResource(R.string.comp_refresh_to_list) else stringResource(R.string.comp_nothing_new),
                            fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        available.forEach { d -> AvailableLine(d, downloads[d.file], enabled = busy == null) { onDownload(d) } }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    if (narrow) { installed(); nightly() }
                    else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { installed() }
                        Column(Modifier.weight(1f)) { nightly() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                        SmallButton(stringResource(R.string.comp_import_wcp), onClick = onImport)
                        if (!activeIsOriginal && originals.any { it.protonVersion == build }) FocusText(stringResource(R.string.comp_restore_original), pal.signal) {
                            ask(ctx.getString(R.string.comp_restore_title, label), ctx.getString(R.string.comp_restore_original_body, p.name, label), R.string.common_restore) { onRestore(build) }
                        }
                    }
                }
                }
            }
        }
    }

    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { Text(stringResource(R.string.comp_title)) },
        text = {
            Text(
                stringResource(R.string.comp_about_text),
                fontSize = 14.sp,
            )
        },
        confirmButton = { FocusText(stringResource(R.string.common_ok), pal.signal) { about = false } },
    )
    confirm?.let { (body, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(confirmTitle) },
            text = { Text(body, fontSize = 14.sp) },
            // Opens on Cancel, so a stray A press on a controller never swaps or deletes anything.
            confirmButton = {
                val del = confirmVerb == R.string.common_delete || confirmVerb == R.string.comp_game_reset
                FocusText(stringResource(confirmVerb), if (del) colors.error else pal.signal) { confirm = null; action() }
            },
            dismissButton = {
                val cancelFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
                FocusText(stringResource(R.string.common_cancel), colors.onBackground, modifier = Modifier.focusRequester(cancelFocus)) { confirm = null }
            },
        )
    }
    if (environmentEditor && selectedGame != null) {
        GameEnvironmentEditor(
            byPad = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard,
            initialScope = selectedGame.profileKey,
        ) {
            environmentEditor = false
            coroutine.launch {
                runCatching { withContext(Dispatchers.IO) { GameEnvironmentStore.read(ctx) } }
                    .onSuccess { environment = it; environmentError = false }
                    .onFailure { environmentError = true }
            }
        }
    }
    val winOpen = winComponentsOpen
    if (winOpen != null && selectedGame != null) {
        WinComponentsDialog(
            selectedGame.profileKey,
            selectedGame.name,
            selectedGame.gameFiles,
            winOpen,
            compat = selectedGame.protonPrefix,
            steamAppId = selectedGame.appId.takeIf { selectedGame.library != Library.ADDED },
        ) {
            winComponentsOpen = null
            winComponentsRevision++
        }
    }
}

@Composable
private fun GameComponentOverrides(
    game: Library.SteamGame,
    config: GameEnvironment.Config?,
    generalFexPreset: String,
    generalProton: ComponentsManager.Proton?,
    protons: List<ComponentsManager.Proton>,
    protonId: String?,
    protonChoice: ProtonDefault.GameChoice?,
    packages: List<ComponentsManager.Package>,
    componentChoices: Map<String, String>,
    componentMenu: String?,
    protonMenu: Boolean,
    fexMenu: Boolean,
    busy: Boolean,
    error: Boolean,
    notice: String?,
    winComponentsRevision: Int,
    onFexMenu: (Boolean) -> Unit,
    onFexPreset: (String?) -> Unit,
    onProtonMenu: (Boolean) -> Unit,
    onProton: (String?) -> Unit,
    onComponentMenu: (String?) -> Unit,
    onComponent: (String, String?) -> Unit,
    onEnvironment: () -> Unit,
    onWinComponents: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val override = config?.let { GameEnvironment.gameFexPreset(it, game.profileKey) }
    val proton = protons.firstOrNull { it.id == protonId }
    val effectiveProton = if (protonChoice == null) generalProton else proton
    val picks = remember(game.profileKey, winComponentsRevision) { WinComponents.picks(context, game.profileKey) }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 16.dp),
    ) {
        Text(
            stringResource(R.string.comp_game_hint),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsGroup(stringResource(R.string.fex_preset_title)) {
            SettingsRow(
                stringResource(R.string.comp_game_fex),
                stringResource(if (override == null) R.string.comp_game_fex_inherited else R.string.comp_game_fex_override),
            ) {
                FexPresetChip(
                    preset = override ?: generalFexPreset,
                    inherited = override == null,
                    open = fexMenu,
                    enabled = config != null && !busy,
                    onToggle = { onFexMenu(!fexMenu) },
                    onDismiss = { onFexMenu(false) },
                    onPick = onFexPreset,
                )
            }
            GamePackageRow(
                comp = "fex",
                packages = packages,
                selectedFile = componentChoices["fex"],
                open = componentMenu == "fex",
                busy = busy,
                onMenu = onComponentMenu,
                onPick = onComponent,
            )
        }
        SettingsGroup(stringResource(R.string.comp_game_compatibility)) {
            SettingsRow(
                stringResource(R.string.comp_proton),
                stringResource(if (protonChoice == null) R.string.comp_game_proton_inherited else R.string.comp_game_proton_override),
            ) {
                Box {
                    ValueChip(
                        proton?.name ?: protonChoice?.dir ?: stringResource(R.string.comp_game_use_general),
                        protonMenu,
                        enabled = protons.isNotEmpty() && !busy,
                        modifier = Modifier.widthIn(max = 280.dp),
                    ) { onProtonMenu(!protonMenu) }
                    AnchoredMenu(
                        protonMenu,
                        onDismiss = { onProtonMenu(false) },
                        title = stringResource(R.string.comp_proton),
                    ) { first ->
                        MenuItem(
                            stringResource(R.string.comp_game_use_general),
                            checked = protonChoice == null,
                            focusRequester = first,
                        ) { onProton(null); onProtonMenu(false) }
                        protons.forEach { option ->
                            MenuItem(option.name, checked = proton?.id == option.id) {
                                onProton(option.id)
                                onProtonMenu(false)
                            }
                        }
                    }
                }
            }
            GamePackageRow(
                comp = "dxvk",
                packages = packages,
                selectedFile = componentChoices["dxvk"],
                open = componentMenu == "dxvk",
                busy = busy,
                proton = effectiveProton,
                onMenu = onComponentMenu,
                onPick = onComponent,
                onRuntimeOptions = onEnvironment,
            )
            GamePackageRow(
                comp = "vkd3d",
                packages = packages,
                selectedFile = componentChoices["vkd3d"],
                open = componentMenu == "vkd3d",
                busy = busy,
                proton = effectiveProton,
                onMenu = onComponentMenu,
                onPick = onComponent,
                onRuntimeOptions = onEnvironment,
            )
            SettingsRow(
                stringResource(R.string.game_env_title),
                stringResource(R.string.comp_game_environment_other_hint),
            ) {
                SecondaryButton(stringResource(R.string.game_env_edit), enabled = !busy, onClick = onEnvironment)
            }
            SettingsRow(
                stringResource(R.string.wincomp_title),
                if (picks.isEmpty()) stringResource(R.string.wincomp_card_none)
                else picks.joinToString(", ") { com.droiddeck.launcher.session.WinComponentNames.of(it) },
            ) {
                SecondaryButton(stringResource(R.string.game_env_edit), onClick = onWinComponents)
            }
        }
        notice?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = if (error) MaterialTheme.colorScheme.error else LocalPalette.current.good,
            )
        }
        if (error && notice == null) Text(stringResource(R.string.game_env_error), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        if (!busy) {
            FocusText(
                stringResource(R.string.comp_game_reset),
                MaterialTheme.colorScheme.error,
                onClick = onReset,
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun GamePackageRow(
    comp: String,
    packages: List<ComponentsManager.Package>,
    selectedFile: String?,
    open: Boolean,
    busy: Boolean,
    proton: ComponentsManager.Proton? = null,
    onMenu: (String?) -> Unit,
    onPick: (String, String?) -> Unit,
    onRuntimeOptions: (() -> Unit)? = null,
) {
    val choices = packages.filter { it.comp == comp }
    val selected = choices.firstOrNull { it.file == selectedFile }
    val title = ComponentsManager.LABEL[comp] ?: comp
    SettingsRow(
        title,
        if (selected == null) {
            if (proton == null) stringResource(R.string.comp_game_component_general)
            else stringResource(R.string.comp_game_component_runtime, proton.name)
        } else stringResource(R.string.comp_game_component_override, selected.version),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                ValueChip(
                    selected?.version ?: stringResource(R.string.comp_game_use_general),
                    open,
                    enabled = !busy,
                    modifier = Modifier.widthIn(max = 240.dp),
                ) { onMenu(if (open) null else comp) }
                AnchoredMenu(open, onDismiss = { onMenu(null) }, title = title) { first ->
                    MenuItem(
                        stringResource(R.string.comp_game_use_general),
                        checked = selected == null,
                        focusRequester = first,
                    ) { onPick(comp, null); onMenu(null) }
                    choices.forEach { option ->
                        MenuItem(option.version, checked = option.file == selectedFile, detail = option.description) {
                            onPick(comp, option.file)
                            onMenu(null)
                        }
                    }
                }
            }
            if (onRuntimeOptions != null) {
                SecondaryButton(stringResource(R.string.comp_game_runtime_options), enabled = !busy, onClick = onRuntimeOptions)
            }
        }
    }
}

@Composable
private fun FexPresetChip(
    preset: String,
    inherited: Boolean,
    open: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val chosen = FexPreset.all.firstOrNull { it.id == preset }
    val label = when {
        preset == GameEnvironment.CUSTOM_FEX_PRESET -> stringResource(R.string.comp_game_fex_custom)
        chosen == null -> stringResource(FexPreset.byId("").label)
        inherited -> stringResource(R.string.comp_game_use_general)
        else -> stringResource(chosen.label)
    }
    Box {
        ValueChip(label, open, enabled = enabled, modifier = Modifier.widthIn(max = 260.dp), onClick = onToggle)
        AnchoredMenu(open, onDismiss = onDismiss, title = stringResource(R.string.fex_preset_title)) { first ->
            if (inherited || preset == GameEnvironment.CUSTOM_FEX_PRESET) {
                MenuItem(
                    stringResource(R.string.comp_game_use_general),
                    checked = inherited,
                    focusRequester = first,
                ) { onPick(null); onDismiss() }
            }
            FexPreset.all.forEachIndexed { index, option ->
                MenuItem(
                    stringResource(option.label),
                    checked = !inherited && option.id == preset,
                    detail = stringResource(option.detail),
                    focusRequester = if (index == 0 && !inherited && preset != GameEnvironment.CUSTOM_FEX_PRESET) first else null,
                ) { onPick(option.id); onDismiss() }
            }
        }
    }
}

@Composable
internal fun ToolIcon(icon: ImageVector, description: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (hot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
            .glideBorder(hot, RoundedCornerShape(12.dp), pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick),
    ) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Icon(icon, contentDescription = description, tint = colors.onBackground, modifier = Modifier.size(20.dp))
    }
}

/**
 * A small button. Resting: grey outline (blue text when [accent]). Focused or hovered: a solid blue
 * fill with an outline in the text colour - unmistakable in a column of identical buttons.
 */
@Composable
private fun SmallButton(text: String, enabled: Boolean = true, accent: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = (src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value) && enabled
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (hot) pal.signal else colors.surfaceVariant)
            .glideBorder(hot, RoundedCornerShape(10.dp), colors.onBackground, pal.line2, restWidth = 2.dp)
            .hoverable(src).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (!enabled) colors.onSurfaceVariant else if (hot) pal.onSignal else if (accent) pal.signal else colors.onBackground,
        )
    }
}

@Composable
private fun Tag(text: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val (fg, bg) = when (text) {
        "ORIGINAL" -> GOLD to Color(0x22F2C66D)
        "IN USE" -> pal.onSignal to pal.signal
        "NEW" -> pal.good to Color(0x224CD37F)
        else -> colors.onSurfaceVariant to Color.White.copy(alpha = 0.07f)
    }
    Text(
        when (text) {
            "ORIGINAL" -> stringResource(R.string.comp_tag_original)
            "IN USE" -> stringResource(R.string.comp_tag_in_use)
            "NEW" -> stringResource(R.string.comp_tag_new)
            "STORED" -> stringResource(R.string.comp_tag_stored)
            else -> text.lowercase().replaceFirstChar { it.uppercase() }
        }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun InstalledLine(item: InstalledItem, modifier: Modifier = Modifier, onSelect: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (item.selected) pal.signal.copy(alpha = 0.07f) else Color.Transparent),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).then(modifier)
                .glideBorder(hot, ROW_SHAPE, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onSelect)
                .controllerConfirm(onClick = onSelect)
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).border(2.dp, if (item.selected) pal.signal else colors.onSurfaceVariant, CircleShape)) {
                if (item.selected) Box(Modifier.size(9.dp).background(pal.signal, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (item.selected) pal.signal else colors.onBackground, modifier = Modifier.weight(1f, fill = false),
                    )
                    Tag(item.tag)
                }
                Text(item.detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (item.selected) Tag("IN USE")
        }
        // The trash column is always there (empty when the row can't be deleted) so rows line up.
        if (!item.removable) Spacer(Modifier.padding(end = 6.dp).size(40.dp))
        else {
            val delSrc = remember { MutableInteractionSource() }
            val delHot = delSrc.collectIsFocusedAsState().value || delSrc.collectIsHoveredAsState().value
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(end = 6.dp).size(40.dp).clip(ROW_SHAPE)
                    .background(if (delHot) colors.error.copy(alpha = 0.14f) else Color.Transparent)
                    .glideBorder(delHot, ROW_SHAPE, colors.error)
                    .hoverable(delSrc).clickable(interactionSource = delSrc, indication = null, onClick = onDelete)
                    .controllerConfirm(onClick = onDelete),
            ) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete_named, item.name), tint = if (delHot) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun AvailableLine(d: CatalogItem, progress: Int?, enabled: Boolean, onDownload: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.file.removeSuffix(".wcp"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${d.release} · " + stringResource(R.string.common_size_mb, d.size / 1048576.0), fontSize = 13.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (progress == null) SmallButton(stringResource(R.string.common_download), enabled = enabled, accent = true, onClick = onDownload)
        else Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
            Text(if (progress < 0) "…" else "$progress%", fontSize = 13.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (progress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/** The Components page's tab for the GPU drivers, before the Proton components' own. */
const val GPU_TAB = "gpu"
