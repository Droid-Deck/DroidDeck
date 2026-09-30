package com.droiddeck.launcher.ui

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

private val TABS = listOf("Discover", "Search", "Installed")

@Composable
internal fun StorePage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 16.dp else 22.dp
    val padV = if (narrow) 12.dp else 18.dp
    var tab by rememberSaveable { mutableStateOf(0) }
    var openApp by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(s.ready) { StoreState.refresh(ctx) }
    LaunchedEffect(Unit) { StoreState.loadSections() }
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
            PageHeader("Store") {
                Chip("Flathub · ARM64", ok = false)
                if (StoreState.ready) Chip("● Flatpak ready", ok = true)
            }
        }
        if (!s.ready) {
            Rise(1) { Note("Install the Linux runtime from Setup first; the store installs apps into it.") }
            return@Column
        }
        if (!StoreState.ready) Rise(1) { SetupCard() }
        Rise(2) {
            TabStrip(
                TABS.mapIndexed { i, t -> if (i == 2 && StoreState.installed.isNotEmpty()) "$t · ${StoreState.installed.size}" else t },
                tab, { switchTab(it) }, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp), focusRequesters = tabFocus,
            )
        }
        BusyBar()
        when (tab) {
            0 -> Discover(onOpen = { openApp = it }, onCategory = { category = it; query = ""; tab = 1; StoreState.search("", it) })
            1 -> Search(query, category, { query = it }, { category = it }, onOpen = { openApp = it })
            else -> Installed(a, onOpen = { openApp = it })
        }
    }
}

/** Flatpak is not in the runtime yet: one button puts it there. */
@Composable
private fun SetupCard() {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val busy = StoreState.busy == "setup"
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(16.dp),
    ) {
        Text("Set up Flatpak", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Text(
            "Adds Flatpak to the Linux runtime and connects it to Flathub, about 20 MB. Apps then install " +
                "with the runtimes they need; the first one is the biggest download.",
            fontSize = 14.sp, color = colors.onSurfaceVariant,
        )
        Actions {
            PrimaryButton(if (busy) "Setting up…" else "Set up Flatpak", enabled = StoreState.busy == null, main = true) { StoreState.setup(ctx) }
        }
    }
}

/** The running install, update or setup, whichever page it was started from. */
@Composable
private fun BusyBar() {
    val colors = MaterialTheme.colorScheme
    val busy = StoreState.busy ?: return
    val name = StoreState.installed.firstOrNull { it.id == busy }?.name ?: StoreState.details[busy]?.name
        ?: when (busy) { "setup" -> "Flatpak"; "update-all" -> "Updates"; else -> busy.substringAfterLast('.') }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        val stage = StoreState.stage
        Text(
            "$name · " + (if (stage != null && StoreState.percent >= 0) "$stage · ${StoreState.percent}%" else stage ?: "Starting…"),
            fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (StoreState.percent >= 0) LinearProgressIndicator(progress = { StoreState.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
    }
}

@Composable
private fun Discover(onOpen: (String) -> Unit, onCategory: (String) -> Unit) {
    Rise(3) { SectionTitle("Categories", null) }
    Rise(3) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            FlathubApi.categories.forEach { c -> PillButton(c.label, selected = false) { onCategory(c.id) } }
        }
    }
    if (StoreState.sectionsFailed && StoreState.sections.values.all { it.isEmpty() }) {
        Rise(4) { Note("Flathub could not be reached. Check the connection and open the Store again.") }
        Actions { SecondaryButton("Try again") { StoreState.loadSections(force = true) } }
        return
    }
    StoreState.SECTIONS.forEachIndexed { i, (key, title) ->
        val apps = StoreState.sections[key]
        Rise(4 + i) { SectionTitle(title, apps?.let { if (it.isEmpty()) null else it.size.toString() } ?: "loading…") }
        if (apps != null && apps.isNotEmpty()) Rise(4 + i) { AppGrid(apps.take(12), first = i == 0, onOpen = onOpen) }
    }
}

@Composable
private fun Search(query: String, category: String?, onQuery: (String) -> Unit, onCategory: (String?) -> Unit, onOpen: (String) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val run = {
        // The field is an Android EditText; Done leaves its keyboard over the results otherwise.
        (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        StoreState.search(query.trim(), category)
    }
    Rise(3) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            AdbTextField(
                value = query, onValueChange = onQuery, label = "Search Flathub", keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done, placeholder = "Firefox, games, emulators…", modifier = Modifier.weight(1f), onDone = run,
            )
            PrimaryButton("Search", main = true, onClick = run)
        }
    }
    Rise(4) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 4.dp)) {
            PillButton("All", selected = category == null) { onCategory(null); StoreState.search(query.trim(), null) }
            FlathubApi.categories.forEach { c ->
                PillButton(c.label, selected = category == c.id) { onCategory(c.id); StoreState.search(query.trim(), c.id) }
            }
        }
    }
    val results = StoreState.searchResults
    when {
        StoreState.searching -> Rise(5) { SectionTitle("Searching…", null) }
        results == null -> Rise(5) { Note("Search Flathub by name, or pick a category. Only apps built for ARM64 are listed.") }
        results.isEmpty() -> Rise(5) { Note("Nothing on Flathub for ARM64 matches that.") }
        else -> {
            Rise(5) { SectionTitle("Results", results.size.toString()) }
            Rise(6) { AppGrid(results, first = true, onOpen = onOpen) }
        }
    }
}

@Composable
private fun Installed(a: FrontEndActions, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val apps = StoreState.installed
    Rise(3) {
        Actions {
            SecondaryButton(if (StoreState.checkingUpdates) "Checking…" else "Check for updates", enabled = StoreState.ready && !StoreState.checkingUpdates && StoreState.busy == null) {
                StoreState.checkUpdates(ctx)
            }
            if (StoreState.updates.isNotEmpty()) PrimaryButton("Update all (${StoreState.updates.size})", enabled = StoreState.busy == null) {
                StoreState.update(ctx, null, "Updates")
            } else if (StoreState.updatesChecked && !StoreState.checkingUpdates) ActionChip("● Everything is up to date", ok = true)
        }
    }
    if (apps.isEmpty()) {
        Rise(4) { Box(Modifier.padding(top = 12.dp)) { Note(if (StoreState.ready) "No apps yet. Find some under Discover or Search." else "Set up Flatpak to install apps.") } }
        return
    }
    Rise(4) { SectionTitle("Installed", apps.size.toString()) }
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
                                (if (app.id in StoreState.updates) "Update available · " else "") + (app.summary ?: app.id),
                                fontSize = 13.sp, color = if (app.id in StoreState.updates) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        PrimaryButton("Open", compact = true, enabled = !s_busyFor(app.id)) { a.onFlatpakApp(app.id, app.name) }
                        if (app.id in StoreState.updates) SecondaryButton("Update", compact = true, enabled = StoreState.busy == null) { StoreState.update(ctx, app.id, app.name) }
                        SecondaryButton("Details", compact = true) { onOpen(app.id) }
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
    LaunchedEffect(id) { StoreState.loadDetails(id) }
    val d = StoreState.details[id]
    val local = StoreState.installed.firstOrNull { it.id == id }
    val name = d?.name ?: local?.name ?: id
    Rise(0) { BackLink("Store", onClick = onBack) }
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
                PrimaryButton("Open", enabled = !busyHere, main = true) { a.onFlatpakApp(id, name) }
                if (id in StoreState.updates) SecondaryButton("Update", enabled = StoreState.busy == null) { StoreState.update(ctx, id, name) }
                SecondaryButton(if (busyHere) "Working…" else if (confirmRemove) "Press again to remove" else "Remove", enabled = StoreState.busy == null) {
                    if (confirmRemove) { confirmRemove = false; StoreState.uninstall(ctx, id, name) } else confirmRemove = true
                }
            } else {
                PrimaryButton(
                    if (busyHere) "Installing…" else "Install", main = true,
                    enabled = StoreState.ready && StoreState.busy == null && s.ready && (d == null || d.arches.isEmpty() || "aarch64" in d.arches),
                ) { StoreState.install(ctx, id, name) }
                if (!StoreState.ready) ActionChip("Set up Flatpak first", ok = false)
            }
            d?.version?.let { ActionChip("v$it", ok = false) }
            if (d != null && d.downloadSize > 0) ActionChip("${FileUtils.sizeToString(d.downloadSize)} download", ok = false)
            if (installed) ActionChip("● Installed", ok = true)
        }
    }
    if (busyHere) BusyBar()
    if (d == null) {
        Rise(3) {
            Box(Modifier.padding(top = 14.dp)) {
                Note(if (StoreState.detailsFailed[id] == true) "Flathub's page for this app could not be loaded." else "Loading from Flathub…")
            }
        }
        return
    }
    if (d.arches.isNotEmpty() && "aarch64" !in d.arches) Rise(3) { Box(Modifier.padding(top = 12.dp)) { Note("Flathub has no ARM64 build of this app, so it cannot be installed here.") } }
    if (d.screenshots.isNotEmpty()) {
        Rise(3) { SectionTitle("Screenshots", null) }
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
        Rise(4) { SectionTitle("About", null) }
        Rise(4) { Text(d.description, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onBackground, modifier = Modifier.fillMaxWidth()) }
    }
    Rise(5) { SectionTitle("Details", null) }
    Rise(5) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            listOfNotNull(
                "App ID" to d.id,
                d.license?.let { "License" to it },
                d.runtime?.let { "Runtime" to it.replace("/x86_64/", "/aarch64/") },
                if (d.installedSize > 0) "Installed size" to FileUtils.sizeToString(d.installedSize) + " (plus its runtime, shared between apps)" else null,
                d.homepage?.let { "Website" to it },
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
                if (app.verified) Icon(Icons.Outlined.Verified, contentDescription = "Verified", tint = pal.signal, modifier = Modifier.size(14.dp))
            }
            Text(if (installed) "Installed" else app.summary, fontSize = 13.sp, color = if (installed) pal.good else colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An app's icon from Flathub (a URL) or from its installed files; a placeholder until it loads. */
@Composable
private fun AppIcon(model: Any?, name: String, size: Int) {
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

/** The apps installed from the Store, as launch tiles - on the Desktop page beside the emulators. */
@Composable
internal fun InstalledAppsGrid(a: FrontEndActions) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { StoreState.refresh(ctx) }
    val apps = StoreState.installed
    if (apps.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val columns = if (LocalNarrowPane.current) 1 else 3
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionTitle("Apps from the Store", apps.size.toString())
        apps.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEach { app ->
                    key(app.id) {
                        val src = remember { MutableInteractionSource() }
                        val hot = rememberHot(src)
                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f).fillMaxHeight().paneItem("tile:flatpak:${app.id}")
                                .clip(Shape14).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
                                .glideBorder(hot, Shape14, pal.signal, pal.line)
                                .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { a.onFlatpakApp(app.id, app.name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            AppIcon(app.icon ?: StoreState.details[app.id]?.icon ?: FlathubApi.iconUrl(app.id), app.name, 44)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.summary ?: "Flatpak", fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The user's own AppImages on the Desktop page: an Add button, each one to open, and Remove
 * (pressed twice, like the store's). The picker, the check and the extraction live elsewhere
 * (MainActivity, AppImageManager); this only shows them.
 */
@Composable
internal fun AppImagesSection(a: FrontEndActions, runtimeReady: Boolean) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { com.droiddeck.launcher.store.AppImageState.refresh(ctx) }
    val state = com.droiddeck.launcher.store.AppImageState
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionTitle("AppImages", state.items.size.takeIf { it > 0 }?.toString())
        Actions {
            SecondaryButton(if (state.importing != null) "Importing…" else "Add AppImage", enabled = runtimeReady && state.importing == null) { a.onImportAppImage() }
            if (!runtimeReady) ActionChip("Runtime required", ok = false)
        }
        state.importing?.let { name ->
            Text("$name · ${state.stage ?: "Starting…"}", fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        }
        state.lastError?.let { Note(it) }
        if (state.items.isEmpty() && state.importing == null && state.lastError == null) {
            Note("Add an ARM64 (aarch64) AppImage from your storage. It is unpacked into the Linux runtime once, then opens full screen from here or from the desktop's menu.")
        }
        state.items.forEach { item ->
            key(item.id) {
                var confirm by remember(item.id) { mutableStateOf(false) }
                LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
                val src = remember { MutableInteractionSource() }
                val hot = rememberHot(src)
                Row(
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(end = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f).paneItem("tile:appimage:${item.id}").clip(Shape14)
                            .background(if (hot) pal.signal.copy(alpha = 0.10f) else Color.Transparent)
                            .glideBorder(hot, Shape14, pal.signal)
                            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { a.onAppImage(item.guestDir, item.name) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        AppIcon(item.icon, item.name, 44)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.comment ?: "AppImage", fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    SecondaryButton(if (confirm) "Press again to remove" else "Remove", compact = true) {
                        if (confirm) { confirm = false; com.droiddeck.launcher.store.AppImageState.remove(ctx, item.id) } else confirm = true
                    }
                }
            }
        }
    }
}
