package com.droiddeck.launcher.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.CompositionLocalProvider
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.store.UserAppsState
import java.io.File

// The front end's right-hand pane and the page it shows: backdrop, desktop card and emulators.

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun Pane(
    s: FrontEndState, selected: String, a: FrontEndActions, page: (@Composable () -> Unit)?, modifier: Modifier,
    onSelect: (String) -> Unit, onAndroidAppClick: (HomeApp.LaunchableApp) -> Unit,
    onOpenDeveloperOptions: () -> Unit, onRequestWirelessAdb: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
      CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
        val backdropArt: File? = when {
            s.pageKey != null && page != null -> null
            selected.startsWith("app:") -> s.steamGames.firstOrNull { "app:${it.appId}" == selected }?.art
            selected == "games" -> s.steamGames.maxByOrNull { it.lastPlayed }?.art
            selected.startsWith("rom:") -> romFor(s, selected)?.second?.art
            else -> null
        }
        Backdrop(backdropArt)
        val pal = LocalPalette.current
        // Pages keep drawing while they leave, so one can fold away instead of blinking out.
        val pages = remember { HashMap<String, @Composable () -> Unit>() }
        // Pages opened from a control (the cog), with where that control sat, in this pane's coordinates.
        val origins = remember { HashMap<String, Origin>() }
        val paneAt = remember { arrayOf(Offset.Zero) }
        val livePage by rememberUpdatedState(s.pageKey)
        // Picking another game changes the detail beside the list, not the whole page.
        val target = if (page != null && s.pageKey != null) s.pageKey else if (selected.startsWith("app:")) "games" else selected
        // The game the Games page shows. It must outlive the selection: while the page animates out,
        // drawing it with what is selected now (another rail item) turned it into a copy of that page.
        val gameShown = remember { arrayOf("games") }
        if (selected == "games" || selected.startsWith("app:")) gameShown[0] = selected
        if (page != null && s.pageKey != null) {
            pages[s.pageKey] = page
            if (s.pageKey !in origins) PageOrigin.take()?.let { origins[s.pageKey] = it.translate(-paneAt[0]) }
        }
        AnimatedContent(
            targetState = target,
            modifier = Modifier.onGloballyPositioned { paneAt[0] = it.positionInRoot() },
            transitionSpec = {
                when {
                    // Into a page from its control: the control floods the pane (PageFlood) as it sinks back.
                    targetState in origins -> (fadeIn(Motion.tw(120)))
                        .togetherWith(fadeOut(Motion.tw(320, 80)) + scaleOut(Motion.tw(480), targetScale = 0.95f))
                        .apply { targetContentZIndex = 1f }
                    // Back out of it: the flood draws back into the control as the pane comes forward again.
                    initialState in origins -> (fadeIn(Motion.tw(300, 100)) + scaleIn(Motion.tw(460, 40), initialScale = 0.95f))
                        .togetherWith(ExitTransition.None)
                        .apply { targetContentZIndex = -1f }
                    else -> (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                        .togetherWith(fadeOut(Motion.tw(170)) + slideOutVertically(Motion.tw(170)) { -it / 40 })
                        .apply { targetContentZIndex = 1f }
                }
            },
            label = "pane",
        ) { key ->
            val shown = if (page != null && key == s.pageKey) page else pages[key]
            if (shown != null) {
                DisposableEffect(key) { onDispose { if (key != livePage) { pages.remove(key); origins.remove(key) } } }
                val from = origins[key]
                if (from == null) shown()
                else {
                    // Keeps a leaving page on screen while its flood draws back into the control.
                    transition.animateFloat(
                        transitionSpec = { if (targetState == EnterExitState.PostExit) Motion.tw(PAGE_RETURN_MS) else snap() },
                        label = "pageReturn",
                    ) { if (it == EnterExitState.PostExit) 1f else 0f }
                    PageFlood(from, leaving = transition.targetState == EnterExitState.PostExit) { shown() }
                }
            }
            else Content(s, if (key == "games") gameShown[0] else key, a, Modifier.fillMaxSize(), onSelect, onAndroidAppClick, onOpenDeveloperOptions, onRequestWirelessAdb)
        }
      }
    }
}

@Composable
private fun Backdrop(art: File?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Crossfade(targetState = art, animationSpec = Motion.tw(700), label = "backdrop") { a ->
        // A game's own art, blurred, where it has some; otherwise the plain ground with one quiet
        // glow of the theme's signal colour - a colour per title hash read as noise.
        if (a != null && Build.VERSION.SDK_INT >= 31) {
            Box(modifier = Modifier.fillMaxSize().alpha(0.26f).blur(70.dp)) {
                AsyncImage(model = a, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(pal.signal.copy(alpha = 0.07f), Color.Transparent), center = Offset.Zero, radius = 1400f)))
        }
    }
    Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.35f)))))
}

private fun romFor(s: FrontEndState, selected: String): Pair<Library.Emulator, Library.Rom>? {
    val parts = selected.split(":")
    val e = s.emulators.firstOrNull { it.id == parts.getOrNull(1) } ?: return null
    val g = e.games.getOrNull(parts.getOrNull(2)?.toIntOrNull() ?: -1) ?: return null
    return e to g
}

@Composable
private fun Content(
    s: FrontEndState, selected: String, a: FrontEndActions, modifier: Modifier,
    onSelect: (String) -> Unit, onAndroidAppClick: (HomeApp.LaunchableApp) -> Unit,
    onOpenDeveloperOptions: () -> Unit, onRequestWirelessAdb: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val detailPosterWidth = if (LocalConfiguration.current.screenHeightDp < 600) 72.dp else 120.dp
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 16.dp else 22.dp
    val padV = if (narrow) 12.dp else 18.dp
    // Setup scrolls inside itself, under its tabs.
    if (selected == "setup") {
        Column(modifier = modifier.padding(horizontal = padH, vertical = padV)) {
            SetupPanel(s, a, onOpenDeveloperOptions, onRequestWirelessAdb)
        }
        return
    }
    if (selected == "updates") {
        UpdatesPage(s, a, modifier.padding(horizontal = padH, vertical = padV))
        return
    }
    // Steam is a full-bleed wall of the library with Play over it.
    if (selected == "steam") {
        SteamHome(s, a, modifier)
        return
    }
    // The Store scrolls and navigates inside itself (its tabs and an app's page).
    if (selected == "store" && s.storeEnabled) {
        StorePage(s, a, modifier)
        return
    }
    // Stores (GOG, Epic, Amazon) takes the whole pane: chips, shelves and a game's page inside it.
    if (selected == "stores" && s.gameStoresEnabled) {
        StoresPage(s, a, modifier)
        return
    }
    // The Games tab lays out its own list and detail.
    if (selected == "games" || selected.startsWith("app:")) {
        GamesPage(s, a, selected, onSelect, modifier)
        return
    }
    // Every other page scrolls as one - header, hero and grid - so a short screen reaches the grid
    // instead of showing a sliver of it under a fixed hero. A focused tile scrolls itself into view.
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = padH, vertical = padV)) {
        when {
            selected == "android-apps" && s.isHomeApp -> {
                Rise(0) { PageHeader(stringResource(R.string.content_android_apps)) { Chip(stringResource(R.string.content_apps_count, s.androidApps.size), ok = false) } }
                if (s.androidApps.isEmpty()) Rise(3) { Note(stringResource(R.string.content_no_android_apps)) }
                else Rise(3, Modifier.fillMaxWidth()) {
                    ArtGrid(s.androidApps.map { app ->
                        Tile(
                            app.label,
                            null,
                            null,
                            "android:${app.packageName}",
                            onClick = { onAndroidAppClick(app) },
                            iconBitmap = app.icon,
                        )
                    })
                }
            }
            selected == "desktop" -> {
                val installed = s.emulators.filter { it.installed }
                val available = s.emulators.filter { !it.installed }
                val ctx = LocalContext.current
                LaunchedEffect(Unit) { UserAppsState.refresh(ctx) }
                val apps = UserAppsState.items
                var adding by rememberSaveable { mutableStateOf(false) }
                Rise(0) {
                    PageHeader(stringResource(R.string.drawer_desktop)) {
                        if (s.desktopInstalled) Chip(stringResource(R.string.content_desktop_installed), ok = true) else Chip(stringResource(R.string.content_desktop_first_open), ok = false)
                    }
                }
                Rise(2) { DesktopCard(s, a) }
                // Add closes the last grid: the installable emulators, or the installed ones once none are left.
                val ready = installed.map { GridItem.Emu(it) } + apps.map { GridItem.User(it) }
                val addAfterReady = available.isEmpty()
                if (ready.isNotEmpty() || addAfterReady) {
                    Rise(3) { SectionTitle(stringResource(R.string.user_apps_section), ready.size.takeIf { it > 0 }?.toString()) }
                    Rise(4) {
                        LauncherGrid(if (addAfterReady) ready + GridItem.Add else ready, first = true, onSelect = onSelect, onAdd = { if (!s.busy) adding = true })
                    }
                }
                if (UserAppsState.working != null || UserAppsState.lastError != null) Rise(5) { UserAppsProgress() }
                if (!addAfterReady) {
                    Rise(5) { SectionTitle(stringResource(R.string.content_available), available.size.toString()) }
                    Rise(6) {
                        LauncherGrid(available.map { GridItem.Emu(it) } + GridItem.Add, first = ready.isEmpty(), onSelect = onSelect, onAdd = { if (!s.busy) adding = true })
                    }
                }
                if (adding && !s.busy) AddAppDialog(s.ready, onDismiss = { adding = false }) { request, label -> UserAppsState.add(ctx, request, label) }
            }
            selected.startsWith("user:") -> {
                val app = UserAppsState.items.firstOrNull { "user:${it.key}" == selected }
                if (app == null) Note(stringResource(R.string.user_apps_gone)) else UserAppPage(app, s, a, onSelect)
            }
            selected.startsWith("emu:") -> {
                val e = s.emulators.firstOrNull { "emu:${it.id}" == selected }
                if (e == null) Note(stringResource(R.string.content_not_installed)) else {
                    val pkgId = Library.packageId(e.id)
                    val pkg = pkgId?.let { id -> s.packages?.firstOrNull { it.id == id } }
                    Rise(0) {
                        BackLink(stringResource(R.string.drawer_desktop)) { onSelect("desktop") }
                    }
                    Rise(1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                        ) {
                            Image(painterResource(e.iconRes), null, modifier = Modifier.size(52.dp))
                            Column {
                                Text(e.name, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                                Text(
                                    systemLabel(e).let { if (e.installed) it else stringResource(R.string.content_not_installed_suffix, it) },
                                    fontSize = 14.sp, color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (e.installed) {
                        Rise(3) {
                            Actions {
                                PrimaryButton(stringResource(R.string.content_open_named, e.name), enabled = !s.busy, main = true) { a.onEmulator(e) }
                                BusyChip(s)
                                SecondaryButton(stringResource(R.string.setup_tool_roms), onClick = a.onRoms)
                                if (pkg != null) SecondaryButton(
                                    if (pkg.kind == "appimage") stringResource(R.string.store_remove) else stringResource(R.string.common_hide),
                                    enabled = !s.busy && s.packageBusyId == null && !s.sessionRunning,
                                ) { a.onRemovePackage(pkg.id) }
                            }
                        }
                        Rise(4) { SectionTitle(stringResource(R.string.content_games), e.games.size.toString()) }
                        if (e.games.isEmpty()) Rise(5) {
                            Note(
                                if (s.romsDir == null) stringResource(R.string.content_choose_roms)
                                else if (e.id == "retroarch") stringResource(R.string.content_retroarch_roms)
                                else stringResource(R.string.content_add_roms, e.system, e.system.substringBefore(' ')),
                            )
                        }
                        else Rise(5, Modifier.fillMaxWidth()) {
                            ArtGrid(e.games.mapIndexed { index, g -> Tile(g.name, if (g.art != null) stringResource(R.string.content_rom_installed) else g.hostPath.extension.uppercase().ifEmpty { stringResource(R.string.content_rom_folder) }, g.art, "rom:${e.id}:$index", e.iconRes) { onSelect("rom:${e.id}:$index") } }, wide = e.games.none { it.art != null })
                        }
                    } else {
                        if (pkg != null) Rise(2) {
                            Actions {
                                PrimaryButton(
                                    if (s.packageBusyId == pkg.id) stringResource(R.string.store_installing) else stringResource(R.string.content_install_named, e.name),
                                    enabled = !s.busy && s.packageBusyId == null && s.ready && !s.packageCatalogLoading && !s.sessionRunning,
                                ) { a.onInstallPackage(pkg.id) }
                                if (s.sessionRunning) ActionChip(stringResource(R.string.content_stop_to_install), ok = false)
                                else if (!s.ready) ActionChip(stringResource(R.string.content_runtime_required), ok = false)
                            }
                        }
                        Rise(3) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Note(when {
                                    s.packageCatalogLoading -> stringResource(R.string.content_loading_details)
                                    pkg == null -> stringResource(R.string.content_details_unavailable)
                                    !s.ready -> stringResource(R.string.content_needs_runtime)
                                    s.sessionRunning -> stringResource(R.string.content_stop_first)
                                    pkg.notes.isNotBlank() -> pkg.notes
                                    else -> stringResource(R.string.content_install_into, e.name)
                                })
                            }
                        }
                        if (s.packageBusyId == pkg?.id) Rise(4) {
                            val stage = s.packageStage
                            Text(
                                if (stage != null && s.packagePercent >= 0) stringResource(R.string.store_busy_percent_short, stage, s.packagePercent) else stage ?: stringResource(R.string.store_starting),
                                fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (s.packagePercent >= 0) LinearProgressIndicator(progress = { s.packagePercent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                        if (pkg?.kind == "tar") Rise(5) { Note(stringResource(R.string.content_hide_note)) }
                    }
                }
            }
            selected.startsWith("rom:") -> {
                val pair = romFor(s, selected)
                if (pair == null) Note(stringResource(R.string.content_rom_gone)) else {
                    val (e, g) = pair
                    Rise(0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BackLink(e.name) { onSelect("emu:${e.id}") }
                            Eyebrow(stringResource(R.string.content_desktop_system, systemLabel(e)))
                        }
                    }
                    Rise(1) { Title(g.name) }
                    Rise(2) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.guestPath, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.surface).padding(horizontal = 10.dp, vertical = 6.dp))
                                Spacer(Modifier.height(14.dp))
                                Actions {
                                    Image(painterResource(e.iconRes), null, modifier = Modifier.size(40.dp))
                                    PrimaryButton(stringResource(R.string.content_launch_in, e.name), enabled = !s.busy, main = true) { a.onRom(g) }
                                    if (s.busy) BusyChip(s) else ActionChip(g.hostPath.extension.uppercase().ifEmpty { stringResource(R.string.content_rom_folder) }, ok = false)
                                }
                            }
                            if (g.art != null && !narrow) Poster(g.art, g.name, Modifier.width(detailPosterWidth))
                        }
                    }
                    val others = e.games.filter { it !== g }
                    if (others.isNotEmpty()) {
                        Rise(3) { SectionTitle(stringResource(R.string.content_also_in, e.name), null) }
                        Rise(4, Modifier.fillMaxWidth()) {
                            ArtGrid(others.map { x ->
                                val index = e.games.indexOf(x)
                                Tile(x.name, if (x.art != null) stringResource(R.string.content_rom_installed) else x.hostPath.extension.uppercase().ifEmpty { stringResource(R.string.content_rom_folder) }, x.art, "rom:${e.id}:$index", e.iconRes) { onSelect("rom:${e.id}:$index") }
                            }, wide = others.none { it.art != null })
                        }
                    }
                }
            }
            else -> Note(stringResource(R.string.content_page_gone))
        }
    }
}

/** The Linux desktop itself, above the emulators that run in it. */
@Composable
private fun DesktopCard(s: FrontEndState, a: FrontEndActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val actions: @Composable () -> Unit = {
        Actions {
            // Enabled without a runtime or the desktop: the session's loading screen installs them first.
            PrimaryButton(if (s.desktopInstalled) stringResource(R.string.content_open_desktop) else stringResource(R.string.content_install_open_desktop), enabled = !s.busy, main = true, onClick = a.onDesktop)
            Cog(onClick = a.onDesktopSettings)
            BusyChip(s)
        }
    }
    // The button goes under the title unless the card is wide enough for both side by side: in some
    // languages its label alone takes half a small phone's pane.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = LocalNarrowPane.current || maxWidth < DesktopCardInlineWidth
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(52.dp).clip(Shape14).background(colors.surfaceVariant).border(1.dp, pal.line2, Shape14),
                ) { Icon(Icons.Outlined.DesktopWindows, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(26.dp)) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.content_linux_desktop), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    Text(stringResource(R.string.content_linux_desktop_hint), fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!narrow) actions()
            }
            if (narrow) actions()
        }
    }
}

/** How wide the desktop card must be to keep its button beside the title. */
private val DesktopCardInlineWidth = 640.dp

/** What the Desktop page's grids hold: emulators, the user's own apps, and Add at the end. */
private sealed class GridItem(val key: String) {
    class Emu(val e: Library.Emulator) : GridItem("emu:${e.id}")
    class User(val app: UserApps.App) : GridItem("user:${app.key}")
    data object Add : GridItem("add")
}

/** Wide tiles - three across, two on a small phone, or a list on a narrow page. */
@Composable
private fun LauncherGrid(items: List<GridItem>, first: Boolean, onSelect: (String) -> Unit, onAdd: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A tile holds an icon, a name and an Install button: one much under 230dp wide leaves the
        // name no room at all.
        val columns = when {
            LocalNarrowPane.current -> 1
            maxWidth < 720.dp -> 2
            else -> 3
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
            items.chunked(columns).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    row.forEachIndexed { i, item ->
                        key(item.key) {
                            val m = Modifier.weight(1f).fillMaxHeight()
                            val isFirst = first && r == 0 && i == 0
                            when (item) {
                                is GridItem.Emu -> EmulatorTile(item.e, m, isFirst) { onSelect(item.key) }
                                is GridItem.User -> UserAppTile(item.app, m, isFirst) { onSelect(item.key) }
                                GridItem.Add -> AddTile(m, isFirst, onAdd)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A grid tile's frame: the emulator tiles' look, focus glide and press. */
@Composable
private fun TileFrame(id: String, modifier: Modifier, isFirst: Boolean, filled: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "tileScale")
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.paneItem("tile:$id").then(if (isFirst) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else if (filled) colors.surface else Color.Transparent)
            .glideBorder(hot, Shape14, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { content() }
}

@Composable
private fun TileText(title: String, detail: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal fun UserApps.Kind.label() = when (this) {
    UserApps.Kind.SCRIPT -> R.string.user_apps_kind_script
    UserApps.Kind.APPIMAGE -> R.string.user_apps_kind_appimage
    UserApps.Kind.FLATPAK -> R.string.user_apps_kind_flatpak
}

/** An added app's icon: its own, or the kind's when it has none. */
@Composable
private fun UserAppIcon(app: UserApps.App, size: Int) {
    if (app.icon != null) AppIcon(app.icon, app.name, size)
    else Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 5).dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            if (app.kind == UserApps.Kind.SCRIPT) Icons.Outlined.Terminal else Icons.Outlined.Apps, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((size / 2).dp),
        )
    }
}

@Composable
private fun UserAppTile(app: UserApps.App, modifier: Modifier, isFirst: Boolean, onClick: () -> Unit) =
    TileFrame("user:${app.key}", modifier, isFirst, filled = true, onClick = onClick) {
        UserAppIcon(app, 44)
        TileText(app.name, stringResource(app.kind.label()), Modifier.weight(1f))
    }

/** The grid's last tile: opens the Add dialog. */
@Composable
private fun AddTile(modifier: Modifier, isFirst: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    TileFrame("add", modifier, isFirst, filled = false, onClick = onClick) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(Shape12).background(pal.signal.copy(alpha = 0.12f)).border(1.dp, pal.signal.copy(alpha = 0.35f), Shape12),
        ) { Icon(Icons.Filled.Add, null, tint = pal.signal, modifier = Modifier.size(24.dp)) }
        TileText(stringResource(R.string.user_apps_add), stringResource(R.string.user_apps_add_detail), Modifier.weight(1f))
    }
}

/** The add or remove under way, or why the last one failed. */
@Composable
private fun UserAppsProgress() {
    val colors = MaterialTheme.colorScheme
    val working = UserAppsState.working
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (working != null) {
            val stage = UserAppsState.stage ?: stringResource(R.string.user_apps_starting)
            val percent = UserAppsState.percent
            Text(
                if (percent >= 0) stringResource(R.string.user_apps_progress_percent, working, stage, percent) else stringResource(R.string.user_apps_progress, working, stage),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        } else UserAppsState.lastError?.let { Note(it) }
    }
}

/** An added app's page: open it, change its name and icon, update one added from GitHub, or remove it (pressed twice). */
@Composable
private fun UserAppPage(app: UserApps.App, s: FrontEndState, a: FrontEndActions, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    var confirm by remember(app.key) { mutableStateOf(false) }
    var editing by rememberSaveable(app.key) { mutableStateOf(false) }
    val x86 = (app.arch == "x86_64" || app.arch == "i386") && app.fex != com.droiddeck.launcher.runtime.LinuxFex.OFF
    val fexReady by produceState<Boolean?>(null, app.key, x86, s.sessionRunning) {
        value = if (x86) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.droiddeck.launcher.runtime.LinuxFex.ready(ctx) } else true
    }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
    Rise(0) { BackLink(stringResource(R.string.user_apps_back)) { onSelect("desktop") } }
    Rise(1) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        ) {
            UserAppIcon(app, 52)
            Column {
                Text(app.name, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text(
                    (app.repo?.let { stringResource(R.string.user_apps_github_version, it, app.version.orEmpty()) } ?: stringResource(app.kind.label())) +
                        (if (app.arch == "x86_64" || app.arch == "i386") " · " + stringResource(R.string.app_fex_x86, app.arch) else ""),
                    fontSize = 14.sp, color = colors.onSurfaceVariant,
                )
            }
        }
    }
    Rise(3) {
        Actions {
            PrimaryButton(stringResource(R.string.user_apps_open, app.name), enabled = !s.busy, main = true) { a.onUserApp(app) }
            BusyChip(s)
            SecondaryButton(stringResource(R.string.user_apps_edit), enabled = UserAppsState.working == null) { editing = true }
            if (app.repo != null) UpdateButton(app, s)
            SecondaryButton(
                stringResource(if (confirm) R.string.user_apps_remove_confirm else R.string.user_apps_remove),
                enabled = !s.busy && !s.sessionRunning && UserAppsState.working == null,
            ) {
                if (!confirm) confirm = true
                else { confirm = false; UserAppsState.remove(ctx, app); onSelect("desktop") }
            }
            if (s.sessionRunning) ActionChip(stringResource(R.string.user_apps_stop_session), ok = false)
        }
    }
    if (UserAppsState.working != null || UserAppsState.lastError != null) Rise(4) { UserAppsProgress() }
    if (fexReady == false) Rise(4) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Note(stringResource(R.string.app_fex_not_ready, app.arch.orEmpty())) }
    }
    if (editing) EditAppDialog(app, onDismiss = { editing = false }) { name, icon, fex -> UserAppsState.edit(ctx, app, name, icon, fex) }
    val detail = app.detail
    if (detail != null) Rise(4) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Note(
                if (app.kind != UserApps.Kind.SCRIPT) detail
                else stringResource(if (app.copied) R.string.user_apps_script_copied else R.string.user_apps_script_linked, detail),
            )
        }
    }
}

/** Looks up a newer release of an app added from GitHub, then installs it. */
@Composable
private fun UpdateButton(app: UserApps.App, s: FrontEndState) {
    val ctx = LocalContext.current
    val idle = UserAppsState.working == null && UserAppsState.checking == null
    when (val found = UserAppsState.updates[app.key]) {
        is UserApps.UpdateCheck.Available -> SecondaryButton(
            stringResource(R.string.user_apps_update_to, found.release.tag), enabled = !s.busy && idle && !s.sessionRunning,
        ) { UserAppsState.update(ctx, app, found.release) }
        else -> {
            SecondaryButton(
                stringResource(if (UserAppsState.checking == app.key) R.string.user_apps_checking_updates else R.string.user_apps_check_updates),
                enabled = idle,
            ) { UserAppsState.checkUpdate(ctx, app) }
            when (found) {
                is UserApps.UpdateCheck.Current -> ActionChip(stringResource(R.string.user_apps_up_to_date, found.tag), ok = true)
                is UserApps.UpdateCheck.Failed -> ActionChip(found.message, ok = false)
                else -> {}
            }
        }
    }
}

/**
 * One emulator: its icon, system and games. One not yet installed says so in words and keeps full
 * contrast - dimming it read as disabled rather than one press from installing.
 */
@Composable
private fun systemLabel(e: Library.Emulator): String =
    if (e.systemRes != 0) stringResource(e.systemRes) else e.system.replaceFirstChar { it.uppercase() }

@Composable
private fun EmulatorTile(e: Library.Emulator, modifier: Modifier, isFirst: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val system = systemLabel(e)
    val detail = if (e.installed && e.id != "retroarch") "$system · " + pluralStringResource(R.plurals.mode_added_count, e.games.size, e.games.size) else system
    TileFrame("emu:${e.id}", modifier, isFirst, filled = e.installed, onClick = onClick) {
        Image(painterResource(e.iconRes), contentDescription = null, modifier = Modifier.size(if (e.installed) 44.dp else 36.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(e.name, fontSize = if (e.installed) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = if (e.installed) 13.sp else 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!e.installed) Text(
            stringResource(R.string.setup_install), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, pal.line2, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
