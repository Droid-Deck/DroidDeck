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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
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
        val pages = remember { HashMap<String, @Composable () -> Unit>() }
        val origins = remember { HashMap<String, Origin>() }
        val paneAt = remember { arrayOf(Offset.Zero) }
        val livePage by rememberUpdatedState(s.pageKey)
        val railContent = if (selected.startsWith("app:")) "games" else selected
        val target = if (page != null && s.pageKey != null) s.pageKey else railContent
        val gameShown = remember { arrayOf("games") }
        if (selected == "games" || selected.startsWith("app:")) gameShown[0] = selected
        val before = remember { arrayOf(target) }
        if (page != null && s.pageKey != null) {
            pages[s.pageKey] = page
            if (s.pageKey !in origins) PageOrigin.take()?.let { o ->
                origins[s.pageKey] = inPane(o.translate(-paneAt[0])).let { if (it.owner == null) it.ownedBy(railContent) else it }
            }
        } else if (target != before[0] && target !in origins) {
            PageOrigin.take()?.let { o -> origins[target] = inPane(o.translate(-paneAt[0])).let { if (it.owner == null) it.ownedBy(before[0]) else it } }
        }
        before[0] = target
        val targetNow by rememberUpdatedState(target)
        val leave = if (Hops.linking) PageLeave.Link else PageLeave.Ordinary
        LaunchedEffect(target) { Hops.linking = false }
        AnimatedContent(
            targetState = target,
            modifier = Modifier.onGloballyPositioned { paneAt[0] = it.positionInRoot() },
            transitionSpec = {
                when {
                    targetState in origins -> (fadeIn(Motion.tw(120)))
                        .togetherWith(fadeOut(Motion.tw(320, 80)) + scaleOut(Motion.tw(480), targetScale = 0.95f))
                        .apply { targetContentZIndex = 1f }
                    initialState in origins && pageExit(origins[initialState]?.owner, targetState, leave) == PageExit.Drain ->
                        (fadeIn(Motion.tw(300, 100)) + scaleIn(Motion.tw(460, 40), initialScale = 0.95f))
                            .togetherWith(ExitTransition.None)
                            .apply { targetContentZIndex = -1f }
                    initialState in origins -> (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                        .togetherWith(fadeOut(Motion.tw(170)) + scaleOut(Motion.tw(170), targetScale = 0.95f))
                        .apply { targetContentZIndex = 1f }
                    else -> (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                        .togetherWith(fadeOut(Motion.tw(170)) + slideOutVertically(Motion.tw(170)) { -it / 40 })
                        .apply { targetContentZIndex = 1f }
                }
            },
            label = "pane",
        ) { key ->
            val drawn = (if (page != null && key == s.pageKey) page else pages[key])
                ?: { Content(s, if (key == "games") gameShown[0] else key, a, Modifier.fillMaxSize(), onSelect, onAndroidAppClick, onOpenDeveloperOptions, onRequestWirelessAdb) }
            val hopSink by androidx.compose.animation.core.animateFloatAsState(
                if (Hops.sinking == key) 1f else 0f, Motion.tw(260), label = "hopSink",
            )
            val shown: @Composable () -> Unit = {
                Box(Modifier.fillMaxSize().graphicsLayer { val k = 1f - 0.05f * hopSink; scaleX = k; scaleY = k; alpha = 1f - 0.35f * hopSink }) { drawn() }
            }
            run {
                DisposableEffect(key) { onDispose { if (key != livePage && key != targetNow) { pages.remove(key); origins.remove(key) } } }
                val from = origins[key]
                if (from == null) shown()
                else {
                    val art = when (val m = from.art) {
                        null -> null
                        is Int -> androidx.compose.ui.res.painterResource(m)
                        else -> coil.compose.rememberAsyncImagePainter(m)
                    }
                    val leaving = transition.targetState == EnterExitState.PostExit
                    val drains = remember(leaving) { !leaving || pageExit(from.owner, target, leave) == PageExit.Drain }
                    transition.animateFloat(
                        transitionSpec = { if (targetState == EnterExitState.PostExit && drains) Motion.tw(PAGE_RETURN_MS) else snap() },
                        label = "pageReturn",
                    ) { if (it == EnterExitState.PostExit) 1f else 0f }
                    PageFlood(from, leaving = leaving, art = art, sinks = !drains) { shown() }
                }
            }
        }
      }
    }
}

internal fun inPane(o: Origin): Origin =
    if (o.bounds.right > 0f) o else o.translate(Offset(-o.bounds.right + o.bounds.width / 2f, 0f))

@Composable
private fun Backdrop(art: File?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Crossfade(targetState = art, animationSpec = Motion.tw(700), label = "backdrop") { a ->
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
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val padH = if (narrow) 16.dp else 22.dp
    val padV = if (narrow) 12.dp else 18.dp
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
    if (selected == "steam") {
        SteamHome(s, a, modifier)
        return
    }
    if (selected == "store") {
        StorePage(s, a, modifier)
        return
    }
    if (selected == "stores") {
        StoresPage(s, a, modifier)
        return
    }
    if (selected == "games" || selected.startsWith("app:")) {
        LibraryPage(s, a, selected, onSelect, modifier)
        return
    }
    if (selected == "desktop") {
        DesktopPage(s, a, modifier, onSelect)
        return
    }
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
            selected.startsWith("user:") -> {
                val app = UserAppsState.items.firstOrNull { "user:${it.key}" == selected }
                if (app == null) Note(stringResource(R.string.user_apps_gone)) else UserAppPage(app, s, a, onSelect)
            }
            selected.startsWith("emu:") -> {
                val e = s.emulators.firstOrNull { "emu:${it.id}" == selected }
                if (e == null) Note(stringResource(R.string.content_not_installed)) else {
                    val pkgId = Library.packageId(e.id)
                    val pkg = pkgId?.let { id -> s.packages?.firstOrNull { it.id == id } }
                    Rise(1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                        ) {
                            Image(painterResource(e.iconRes), null, modifier = Modifier.size(64.dp))
                            Column {
                                Text(e.name, fontSize = if (narrow) 24.sp else 30.sp, fontWeight = FontWeight.Black, color = colors.onBackground)
                                Text(
                                    systemLabel(e).let { if (e.installed) it else stringResource(R.string.content_not_installed_suffix, it) },
                                    fontSize = 14.sp, color = colors.onSurfaceVariant,
                                )
                                if (e.installed) Text(
                                    listOfNotNull(
                                        pluralStringResource(R.plurals.mode_added_count, e.games.size, e.games.size).takeIf { e.id != "retroarch" || e.games.isNotEmpty() },
                                        s.romsDir,
                                    ).joinToString(" · "),
                                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                            Column {
                                val systems = remember(e.games) { e.games.map { romSystem(it) }.distinct() }
                                var system by rememberSaveable(e.id) { mutableStateOf<String?>(null) }
                                if (systems.size > 1) Box(Modifier.padding(top = 10.dp)) {
                                    val labels = listOf(stringResource(R.string.desktop_all_systems)) + systems
                                    SheetChips(labels, if (system == null) 0 else systems.indexOf(system) + 1) { i -> system = if (i == 0) null else systems[i - 1] }
                                }
                                val shown = e.games.withIndex().filter { system == null || romSystem(it.value) == system }
                                ArtGrid(shown.map { (index, g) ->
                                    val key = "rom:${e.id}:$index"
                                    Tile(g.name, if (g.art != null) stringResource(R.string.content_rom_installed) else g.hostPath.extension.uppercase().ifEmpty { stringResource(R.string.content_rom_folder) }, g.art, key, e.iconRes) {
                                        TileBounds.mark(key, g.art ?: e.iconRes, 12f * density)
                                        onSelect(key)
                                    }
                                }, wide = e.games.none { it.art != null })
                            }
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
                    Rise(0) { Eyebrow(stringResource(R.string.content_desktop_system, systemLabel(e))) }
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
                                Tile(x.name, if (x.art != null) stringResource(R.string.content_rom_installed) else x.hostPath.extension.uppercase().ifEmpty { stringResource(R.string.content_rom_folder) }, x.art, "rom:${e.id}:$index", e.iconRes) {
                                    TileBounds.mark("rom:${e.id}:$index", x.art ?: e.iconRes, 12f * density)
                                    onSelect("rom:${e.id}:$index")
                                }
                            }, wide = others.none { it.art != null })
                        }
                    }
                }
            }
            else -> Note(stringResource(R.string.content_page_gone))
        }
    }
}

internal fun UserApps.Kind.label() = when (this) {
    UserApps.Kind.SCRIPT -> R.string.user_apps_kind_script
    UserApps.Kind.APPIMAGE -> R.string.user_apps_kind_appimage
    UserApps.Kind.FLATPAK -> R.string.user_apps_kind_flatpak
}

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

@Composable
private fun UserAppPage(app: UserApps.App, s: FrontEndState, a: FrontEndActions, onSelect: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val narrow = LocalNarrowPane.current
    var editing by rememberSaveable(app.key) { mutableStateOf(false) }
    val x86 = (app.arch == "x86_64" || app.arch == "i386") && app.fex != com.droiddeck.launcher.runtime.LinuxFex.OFF
    val fexReady by produceState<Boolean?>(null, app.key, x86, s.sessionRunning) {
        value = if (x86) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.droiddeck.launcher.runtime.LinuxFex.ready(ctx) } else true
    }
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
            RemoveAppButton(app, enabled = !s.busy && !s.sessionRunning && UserAppsState.working == null) {
                UserAppsState.remove(ctx, app); onSelect("desktop")
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

@Composable
private fun RemoveAppButton(app: UserApps.App, enabled: Boolean, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.user_apps_remove)
    val title = stringResource(R.string.flathub_remove_title, app.name)
    val keep = stringResource(R.string.flathub_keep)
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SecondaryButton(label, enabled = enabled) {
            val from = at ?: return@SecondaryButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Right, accent = colors.error, pillCorner = 12.dp, items = 2,
                handle = { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1) },
            ) {
                StepTitle(title)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.stepItem(1)) {
                    StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(label, danger = true, enabled = open) { Steps.fold(); onRemove() }
                }
            })
        }
    }
}

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

@Composable
private fun systemLabel(e: Library.Emulator): String =
    if (e.systemRes != 0) stringResource(e.systemRes) else e.system.replaceFirstChar { it.uppercase() }

private fun romSystem(rom: Library.Rom): String = rom.hostPath.parentFile?.name.orEmpty()
