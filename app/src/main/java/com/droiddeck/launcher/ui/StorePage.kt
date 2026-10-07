package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.LocalInspectionMode
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.store.FlathubApi
import com.droiddeck.launcher.store.StoreState

// The Store rail section: Flathub's apps for this device's architecture, installed into the
// Linux runtime with Flatpak, and the ones already installed.

private val TABS = listOf(R.string.store_tab_discover, R.string.store_tab_search, R.string.store_tab_installed)

@Composable
internal fun StorePage(s: FrontEndState, a: FrontEndActions, modifier: Modifier, previewSections: Map<String, List<FlathubApi.AppSummary>>? = null) {
    val ctx = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val sections = previewSections.takeIf { inspecting } ?: StoreState.sections
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 16.dp else 22.dp
    val padV = if (narrow) 12.dp else 18.dp
    var tab by rememberSaveable { mutableStateOf(0) }
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(s.ready) { if (!inspecting) StoreState.refresh(ctx) }
    LaunchedEffect(Unit) { if (!inspecting) StoreState.loadSections() }
    // A pad's focus sits on something the page is about to replace - the tile that opens an app,
    // a tab's contents - and would be lost with it. Each move says where focus goes next.
    val ff = LocalFrontFocus.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val tabFocus = remember { List(TABS.size) { androidx.compose.ui.focus.FocusRequester() } }
    var focusMove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var lastApp by remember { mutableStateOf<String?>(null) }
    fun closeApp() { lastApp = openApp; openApp = null; focusMove = "tile" to (focusMove?.second ?: 0) + 1 }
    fun switchTab(to: Int) { tab = to; focusMove = "tab" to (focusMove?.second ?: 0) + 1 }
    BackHandler(enabled = openApp != null) { closeApp() }
    val scroll = rememberScrollState()
    LaunchedEffect(openApp, tab) { scroll.scrollTo(0) }
    LaunchedEffect(focusMove, openApp) {
        if (inputMode.inputMode != androidx.compose.ui.input.InputMode.Keyboard) return@LaunchedEffect
        val target = when {
            openApp != null -> ff?.primary
            focusMove?.first == "tile" -> lastApp?.let { ff?.items?.get("tile:store:$it") } ?: tabFocus[tab]
            focusMove?.first == "tab" -> tabFocus[tab]
            else -> null
        } ?: return@LaunchedEffect
        var landed = false
        focusWithinFrames({ landed }) { target.also { landed = runCatching { it.requestFocus() }.isSuccess } }
    }
    Column(
        modifier = modifier.verticalScroll(scroll).padding(horizontal = padH, vertical = padV)
            .bumpers(onPrevious = { if (openApp == null) switchTab((tab + TABS.size - 1) % TABS.size) },
                     onNext = { if (openApp == null) switchTab((tab + 1) % TABS.size) }),
    ) {
        val id = openApp
        if (id != null) {
            AppDetail(s, a, id) { closeApp() }
            return@Column
        }
        Rise(0) {
            PageHeader(stringResource(R.string.store_title)) {
                Chip(stringResource(R.string.store_chip_flathub), ok = false)
                if (StoreState.ready) Chip(stringResource(R.string.store_chip_ready), ok = true)
            }
        }
        if (!s.ready) {
            Rise(1) { Note(stringResource(R.string.store_needs_runtime)) }
            return@Column
        }
        if (!StoreState.ready) Rise(1) { SetupCard() }
        Rise(2) {
            TabStrip(
                TABS.mapIndexed { i, id -> stringResource(id).let { t -> if (i == 2 && StoreState.installed.isNotEmpty()) stringResource(R.string.store_tab_count, t, StoreState.installed.size) else t } },
                tab, { switchTab(it) }, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp), focusRequesters = tabFocus,
            )
        }
        BusyBar()
        when (tab) {
            0 -> Discover(onOpen = { openApp = it }, onCategory = { category = it; query = ""; tab = 1; if (!inspecting) StoreState.search("", it) }, sections = sections)
            1 -> Search(query, category, { query = it }, { category = it }, onOpen = { openApp = it })
            else -> Installed(a, onOpen = { openApp = it })
        }
    }
}

/** Flatpak is not in the runtime yet: one button puts it there. */
@Composable
private fun SetupCard() {
    val inspecting = LocalInspectionMode.current
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val busy = StoreState.busy == "setup"
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(16.dp),
    ) {
        Text(stringResource(R.string.store_setup_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Text(
            stringResource(R.string.store_setup_text),
            fontSize = 14.sp, color = colors.onSurfaceVariant,
        )
        Actions {
            PrimaryButton(if (busy) stringResource(R.string.store_setting_up) else stringResource(R.string.store_setup_title), enabled = StoreState.busy == null, main = true) { if (!inspecting) StoreState.setup(ctx) }
        }
    }
}

/** The running install, update or setup, whichever page it was started from. */
@Composable
private fun BusyBar() {
    val colors = MaterialTheme.colorScheme
    val busy = StoreState.busy ?: return
    val name = StoreState.installed.firstOrNull { it.id == busy }?.name ?: StoreState.details[busy]?.name
        ?: when (busy) { "setup" -> stringResource(R.string.store_flatpak); "update-all" -> stringResource(R.string.store_updates); else -> busy.substringAfterLast('.') }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        val stage = StoreState.stage
        Text(
            if (stage != null && StoreState.percent >= 0) stringResource(R.string.store_busy_percent, name, stage, StoreState.percent) else stringResource(R.string.store_busy, name, stage ?: stringResource(R.string.store_starting)),
            fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (StoreState.percent >= 0) LinearProgressIndicator(progress = { StoreState.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
    }
}

@Composable
private fun Discover(onOpen: (String) -> Unit, onCategory: (String) -> Unit, sections: Map<String, List<FlathubApi.AppSummary>>) {
    val inspecting = LocalInspectionMode.current
    Rise(3) { SectionTitle(stringResource(R.string.store_categories), null) }
    Rise(3) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            FlathubApi.categories.forEach { c -> PillButton(stringResource(c.label), selected = false) { onCategory(c.id) } }
        }
    }
    if (StoreState.sectionsFailed && sections.values.all { it.isEmpty() }) {
        Rise(4) { Note(stringResource(R.string.store_unreachable)) }
        Actions { SecondaryButton(stringResource(R.string.store_try_again)) { if (!inspecting) StoreState.loadSections(force = true) } }
        return
    }
    StoreState.SECTIONS.forEachIndexed { i, (key, title) ->
        val apps = sections[key]
        Rise(4 + i) { SectionTitle(stringResource(title), apps?.let { if (it.isEmpty()) null else it.size.toString() } ?: stringResource(R.string.store_section_loading)) }
        if (apps != null && apps.isNotEmpty()) Rise(4 + i) { AppGrid(apps.take(12), first = i == 0, onOpen = onOpen) }
    }
}

@Composable
private fun Search(query: String, category: String?, onQuery: (String) -> Unit, onCategory: (String?) -> Unit, onOpen: (String) -> Unit) {
    val inspecting = LocalInspectionMode.current
    val view = androidx.compose.ui.platform.LocalView.current
    val run = {
        // The field is an Android EditText; Done leaves its keyboard over the results otherwise.
        (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        if (!inspecting) StoreState.search(query.trim(), category)
    }
    Rise(3) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            AdbTextField(
                value = query, onValueChange = onQuery, label = stringResource(R.string.store_search_label), keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done, placeholder = stringResource(R.string.store_search_placeholder), modifier = Modifier.weight(1f), onDone = run,
            )
            PrimaryButton(stringResource(R.string.store_search), main = true, onClick = run)
        }
    }
    Rise(4) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 4.dp)) {
            PillButton(stringResource(R.string.store_all), selected = category == null) { onCategory(null); if (!inspecting) StoreState.search(query.trim(), null) }
            FlathubApi.categories.forEach { c ->
                PillButton(stringResource(c.label), selected = category == c.id) { onCategory(c.id); if (!inspecting) StoreState.search(query.trim(), c.id) }
            }
        }
    }
    val results = StoreState.searchResults
    when {
        StoreState.searching -> Rise(5) { SectionTitle(stringResource(R.string.store_searching), null) }
        results == null -> Rise(5) { Note(stringResource(R.string.store_search_hint)) }
        results.isEmpty() -> Rise(5) { Note(stringResource(R.string.store_no_results)) }
        else -> {
            Rise(5) { SectionTitle(stringResource(R.string.store_results), results.size.toString()) }
            Rise(6) { AppGrid(results, first = true, onOpen = onOpen) }
        }
    }
}

@Composable
private fun Installed(a: FrontEndActions, onOpen: (String) -> Unit) {
    val inspecting = LocalInspectionMode.current
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val apps = StoreState.installed
    Rise(3) {
        Actions {
            SecondaryButton(if (StoreState.checkingUpdates) stringResource(R.string.common_checking) else stringResource(R.string.store_check_updates), enabled = StoreState.ready && !StoreState.checkingUpdates && StoreState.busy == null) {
                if (!inspecting) StoreState.checkUpdates(ctx)
            }
            if (StoreState.updates.isNotEmpty()) PrimaryButton(stringResource(R.string.store_update_all, StoreState.updates.size), enabled = StoreState.busy == null) {
                if (!inspecting) StoreState.update(ctx, null, ctx.getString(R.string.store_updates))
            } else if (StoreState.updatesChecked && !StoreState.checkingUpdates) ActionChip(stringResource(R.string.store_all_current), ok = true)
        }
    }
    if (apps.isEmpty()) {
        Rise(4) { Box(Modifier.padding(top = 12.dp)) { Note(if (StoreState.ready) stringResource(R.string.store_no_apps) else stringResource(R.string.store_setup_first_note)) } }
        return
    }
    Rise(4) { SectionTitle(stringResource(R.string.store_tab_installed), apps.size.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        apps.forEachIndexed { i, app ->
            key(app.id) {
                Rise(5 + i.coerceAtMost(6)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        AppIcon(app.icon ?: StoreState.details[app.id]?.icon ?: FlathubApi.iconUrl(app.id), app.name, 44)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (app.summary ?: app.id).let { if (app.id in StoreState.updates) stringResource(R.string.store_update_available_prefix, it) else it },
                                fontSize = 13.sp, color = if (app.id in StoreState.updates) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        PrimaryButton(stringResource(R.string.store_open), compact = true, enabled = !s_busyFor(app.id)) { a.onFlatpakApp(app.id, app.name) }
                        if (app.id in StoreState.updates) SecondaryButton(stringResource(R.string.common_update), compact = true, enabled = StoreState.busy == null) { if (!inspecting) StoreState.update(ctx, app.id, app.name) }
                        SecondaryButton(stringResource(R.string.store_details), compact = true) { onOpen(app.id) }
                    }
                }
            }
        }
    }
}

private fun s_busyFor(id: String) = StoreState.busy == id

@Composable
private fun AppDetail(s: FrontEndState, a: FrontEndActions, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val inspecting = LocalInspectionMode.current
    LaunchedEffect(id) { if (!inspecting) StoreState.loadDetails(id) }
    val d = StoreState.details[id]
    val local = StoreState.installed.firstOrNull { it.id == id }
    val name = d?.name ?: local?.name ?: id
    Rise(0) { BackLink(stringResource(R.string.store_title), onClick = onBack) }
    Rise(1) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
            AppIcon(d?.icon ?: local?.icon ?: FlathubApi.iconUrl(id), name, 72)
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                d?.developer?.let { Text(it, fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                (d?.summary ?: local?.summary)?.let { Text(it, fontSize = 14.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
    val busyHere = StoreState.busy == id
    val installed = local != null
    // Remove asks twice: one stray press of A should not cost a download. The second press has
    // to come within a few seconds; the app's own data in ~/.var/app stays either way.
    var confirmRemove by remember(id) { mutableStateOf(false) }
    LaunchedEffect(confirmRemove) { if (confirmRemove) { kotlinx.coroutines.delay(4000); confirmRemove = false } }
    Rise(2) {
        Actions {
            if (installed) {
                PrimaryButton(stringResource(R.string.store_open), enabled = !busyHere, main = true) { a.onFlatpakApp(id, name) }
                if (id in StoreState.updates) SecondaryButton(stringResource(R.string.common_update), enabled = StoreState.busy == null) { if (!inspecting) StoreState.update(ctx, id, name) }
                SecondaryButton(if (busyHere) stringResource(R.string.common_working) else if (confirmRemove) stringResource(R.string.store_press_again) else stringResource(R.string.store_remove), enabled = StoreState.busy == null) {
                    if (confirmRemove) { confirmRemove = false; if (!inspecting) StoreState.uninstall(ctx, id, name) } else confirmRemove = true
                }
            } else {
                PrimaryButton(
                    if (busyHere) stringResource(R.string.store_installing) else stringResource(R.string.store_install), main = true,
                    enabled = StoreState.ready && StoreState.busy == null && s.ready && (d == null || d.arches.isEmpty() || "aarch64" in d.arches),
                ) { if (!inspecting) StoreState.install(ctx, id, name) }
                if (!StoreState.ready) ActionChip(stringResource(R.string.store_setup_first), ok = false)
            }
            d?.version?.let { ActionChip("v$it", ok = false) }
            if (d != null && d.downloadSize > 0) ActionChip(stringResource(R.string.store_download_size, FileUtils.sizeToString(d.downloadSize)), ok = false)
            if (installed) ActionChip(stringResource(R.string.store_installed_chip), ok = true)
        }
    }
    if (busyHere) BusyBar()
    if (d == null) {
        Rise(3) {
            Box(Modifier.padding(top = 14.dp)) {
                Note(if (StoreState.detailsFailed[id] == true) stringResource(R.string.store_details_failed) else stringResource(R.string.store_loading))
            }
        }
        return
    }
    if (d.arches.isNotEmpty() && "aarch64" !in d.arches) Rise(3) { Box(Modifier.padding(top = 12.dp)) { Note(stringResource(R.string.store_no_arm)) } }
    if (d.screenshots.isNotEmpty()) {
        Rise(3) { SectionTitle(stringResource(R.string.store_screenshots), null) }
        Rise(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                d.screenshots.take(6).forEach { shot ->
                    AsyncImage(
                        model = shot.thumb, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.height(if (narrow) 150.dp else 190.dp).aspectRatio(16f / 9f).clip(Shape12).background(colors.surface),
                    )
                }
            }
        }
    }
    if (d.description.isNotEmpty()) {
        Rise(4) { SectionTitle(stringResource(R.string.store_about), null) }
        Rise(4) { Text(d.description, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onBackground, modifier = Modifier.fillMaxWidth()) }
    }
    Rise(5) { SectionTitle(stringResource(R.string.store_details), null) }
    Rise(5) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            listOfNotNull(
                stringResource(R.string.store_app_id) to d.id,
                d.license?.let { stringResource(R.string.store_license) to it },
                d.runtime?.let { stringResource(R.string.store_runtime) to it.replace("/x86_64/", "/aarch64/") },
                if (d.installedSize > 0) stringResource(R.string.store_installed_size) to stringResource(R.string.store_installed_size_value, FileUtils.sizeToString(d.installedSize)) else null,
                d.homepage?.let { stringResource(R.string.store_website) to it },
            ).forEach { (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(110.dp))
                    Text(v, fontSize = 13.sp, color = colors.onBackground, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Apps as wide tiles: icon, name, summary - three across, or a list on a narrow page. */
@Composable
private fun AppGrid(apps: List<FlathubApi.AppSummary>, first: Boolean, onOpen: (String) -> Unit) {
    val columns = if (LocalNarrowPane.current) 1 else 3
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        apps.chunked(columns).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { i, app ->
                    key(app.id) {
                        AppTile(app, Modifier.weight(1f).fillMaxHeight(), isFirst = first && r == 0 && i == 0) { onOpen(app.id) }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppTile(app: FlathubApi.AppSummary, modifier: Modifier, isFirst: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "appScale")
    val installed = StoreState.installed.any { it.id == app.id }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.paneItem("tile:store:${app.id}").then(if (isFirst) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, Shape14, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        AppIcon(app.icon, app.name, 44)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (app.verified) Icon(Icons.Outlined.Verified, contentDescription = stringResource(R.string.store_verified), tint = pal.signal, modifier = Modifier.size(14.dp))
            }
            Text(if (installed) stringResource(R.string.store_tab_installed) else app.summary, fontSize = 13.sp, color = if (installed) pal.good else colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An app's icon from Flathub (a URL) or from its installed files; a placeholder until it loads. */
@Composable
internal fun AppIcon(model: Any?, name: String, size: Int) {
    val colors = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(if (model == null) colors.surfaceVariant else Color.Transparent)) {
        if (model == null) Icon(Icons.Outlined.Apps, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size((size / 2).dp))
        else AsyncImage(model = model, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.size(size.dp))
    }
}

/** A category pill: small, focusable, and filled when it is the one in use. */
@Composable
private fun PillButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (selected) pal.onSignal else if (hot) colors.onBackground else colors.onSurfaceVariant,
        modifier = Modifier.paneItem("pill:$label").clip(shape)
            .background(if (selected) pal.signal else if (hot) pal.signal.copy(alpha = 0.16f) else colors.surfaceVariant)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
