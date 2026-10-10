package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.ComponentsManager.CatalogItem
import com.droiddeck.launcher.session.ComponentsManager.Snapshot
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The Components page's layer for the GPU drivers, under every Proton's. */
const val GPU_TAB = "gpu"

/** The Components page's layer for the Proton itself, the one the indented layers belong to. */
const val PROTON_TAB = "proton"

/** The stack's layers, top to bottom: LB / RB and the d-pad walk them in this order. */
internal val LAYERS = listOf(PROTON_TAB, "dxvk", "vkd3d", "fex", GPU_TAB)

/** The layer [by] steps from [current] (LB -1, RB +1), wrapping round the stack. */
internal fun layerStep(current: String, by: Int): String {
    val i = LAYERS.indexOf(current).coerceAtLeast(0)
    return LAYERS[((i + by) % LAYERS.size + LAYERS.size) % LAYERS.size]
}

/** One installed row: a Proton's original bundle (one per Proton build) or a stored package. */
private class InstalledItem(
    val name: String, val detail: String, val tag: String,
    val selected: Boolean, val removable: Boolean,
    /** Stored package file, or null for an original. */
    val file: String?,
    /** The Proton build an original belongs to. */
    val protonVersion: String?,
)

private val GOLD = Color(0xFFF2C66D)
private val QUEUED = Color(0xFFFFB86B)
private val ROW_SHAPE = RoundedCornerShape(10.dp)
private val LAYER_SHAPE = RoundedCornerShape(12.dp)

/**
 * Everything a game runs on, as one stack: the Proton, its DXVK, VKD3D-Proton and FEX indented
 * under it because they belong to it, then the GPU drivers that every Proton shares. One selection
 * glides along the stack (LB / RB, or the d-pad in it); beside it a detail frame that never moves -
 * what is in use, what is installed and what is available, and what else can be done - only its
 * rows trade places. A swap is asked for by stepping out of its row, and the row flies home to its
 * layer. On a narrow page the stack is the page, and a layer's detail floods out of its card.
 */
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
    requestInitialFocus: Boolean = true,
    /** The GPU drivers layer ([GPU_TAB]): what it shows and does. */
    gpu: GpuDriversState = GpuDriversState(),
    gpuActions: GpuDriversActions = GpuDriversActions(),
    /** The Proton layer ([PROTON_TAB]): the Protons DroidDeck can install, and installing them. */
    protons: ProtonsState = ProtonsState(),
    protonActions: ProtonsActions = ProtonsActions(),
    /** The Proton layer's options for every Proton (sync, FEX preset, SSBS, environment). */
    protonOptions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val inputMode = LocalInputModeManager.current
    val layer = if (comp in LAYERS) comp else GPU_TAB
    BackHandler(onBack = onBack)

    val views = snapshot?.protons ?: emptyList()
    val view = views.firstOrNull { it.proton.id == protonId } ?: views.firstOrNull()

    // Where things sit, root px: each layer card, each row (for a swap's dot), the page itself.
    val cardsAt = remember { mutableStateMapOf<String, Rect>() }
    val rowsAt = remember { HashMap<String, Rect>() }
    var stackAt by remember { mutableStateOf(Offset.Zero) }
    var pageAt by remember { mutableStateOf(Offset.Zero) }
    // A layer card pops and rings as a swap's dot lands on it, and rings as a hop arrives on it.
    val landed = remember { mutableStateMapOf<String, Int>() }
    val rings = remember { mutableStateMapOf<String, Int>() }
    fun land(l: String) { landed[l] = (landed[l] ?: 0) + 1; rings[l] = (rings[l] ?: 0) + 1 }
    val arrivedSeen = remember { intArrayOf(Hops.arrived) }
    LaunchedEffect(Hops.arrived) {
        if (Hops.arrived == arrivedSeen[0]) return@LaunchedEffect
        arrivedSeen[0] = Hops.arrived
        // Once the page has flooded in from the rail.
        delay(Motion.ms(700).toLong())
        rings[layer] = (rings[layer] ?: 0) + 1
    }
    /** The row at [key] squashes into a dot and flies home to [to]'s card, which pops on landing. */
    fun flyHome(key: String, to: String) {
        val from = rowsAt[key] ?: return land(to)
        Flights.fly(Flight(from, to = { cardsAt[to]?.center }, onLand = { land(to) }))
    }

    // A confirm stepping out of the row that asked: right of a left-column row, left of a right one.
    fun ask(key: String, right: Boolean, title: String, note: String, verb: String, danger: Boolean, face: String = "", then: () -> Unit) {
        val at = rowsAt[key] ?: return then()
        val cancel = ctx.getString(R.string.common_cancel)
        Steps.ask(StepAsk(
            anchor = at, side = if (right) StepSide.Left else StepSide.Right,
            accent = if (danger) colors.error else pal.signal, pillCorner = 10.dp, items = 3, maxWidth = 340.dp,
            // The row's own name rides on top, as the pull's handle.
            handle = {
                Box(Modifier.fillMaxSize().padding(start = 46.dp, end = 14.dp), contentAlignment = Alignment.CenterStart) {
                    Text(face, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (danger) colors.error else pal.signal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
        ) {
            StepTitle(title)
            if (note.isNotEmpty()) Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().stepItem(2)) {
                StepChoice(cancel, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                StepChoice(verb, danger = danger, enabled = open) { Steps.fold(); then() }
            }
        })
    }

    // The runtime and display drivers on their own pages, flooding out of the link that opens them.
    var driverPage by remember { mutableStateOf<String?>(null) }
    var driverShown by remember { mutableStateOf<String?>(null) }
    var driverLeaving by remember { mutableStateOf(false) }
    var driverFrom by remember { mutableStateOf<Origin?>(null) }
    LaunchedEffect(driverPage) {
        val d = driverPage
        if (d != null) { driverLeaving = false; driverShown = d }
        else if (driverShown != null) {
            if (driverFrom != null) { driverLeaving = true; delay(Motion.ms(PAGE_RETURN_MS).toLong()) }
            driverShown = null; driverLeaving = false; driverFrom = null
        }
    }
    // Narrow: a layer's detail floods out of its card, and B draws it back in.
    var narrowOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var narrowShown by remember { mutableStateOf<String?>(null) }
    var narrowLeaving by remember { mutableStateOf(false) }
    LaunchedEffect(narrowOpen) {
        val d = narrowOpen
        if (d != null) { narrowLeaving = false; narrowShown = d }
        else if (narrowShown != null) {
            narrowLeaving = true; delay(Motion.ms(PAGE_RETURN_MS).toLong())
            narrowShown = null; narrowLeaving = false
        }
    }
    if (!narrow && narrowOpen != null) narrowOpen = null
    BackHandler(enabled = driverPage != null || narrowOpen != null) {
        if (driverPage != null) driverPage = null else narrowOpen = null
    }
    // Leaving the page: nothing stays stepped out of a row that is no longer on screen.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Steps.drop() } }

    val checked = if (catalogAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(catalogAt * 1000)) else stringResource(R.string.comp_never)
    val firstRow = remember { FocusRequester() }
    val layerFocus = remember { LAYERS.associateWith { FocusRequester() } }
    // Arrived (from the rail, a hop, a game's card): a pad starts on the chosen layer.
    LaunchedEffect(requestInitialFocus) {
        if (!requestInitialFocus || inputMode.inputMode != InputMode.Keyboard) return@LaunchedEffect
        delay(Motion.ms(200).toLong())
        focusWithinFrames({ false }) { layerFocus.getValue(layer) }
    }
    var travel by remember { mutableIntStateOf(1) }
    val pick: (String) -> Unit = { l ->
        if (l != layer) { travel = if (LAYERS.indexOf(l) > LAYERS.indexOf(layer)) 1 else -1; onComp(l) }
    }

    // ---- one layer's detail, a slot at a time ------------------------------------------------------
    val compState = { l: String -> view?.components?.get(l) }
    val bar: @Composable (String) -> Unit = { l ->
        when (l) {
            GPU_TAB -> {
                val active = gpu.activePair
                InUseBar(
                    queued = gpu.busy != null,
                    sentence = (active?.let { "${it.name} ${it.version}" } ?: gpu.activeBundle ?: stringResource(R.string.gpu_builtin))
                        .let { if (gpu.auto) stringResource(R.string.gpu_bar_auto, it) else it },
                    note = if (gpu.busy != null) (if (gpu.percent >= 0) "${gpu.percent}%" else "…")
                        else gpu.autoStatus.ifEmpty { if (active != null || gpu.activeBundle != null) "" else gpu.pairs.firstOrNull { it.recommended }?.let { stringResource(R.string.gpu_recommended_for, it.name) } ?: stringResource(R.string.gpu_refresh_hint) },
                )
            }
            PROTON_TAB -> InUseBar(
                queued = false,
                sentence = view?.let { stringResource(R.string.comp_proton_bar, it.proton.name) } ?: stringResource(R.string.comp_no_proton),
                note = if (view?.inUseByGame == true) stringResource(R.string.comp_waits) else "",
            )
            else -> {
                val st = compState(l)
                val label = ComponentsManager.LABEL[l] ?: l
                if (st == null) InUseBar(false, stringResource(R.string.comp_no_proton), "")
                else {
                    val reappliedAt = view?.reappliedAt?.takeIf { it > 0 }?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it * 1000)) }
                    InUseBar(
                        queued = st.queued != null,
                        sentence = (if (st.queued != null) stringResource(R.string.comp_next_queued, st.queued) else stringResource(R.string.comp_in_use, label, st.inUse))
                            .let { if (reappliedAt != null) stringResource(R.string.comp_reapplied, it, reappliedAt) else it },
                        note = if (view?.inUseByGame == true) stringResource(R.string.comp_waits) else stringResource(R.string.comp_applies_next_game),
                        onCancel = if (st.queued != null) onCancelQueued else null,
                    )
                }
            }
        }
    }

    // Installed and Available, as (title, rows) for a layer; [right] says which column it is in.
    var showAllPairs by rememberSaveable { mutableStateOf(false) }
    val installedTitle: @Composable (String) -> String = { l ->
        when (l) {
            GPU_TAB -> stringResource(R.string.comp_installed_count, 1 + gpu.pairs.count { it.installed && (it.suits || showAllPairs) })
            PROTON_TAB -> stringResource(R.string.comp_installed_count, views.size)
            else -> stringResource(R.string.comp_installed_count, installedFor(l, view, snapshot).size)
        }
    }
    val availableTitle: @Composable (String) -> String = { l ->
        when (l) {
            GPU_TAB -> stringResource(R.string.comp_available_count, gpu.pairs.count { !it.installed && it.complete && (it.suits || showAllPairs) })
            PROTON_TAB -> stringResource(R.string.comp_available_count, protons.rows.count { it.installed == null })
            else -> stringResource(R.string.comp_nightlies_count, availableFor(l, catalog, snapshot).size)
        }
    }
    val installedRows: @Composable ColumnScope.(String, Boolean) -> Unit = { l, right ->
        when (l) {
            GPU_TAB -> {
                val autoKey = "gpu:auto"
                ChoiceLine(
                    stringResource(R.string.common_auto), stringResource(R.string.gpu_auto_keeps), gpu.auto, removable = false,
                    modifier = Modifier.focusRequester(firstRow).trackRow(autoKey, rowsAt),
                    onSelect = {
                        if (gpu.auto) return@ChoiceLine
                        ask(autoKey, right, ctx.getString(R.string.gpu_use_title, ctx.getString(R.string.common_auto)), ctx.getString(R.string.gpu_auto_hint), ctx.getString(R.string.common_use), false, ctx.getString(R.string.common_auto)) {
                            gpuActions.onAuto(true); flyHome(autoKey, GPU_TAB)
                        }
                    },
                )
                for (p in gpu.pairs.filter { it.installed && (it.suits || showAllPairs) }) {
                    val key = "gpu:${p.key}"
                    ChoiceLine(
                        p.name, "${p.version} · ${p.detail}", !gpu.auto && p.active, removable = false,
                        badge = if (p.recommended) stringResource(R.string.gpu_recommended_caps) else null,
                        progress = if (gpu.busy == p.key) gpu.percent else null,
                        modifier = Modifier.trackRow(key, rowsAt),
                        onSelect = {
                            if (!gpu.auto && p.active || gpu.busy != null) return@ChoiceLine
                            ask(key, right, ctx.getString(R.string.gpu_use_title, p.name), "${p.version} · ${p.detail}", ctx.getString(R.string.common_use), false, p.name) {
                                gpuActions.onAuto(false); gpuActions.onPair(p.key); flyHome(key, GPU_TAB)
                            }
                        },
                    )
                }
            }
            PROTON_TAB -> {
                if (views.isEmpty()) EmptyLine(stringResource(if (snapshot == null) R.string.comp_reading else R.string.comp_no_proton))
                views.forEachIndexed { i, v ->
                    val key = "proton:${v.proton.id}"
                    val swaps = v.components.values.count { it.activeFile != null }
                    val extra = protons.rows.firstOrNull { it.installed == v.proton.dir.name }
                    val removable = extra != null && protons.busyId == null && protons.runtimeReady && !protons.sessionRunning
                    ChoiceLine(
                        v.proton.name,
                        (if (swaps > 0) stringResource(R.string.comp_swapped, v.proton.version, swaps) else stringResource(R.string.comp_all_original, v.proton.version))
                            .let { if (v.inUseByGame) stringResource(R.string.comp_game_running_suffix, it) else it },
                        v.proton.id == view?.proton?.id, removable = removable,
                        modifier = (if (i == 0) Modifier.focusRequester(firstRow) else Modifier).trackRow(key, rowsAt),
                        onSelect = { if (v.proton.id != view?.proton?.id) { onProton(v.proton.id); flyHome(key, PROTON_TAB) } },
                        onDelete = {
                            if (extra != null) ask(key, right, ctx.getString(R.string.proton_remove_title, v.proton.name), "", ctx.getString(R.string.store_remove), true, v.proton.name) {
                                protonActions.onRemove(extra.id)
                            }
                        },
                    )
                }
            }
            else -> {
                val label = ComponentsManager.LABEL[l] ?: l
                val rows = installedFor(l, view, snapshot)
                if (rows.isEmpty()) EmptyLine(stringResource(R.string.comp_nothing_stored, label))
                val p = view?.proton
                rows.forEachIndexed { i, item ->
                    val key = "row:$l:${item.file ?: item.protonVersion}"
                    ChoiceLine(
                        item.name, item.detail, item.selected, item.removable, tag = item.tag,
                        modifier = (if (i == 0) Modifier.focusRequester(firstRow) else Modifier).trackRow(key, rowsAt),
                        onSelect = {
                            if (item.selected || p == null) return@ChoiceLine
                            val waits = if (view.inUseByGame) ctx.getString(R.string.comp_waits_long) else ctx.getString(R.string.comp_applies_long)
                            if (item.file != null) ask(key, right, ctx.getString(R.string.comp_swap_title, label), ctx.getString(R.string.comp_swap_body, item.name, p.name, waits), ctx.getString(R.string.comp_swap), false, item.name) {
                                onSwap(item.file); flyHome(key, l)
                            }
                            else ask(key, right, ctx.getString(R.string.comp_restore_title, label), ctx.getString(R.string.comp_restore_body, item.protonVersion, p.name, waits), ctx.getString(R.string.common_restore), false, item.name) {
                                onRestore(item.protonVersion!!); flyHome(key, l)
                            }
                        },
                        onDelete = {
                            if (p == null) return@ChoiceLine
                            if (item.file != null) ask(key, right, ctx.getString(R.string.common_delete_named, item.name) + "?", ctx.getString(R.string.comp_delete_package_body), ctx.getString(R.string.common_delete), true, item.name) { onDeletePackage(item.file) }
                            else ask(key, right, ctx.getString(R.string.comp_delete_original_title), ctx.getString(R.string.comp_delete_original_body, item.protonVersion, p.name), ctx.getString(R.string.common_delete), true, item.name) { onDeleteOriginal(item.protonVersion!!) }
                        },
                    )
                }
            }
        }
    }
    val availableRows: @Composable ColumnScope.(String) -> Unit = { l ->
        when (l) {
            GPU_TAB -> {
                val shown = gpu.pairs.filter { !it.installed && it.complete && (it.suits || showAllPairs) }
                if (shown.isEmpty()) EmptyLine(stringResource(if (gpu.checking) R.string.gpu_checking else R.string.gpu_none_listed))
                for (p in shown) GetLine(
                    p.name, "${p.version} · ${p.detail}", progress = if (gpu.busy == p.key) gpu.percent else null, enabled = gpu.busy == null,
                    badge = if (p.recommended) stringResource(R.string.gpu_recommended_caps) else null,
                ) { gpuActions.onAuto(false); gpuActions.onPair(p.key) }
                if (gpu.pairs.any { !it.suits }) Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    FocusText(stringResource(if (showAllPairs) R.string.gpu_hide_other else R.string.gpu_show_other), pal.signal) { showAllPairs = !showAllPairs }
                }
            }
            PROTON_TAB -> {
                val rows = protons.rows.filter { it.installed == null }
                if (rows.isEmpty()) EmptyLine(stringResource(R.string.comp_nothing_new))
                for (row in rows) GetLine(
                    row.name, if (row.queued) stringResource(R.string.proton_queued) else stringResource(R.string.proton_not_installed),
                    progress = if (protons.busyId == row.id) protons.percent else null,
                    stage = if (protons.busyId == row.id) protons.stage else null,
                    enabled = protons.busyId == null && protons.runtimeReady && !protons.sessionRunning,
                    onCancel = if (row.queued && protons.busyId == null && !protons.sessionRunning) ({ protonActions.onCancel(row.id) }) else null,
                ) { protonActions.onInstall(row.id) }
            }
            else -> {
                val available = availableFor(l, catalog, snapshot)
                if (available.isEmpty()) EmptyLine(if (catalogAt == 0L) stringResource(R.string.comp_refresh_to_list) else stringResource(R.string.comp_nothing_new))
                for (d in available) GetLine(
                    d.file.removeSuffix(".wcp"), "${d.release} · " + stringResource(R.string.common_size_mb, d.size / 1048576.0),
                    progress = downloads[d.file], enabled = busy == null,
                ) { onDownload(d) }
            }
        }
    }
    val actions: @Composable (String) -> Unit = { l ->
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when (l) {
                GPU_TAB -> {
                    SmallButton(stringResource(R.string.comp_import_zip), onClick = gpuActions.onImportZip)
                    var at by remember { mutableStateOf<Rect?>(null) }
                    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
                        FocusText(stringResource(R.string.gpu_separately) + " ›", pal.signal) {
                            val from = at
                            val title = ctx.getString(R.string.gpu_separately)
                            if (from == null) { driverPage = "rt"; return@FocusText }
                            Steps.ask(StepAsk(anchor = from, side = StepSide.Right, accent = pal.signal, pillCorner = 10.dp, items = 3, handle = {}) {
                                StepTitle(title)
                                StepList {
                                    StepChoice(ctx.getString(R.string.comp_runtime_driver), enabled = open, modifier = Modifier.fillMaxWidth().stepItem(1).focusRequester(first)) {
                                        Steps.fold(); driverFrom = Origin(from.translate(-pageAt), 10f * density.density); driverPage = "rt"
                                    }
                                    StepChoice(ctx.getString(R.string.comp_display_driver), enabled = open, modifier = Modifier.fillMaxWidth().stepItem(2)) {
                                        Steps.fold(); driverFrom = Origin(from.translate(-pageAt), 10f * density.density); driverPage = "panel"
                                    }
                                }
                            })
                        }
                    }
                }
                PROTON_TAB -> {}
                else -> {
                    SmallButton(stringResource(R.string.comp_import_wcp), onClick = onImport)
                    val st = compState(l)
                    val p = view?.proton
                    val build = p?.let { ComponentsManager.safeName(it.version) }
                    if (st != null && p != null && st.activeFile != null && view.originals.any { it.comp == l && it.protonVersion == build }) {
                        val label = ComponentsManager.LABEL[l] ?: l
                        Box(Modifier.trackRow("restore:$l", rowsAt)) {
                            FocusText(stringResource(R.string.comp_restore_original), pal.signal) {
                                ask("restore:$l", false, ctx.getString(R.string.comp_restore_title, label), ctx.getString(R.string.comp_restore_original_body, p.name, label), ctx.getString(R.string.common_restore), false, ctx.getString(R.string.comp_restore_original)) {
                                    onRestore(build!!); flyHome("restore:$l", l)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- the frame ------------------------------------------------------------------------------
    val detail: @Composable (String, Boolean) -> Unit = { l, narrowPage ->
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two columns while each gets room for a row's name and its Get; one under the other below that.
        val single = narrowPage || maxWidth < 600.dp
        Column(Modifier.fillMaxSize()) {
            // The in-use bar keeps its place; only its words change.
            AnimatedContent(l, transitionSpec = { fadeIn(Motion.tw(240)) togetherWith fadeOut(Motion.tw(160)) }, label = "inUse") { k -> bar(k) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp)) {
                val slide: @Composable (@Composable (String) -> Unit) -> Unit = { body ->
                    AnimatedContent(
                        l,
                        transitionSpec = {
                            val d = travel
                            val shift = with(density) { 16.dp.roundToPx() }
                            (slideInVertically(Motion.tw(240)) { shift * d } + fadeIn(Motion.tw(240)))
                                .togetherWith(slideOutVertically(Motion.tw(240)) { -shift * d } + fadeOut(Motion.tw(240)))
                        },
                        label = "layerRows",
                    ) { k -> body(k) }
                }
                if (single) {
                    slide { k -> RowGroup(installedTitle(k)) { installedRows(k, false) } }
                    Spacer(Modifier.height(12.dp))
                    slide { k -> RowGroup(availableTitle(k)) { availableRows(k) } }
                } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { slide { k -> RowGroup(installedTitle(k)) { installedRows(k, false) } } }
                    Column(Modifier.weight(1f)) { slide { k -> RowGroup(availableTitle(k)) { availableRows(k) } } }
                }
                Box(Modifier.padding(top = 14.dp)) { slide { k -> actions(k) } }
                if (l == PROTON_TAB) {
                    Spacer(Modifier.height(10.dp))
                    if (!protons.runtimeReady) EmptyLine(stringResource(R.string.content_needs_runtime))
                    if (protons.sessionRunning) EmptyLine(stringResource(R.string.proton_stop_first))
                    SettingsGroup(stringResource(R.string.comp_for_every_proton)) { protonOptions() }
                    Spacer(Modifier.height(12.dp))
                    Note(stringResource(R.string.comp_about_text))
                }
            }
        }
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { pageAt = it.positionInRoot() }) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)
                .bumpers(onPrevious = { pick(layerStep(layer, -1)) }, onNext = { pick(layerStep(layer, 1)) }),
        ) {
            // ---- header: the title, the device, when the lists were checked and the one refresh ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.comp_title), fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
                if (!narrow) DeviceChip(gpu)
                Spacer(Modifier.weight(1f))
                HopBackChip(onBack)
                if (!narrow) Text(
                    if (busy != null) "$busy…" else stringResource(R.string.comp_checked, checked),
                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val refreshing = checking || gpu.checking
                ToolIcon(Icons.Outlined.Refresh, stringResource(R.string.comp_check_all), busy = refreshing, enabled = !refreshing && gpu.busy == null) {
                    onRefresh(); gpuActions.onRefresh()
                }
            }
            if (narrow) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) { DeviceChip(gpu) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // ---- the stack ----
                Box(
                    (if (narrow) Modifier.fillMaxWidth() else Modifier.width(250.dp)).fillMaxHeight()
                        .onGloballyPositioned { stackAt = it.positionInRoot() },
                ) {
                    val tint = rememberUpdatedState(pal.signal)
                    val local = cardsAt[layer]?.translate(-stackAt)
                    ChipGlide(LAYERS.indexOf(layer), local, tint, GlideAxis.Vertical, corner = 12.dp)
                    Stack(
                        layer, view, gpu, focus = layerFocus, landed = landed, rings = rings,
                        onPlaced = { l, r -> cardsAt[l] = r },
                        onFocus = { l -> pick(l) },
                        onOpen = { l ->
                            pick(l)
                            if (narrow) narrowOpen = l
                            else runCatching { firstRow.requestFocus() }
                        },
                    )
                }
                if (!narrow) {
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) { detail(layer, false) }
                }
            }
        }
        // Narrow: the layer's detail over the stack, out of its card.
        narrowShown?.let { l ->
            val from = cardsAt[l]?.let { Origin(it.translate(-pageAt), 12f * density.density) }
            val body: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = 16.dp, vertical = 12.dp).focusGroup(),
                ) {
                    BackLink(LayerTitle(l), compact = true) { narrowOpen = null }
                    Spacer(Modifier.height(8.dp))
                    detail(layer, true)
                }
            }
            if (from == null) body() else PageFlood(from, narrowLeaving) { body() }
        }
        // The runtime or display driver on its own page.
        driverShown?.let { d ->
            val body: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) { detectTapGestures { } }) {
                    if (d == "rt") DriverPage(
                        title = stringResource(R.string.comp_runtime_driver), hint = stringResource(R.string.comp_runtime_driver_hint),
                        rows = gpu.linuxRows, selected = gpu.linuxSelected, downloads = gpu.linuxDownloads,
                        status = gpu.releaseStatus, checking = gpu.checking, importLabel = stringResource(R.string.comp_import_turnip), canRestore = false,
                        onSelect = gpuActions.onSelectLinux, onDelete = gpuActions.onRemoveLinux, onRefresh = gpuActions.onRefresh,
                        onDownload = gpuActions.onDownloadDriver, onImport = gpuActions.onImportLinux, onRestore = {}, onBack = { driverPage = null },
                    )
                    else DriverPage(
                        title = stringResource(R.string.comp_display_driver), hint = stringResource(R.string.comp_display_driver_hint),
                        rows = gpu.androidRows, selected = gpu.androidSelected, downloads = gpu.androidDownloads,
                        status = gpu.releaseStatus, checking = gpu.checking, importLabel = stringResource(R.string.comp_import_adrenotools),
                        canRestore = gpu.canRestoreBundled,
                        onSelect = gpuActions.onSelectAndroid, onDelete = gpuActions.onRemoveAndroid, onRefresh = gpuActions.onRefresh,
                        onDownload = gpuActions.onDownloadDriver, onImport = gpuActions.onImportAndroid, onRestore = gpuActions.onRestoreBundled,
                        onBack = { driverPage = null },
                    )
                }
            }
            val from = driverFrom
            if (from == null) body() else PageFlood(from, driverLeaving) { body() }
        }
    }
}

/** The Installed rows of one component layer for the Proton the page shows. */
@Composable
private fun installedFor(l: String, view: ComponentsManager.ProtonView?, snapshot: Snapshot?): List<InstalledItem> {
    view ?: return emptyList()
    val st = view.components[l] ?: return emptyList()
    val build = ComponentsManager.safeName(view.proton.version)
    val activeIsOriginal = st.activeFile == null
    return view.originals.filter { it.comp == l }.map { o ->
        val isCurrent = o.protonVersion == build
        InstalledItem(
            stringResource(R.string.comp_original, o.protonVersion),
            o.label.substringAfterLast(" · ", "").ifEmpty { o.label }.let { if (isCurrent) stringResource(R.string.comp_this_build, it) else stringResource(R.string.comp_earlier_build, it) },
            "ORIGINAL", activeIsOriginal && isCurrent, !isCurrent, null, o.protonVersion,
        )
    } + (snapshot?.packages ?: emptyList()).filter { it.comp == l }.map { s ->
        InstalledItem(s.version, stringResource(R.string.common_size_mb, s.size / 1048576.0), "STORED", s.file == st.activeFile, true, s.file, null)
    }
}

/** The Nightlies of one component layer that are not stored yet. */
private fun availableFor(l: String, catalog: List<CatalogItem>, snapshot: Snapshot?): List<CatalogItem> {
    val stored = (snapshot?.packages ?: emptyList()).map { it.file }.toSet()
    return catalog.filter { it.comp == l && ComponentsManager.safeName(it.file) !in stored }
}

@Composable
private fun LayerTitle(l: String): String = when (l) {
    GPU_TAB -> stringResource(R.string.comp_gpu_drivers)
    PROTON_TAB -> stringResource(R.string.comp_proton)
    else -> ComponentsManager.LABEL[l] ?: l
}

/** Records where a row sits, for the confirm that steps out of it and the dot that flies from it. */
private fun Modifier.trackRow(key: String, into: HashMap<String, Rect>): Modifier = onGloballyPositioned { into[key] = it.boundsInRoot() }

/**
 * The stack: a heading over the Proton and the three layers that belong to it (indented, on a
 * line), then a heading over the GPU drivers that every Proton shares. Each card is a layer's name
 * and what it is set to; a dot marks a layer swapped away from its original.
 */
@Composable
private fun Stack(
    layer: String, view: ComponentsManager.ProtonView?, gpu: GpuDriversState,
    focus: Map<String, FocusRequester>, landed: Map<String, Int>, rings: Map<String, Int>,
    onPlaced: (String, Rect) -> Unit, onFocus: (String) -> Unit, onOpen: (String) -> Unit,
) {
    val pal = LocalPalette.current
    val original = stringResource(R.string.comp_tag_original)
    val auto = stringResource(R.string.common_auto)
    val card: @Composable (String) -> Unit = { l ->
        val st = view?.components?.get(l)
        LayerCard(
            title = LayerTitle(l),
            value = when (l) {
                GPU_TAB -> if (gpu.auto) auto else gpu.activePair?.name ?: gpu.activeBundle ?: auto
                PROTON_TAB -> (view?.proton?.name ?: "") + " ▾"
                else -> st?.let { if (it.activeFile == null) original else it.inUse } ?: ""
            },
            swapped = l in ComponentsManager.COMPONENTS && st?.activeFile != null,
            selected = l == layer,
            landed = landed[l] ?: 0, ring = rings[l] ?: 0,
            modifier = Modifier.focusRequester(focus.getValue(l)).onGloballyPositioned { onPlaced(l, it.boundsInRoot()) },
            onFocus = { onFocus(l) }, onClick = { onOpen(l) },
        )
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        StackHeading(stringResource(R.string.comp_this_proton))
        card(PROTON_TAB)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).width(2.dp).fillMaxHeight().background(pal.line2))
            Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                card("dxvk"); card("vkd3d"); card("fex")
            }
        }
        Spacer(Modifier.height(6.dp))
        StackHeading(stringResource(R.string.comp_every_proton))
        card(GPU_TAB)
    }
}

@Composable
private fun StackHeading(text: String) {
    Text(
        text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** One layer: 40dp to see, 44 to touch; its name, what it is set to, and a dot when it is swapped. */
@Composable
private fun LayerCard(
    title: String, value: String, swapped: Boolean, selected: Boolean, landed: Int, ring: Int,
    modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.fillMaxWidth().height(40.dp)
                .landingPop(landed).pulseRing(ring, pal.signal)
                .clip(LAYER_SHAPE)
                .background(if (selected) Color.Transparent else colors.surface)
                .glideBorder(hot, LAYER_SHAPE, pal.signal, if (selected) Color.Transparent else pal.line)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Tab, onClick = onClick)
                .controllerConfirm(onClick = onClick)
                .padding(horizontal = 12.dp),
        ) {
            if (swapped) Box(Modifier.size(7.dp).clip(CircleShape).background(pal.signal))
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = if (selected || hot) colors.onBackground else colors.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.weight(1f))
            // The value ticks over as a swap lands.
            AnimatedContent(
                value,
                transitionSpec = { (slideInVertically(Motion.tw(260)) { it / 2 } + fadeIn(Motion.tw(260))) togetherWith (slideOutVertically(Motion.tw(160)) { -it / 2 } + fadeOut(Motion.tw(160))) },
                label = "layerValue",
            ) { v -> Text(v, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** What is in use for the layer: a dot (green, or amber while a change waits), a sentence, and a note or Cancel. */
@Composable
private fun InUseBar(queued: Boolean, sentence: String, note: String, onCancel: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
            .border(1.dp, pal.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (queued) QUEUED else pal.good, CircleShape))
        Text(sentence, fontSize = 14.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onCancel != null) FocusText(stringResource(R.string.common_cancel), pal.signal, onClick = onCancel)
        else if (note.isNotEmpty() && !LocalNarrowPane.current) Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A titled column of rows. */
@Composable
private fun RowGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = colors.onSurfaceVariant, maxLines = 1)
            Box(Modifier.weight(1f).height(1.dp).background(pal.line))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surface).border(1.dp, pal.line, RoundedCornerShape(14.dp)), content = content)
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
}

@Composable
internal fun ToolIcon(icon: ImageVector, description: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
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
    val hot = rememberHot(src) && enabled
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

/**
 * One installed choice: a radio for the one in use, its name and detail, and a trash at the right
 * of the ones that can go. [progress] shows a download for it in place of the tag.
 */
@Composable
private fun ChoiceLine(
    name: String, detail: String, selected: Boolean, removable: Boolean,
    modifier: Modifier = Modifier, tag: String? = null, badge: String? = null, progress: Int? = null,
    onSelect: () -> Unit, onDelete: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (selected) pal.signal.copy(alpha = 0.07f) else Color.Transparent),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).then(modifier)
                .glideBorder(hot, ROW_SHAPE, pal.signal)
                .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.RadioButton, onClick = onSelect)
                .controllerConfirm(onClick = onSelect)
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).border(2.dp, if (selected) pal.signal else colors.onSurfaceVariant, CircleShape)) {
                if (selected) Box(Modifier.size(9.dp).background(pal.signal, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (selected) pal.signal else colors.onBackground, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tag != null) Tag(tag)
                }
                DetailLine(detail, badge)
            }
            // The radio says which is in use; a download in flight shows where a tag would.
            if (progress != null) ProgressBit(progress)
        }
        // The trash column is always there (empty when the row can't be deleted) so rows line up.
        if (!removable) Spacer(Modifier.padding(end = 6.dp).size(44.dp))
        else {
            val delSrc = remember { MutableInteractionSource() }
            val delHot = rememberHot(delSrc)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(end = 6.dp).size(44.dp).clip(ROW_SHAPE)
                    .background(if (delHot) colors.error.copy(alpha = 0.14f) else Color.Transparent)
                    .glideBorder(delHot, ROW_SHAPE, colors.error)
                    .hoverable(delSrc).clickable(interactionSource = delSrc, indication = null, onClick = onDelete)
                    .controllerConfirm(onClick = onDelete),
            ) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete_named, name), tint = if (delHot) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/** One thing not installed yet: its name and detail, and Get - its download shown in the row once started. */
@Composable
private fun GetLine(
    name: String, detail: String, progress: Int?, enabled: Boolean,
    badge: String? = null, stage: String? = null, onCancel: (() -> Unit)? = null, onGet: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DetailLine(stage ?: detail, badge)
        }
        if (progress != null) ProgressBit(progress)
        else {
            if (onCancel != null) FocusText(stringResource(R.string.proton_cancel_queued), pal.signal, onClick = onCancel)
            SmallButton(stringResource(R.string.comp_get), enabled = enabled, accent = true, onClick = onGet)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/** A row's second line, led by [badge] (Recommended) in green when there is one. */
@Composable
private fun DetailLine(detail: String, badge: String?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val text = androidx.compose.ui.text.buildAnnotatedString {
        if (badge != null) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = pal.good, fontWeight = FontWeight.SemiBold))
            append(badge.lowercase().replaceFirstChar { it.uppercase() })
            pop()
            append(" · ")
        }
        append(detail)
    }
    Text(text, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun ProgressBit(progress: Int) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
        Text(if (progress < 0) "…" else "$progress%", fontSize = 13.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        if (progress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The GPU and how far it is supported, as a chip: a green dot and "<GPU> · tested", amber when it
 * is not. A steps the device's full details out of it, with the copy for a bug report.
 */
@Composable
private fun DeviceChip(s: GpuDriversState) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint = if (s.supported && !s.unsupported) pal.good else AttentionAmber
    val label = stringResource(
        when {
            s.unsupported -> R.string.comp_device_unsupported
            s.supported -> R.string.comp_device_tested
            else -> R.string.comp_device_untested
        }, s.gpuName,
    )
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    var at by remember { mutableStateOf<Rect?>(null) }
    val shape = RoundedCornerShape(99.dp)
    val face: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val open: () -> Unit = {
        at?.let { from ->
            Steps.ask(StepAsk(anchor = from, side = StepSide.Right, accent = pal.signal, items = 2, maxWidth = 420.dp, handle = face) {
                Box(Modifier.stepItem(0)) { DeviceDetails(first) }
            })
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.onGloballyPositioned { at = it.boundsInRoot() }.heightIn(min = 44.dp).paneItem("gpu-device").clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = null, role = Role.Button, onClick = open)
            .controllerConfirm(onClick = open)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { face() }
}

/** What the Proton layer installs: the Protons DroidDeck can download, and one being installed. */
class ProtonsState(
    val rows: List<ProtonRow> = emptyList(),
    val busyId: String? = null,
    val stage: String? = null,
    val percent: Int = -1,
    val runtimeReady: Boolean = true,
    val sessionRunning: Boolean = false,
)

class ProtonsActions(
    val onInstall: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
)

/** A Proton DroidDeck can install, and the folder it is installed as (null when it is not). */
class ProtonRow(val id: String, val name: String, val installed: String?, val queued: Boolean)
