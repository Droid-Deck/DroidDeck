package com.droiddeck.launcher.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.store.UserAppsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


private val DESKTOP_TABS = modeSheetTabs(steam = false) + SheetTab.FILES

@Composable
internal fun DesktopPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val narrow = LocalNarrowPane.current
    val short = compactDesktop()
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { UserAppsState.refresh(ctx) }
    LaunchedEffect(s.ready) { com.droiddeck.launcher.store.StoreState.refresh(ctx) }
    val installed = s.emulators.filter { it.installed }
    val apps = UserAppsState.items
    var adding by rememberSaveable { mutableStateOf(false) }
    val mode = SessionService.MODE_DESKTOP
    val tabs = DESKTOP_TABS.map { it to sheetTabLabel(it) }
    val open = if (s.desktopInstalled) stringResource(R.string.content_open_desktop) else stringResource(R.string.content_install_open_desktop)
    fun openFrom(at: Rect?, art: Any?, page: String) {
        at?.let { PageOrigin.mark(Origin(it, 16f * density.density, art = art)) }
        onSelect(page)
    }

    LiftHero(
        key = "desktop", tabs = tabs,
        onOpen = a.onDesktopSettings, onPrimary = a.onDesktop,
        modifier = modifier.clipToBounds(),
        start = if (narrow) 20.dp else 44.dp, bottom = 44.dp,
        background = {
            DesktopWall(installed.map { it.iconRes } + apps.mapNotNull { it.icon })
        },
        scrim = { lifted ->
            WallFade()
            Box(Modifier.graphicsLayer { val v = lifted(); alpha = 1f - v; translationY = -12.dp.toPx() * v }) {
                DesktopDock(s, a, installed, apps, openFrom = ::openFrom)
            }
        },
        words = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (LocalConfiguration.current.screenHeightDp >= 400) Text(
                    stringResource(R.string.rail_desktop), fontSize = if (short) 30.sp else 42.sp, lineHeight = if (short) 34.sp else 46.sp,
                    fontWeight = FontWeight.Black, color = colors.onBackground,
                )
                BusyChip(s)
            }
        },
        button = { click ->
            PrimaryButton(open, enabled = !s.busy, main = true, large = true, icon = Icons.Outlined.DesktopWindows, onClick = click)
        },
        beside = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                HeroIconButton(Icons.Outlined.Storefront, stringResource(R.string.flathub_name), FlathubTeal, "desktop:flathub") { at -> openFrom(at, null, "store") }
                HeroIconButton(Icons.Filled.Add, stringResource(R.string.user_apps_add), null, "desktop:add") { if (!s.busy) adding = true }
                if (s.desktopPresent) UninstallDesktop(enabled = !s.runtimeActionsBlocked, onUninstall = a.onRemoveDesktop)
            }
        },
        rows = { tab ->
            if (tab == SheetTab.FILES) DesktopFiles(s, a)
            else a.modeSheet(mode, tab, SheetExtras())
        },
    )
    if (adding && !s.busy) AddAppDialog(s.ready, onDismiss = { adding = false }) { request, label -> UserAppsState.add(ctx, request, label) }
}

@Composable
private fun compactDesktop() = LocalNarrowPane.current || LocalConfiguration.current.screenHeightDp < 600

@Composable
private fun DesktopDock(
    s: FrontEndState, a: FrontEndActions, installed: List<Library.Emulator>, apps: List<UserApps.App>,
    openFrom: (Rect?, Any?, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val available = s.emulators.filter { !it.installed }
    val flatpaks = com.droiddeck.launcher.store.StoreState.installed
    val keys = installed.map { "emu:${it.id}" } + apps.map { "user:${it.key}" } + flatpaks.map { "flatpak:${it.id}" }
    var lastAt by remember { mutableStateOf<Rect?>(null) }
    var dockAt by remember { mutableStateOf<Rect?>(null) }
    val seen = remember { mutableSetOf<String>() }
    val mounted = remember { booleanArrayOf(false) }
    LaunchedEffect(Unit) { mounted[0] = true }
    val scroll = rememberScrollState()
    if (keys.isEmpty() && available.isEmpty() && UserAppsState.working == null && UserAppsState.lastError == null) return
    Column(Modifier.fillMaxWidth().padding(top = if (compactDesktop()) 14.dp else 22.dp)) {
        Text(
            stringResource(R.string.user_apps_section).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
            color = colors.onSurfaceVariant, modifier = Modifier.padding(start = if (narrow) 20.dp else 44.dp, bottom = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { dockAt = it.boundsInRoot() }
                .edgeFade(scroll).horizontalScroll(scroll).padding(start = if (narrow) 20.dp else 44.dp, end = 20.dp, top = 2.dp, bottom = 4.dp),
        ) {
            keys.forEachIndexed { i, k ->
                key(k) {
                    val state = remember { MutableTransitionState(!mounted[0] || k in seen) }.apply { targetState = true }
                    LaunchedEffect(Unit) { seen += k }
                    AnimatedVisibility(state, enter = expandHorizontally(Motion.sp(0.75f)) + fadeIn(Motion.tw(300, 80))) {
                        Box(if (i == keys.lastIndex) Modifier.onGloballyPositioned { lastAt = it.boundsInRoot() } else Modifier) {
                            val e = installed.firstOrNull { "emu:${it.id}" == k }
                            val flatpak = flatpaks.firstOrNull { "flatpak:${it.id}" == k }
                            val app = apps.firstOrNull { "user:${it.key}" == k }
                            if (e != null) {
                                val system = if (e.systemRes != 0) stringResource(e.systemRes) else e.system.replaceFirstChar { c -> c.uppercase() }
                                DesktopCard(
                                    k, e.name,
                                    if (e.id == "retroarch") system else "$system · " + pluralStringResource(R.plurals.mode_added_count, e.games.size, e.games.size),
                                    art = e.iconRes, first = i == 0,
                                ) { at -> openFrom(at, e.iconRes, k) }
                            } else if (flatpak != null) DesktopCard(
                                k, flatpak.name, flatpak.summary ?: stringResource(R.string.flathub_name),
                                art = flatpak.icon ?: com.droiddeck.launcher.store.FlathubApi.iconUrl(flatpak.id), first = i == 0,
                            ) { at ->
                                com.droiddeck.launcher.store.StoreState.openRequest = flatpak.id
                                openFrom(at, flatpak.icon, "store")
                            } else if (app != null) DesktopCard(k, app.name, stringResource(app.kind.label()), art = app.icon, fallback = if (app.kind == UserApps.Kind.SCRIPT) Icons.Outlined.Terminal else Icons.Outlined.Apps, first = i == 0) { at ->
                                openFrom(at, app.icon, k)
                            }
                        }
                    }
                }
            }
            if (keys.isNotEmpty()) Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(120.dp).background(pal.line2))
            available.forEach { e ->
                key(e.id) {
                    val pkg = Library.packageId(e.id)?.let { id -> s.packages?.firstOrNull { it.id == id } }
                    val busyHere = pkg != null && s.packageBusyId == pkg.id
                    val canGet = pkg != null && !s.busy && s.packageBusyId == null && s.ready && !s.packageCatalogLoading && !s.sessionRunning
                    val system = if (e.systemRes != 0) stringResource(e.systemRes) else e.system.replaceFirstChar { c -> c.uppercase() }
                    DesktopCard(
                        "emu:${e.id}", e.name, if (busyHere) (s.packageStage ?: stringResource(R.string.store_installing)) else system,
                        art = e.iconRes, badge = if (busyHere) null else stringResource(R.string.comp_get),
                        progress = if (busyHere) s.packagePercent else null, first = keys.isEmpty() && e == available.first(),
                    ) { at ->
                        if (canGet && pkg != null && at != null) {
                            a.onInstallPackage(pkg.id)
                            val end = lastAt
                            val row = dockAt
                            Flights.fly(Flight(at, to = {
                                val r = row ?: return@Flight null
                                Offset((end?.right ?: r.left) + 70.dp.toPx(), r.center.y)
                            }))
                        } else openFrom(at, e.iconRes, "emu:${e.id}")
                    }
                }
            }
        }
        if (UserAppsState.working != null || UserAppsState.lastError != null) {
            Box(Modifier.padding(start = if (narrow) 20.dp else 44.dp, end = 20.dp, top = 6.dp)) { UserAppsProgressLine() }
        }
    }
}

@Composable
private fun HeroIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: Color?, id: String, onClick: (Rect?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else if (hot) 1.04f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "heroIcon")
    val shape = RoundedCornerShape(12.dp)
    val accent = tint ?: pal.signal
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp).paneItem("btn:$id").onGloballyPositioned { at = it.boundsInRoot() }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (hot) accent.copy(alpha = 0.18f) else colors.surface.copy(alpha = 0.92f))
            .glideBorder(hot, shape, accent, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { onClick(at) }
            .controllerConfirm { onClick(at) }
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun DesktopWall(icons: List<Any>) {
    val pal = LocalPalette.current
    val ground = remember(pal) { Brush.linearGradient(listOf(pal.surfaceVariant, pal.surface)) }
    TiltedWall(icons.size, if (icons.isEmpty()) 75_000 else 50_000, capW = 132.dp, capH = 84.dp, gap = 14.dp, corner = 8.dp) { i ->
        WallCapsule(ground, edge = pal.line2, icon = icons.getOrNull(i), bar = pal.background.copy(alpha = 0.7f))
    }
}

@Composable
private fun DesktopCard(
    key: String, name: String, detail: String, art: Any?,
    fallback: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Apps,
    first: Boolean = false, badge: String? = null, progress: Int? = null, accent: Boolean = false, tint: Color? = null,
    onClick: (Rect?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (hot) 1.03f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "deskCard")
    val shape = RoundedCornerShape(16.dp)
    var artAt by remember { mutableStateOf<Rect?>(null) }
    val width = if (LocalNarrowPane.current) 116.dp else 132.dp
    val artH = width * 0.62f
    val ground = tint?.copy(alpha = 0.25f) ?: if (accent) pal.signal.copy(alpha = 0.12f) else colors.surfaceVariant
    Column(
        Modifier.width(width).paneItem("tile:desktop:$key").then(if (first) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { onClick(artAt) }
            .controllerConfirm { onClick(artAt) }
            .semantics { contentDescription = name },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(artH).onGloballyPositioned { artAt = it.boundsInRoot() }
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(ground),
        ) {
            when (art) {
                is Int -> {
                    Image(
                        painterResource(art), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f; alpha = 0.5f }
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    Image(painterResource(art), null, modifier = Modifier.size(artH * 0.6f))
                }
                null -> Icon(fallback, null, tint = tint ?: if (accent) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(artH * 0.42f))
                else -> {
                    AsyncImage(
                        model = art, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f; alpha = 0.5f }
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(artH * 0.6f))
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                if (progress != null) Box(Modifier.clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(4.dp)) {
                    if (progress >= 0) CircularProgressIndicator(progress = { progress / 100f }, strokeWidth = 2.dp, color = pal.signal, trackColor = pal.signal.copy(alpha = 0.25f), modifier = Modifier.size(16.dp))
                    else CircularProgressIndicator(strokeWidth = 2.dp, color = pal.signal, modifier = Modifier.size(16.dp))
                }
                else if (badge != null) Text(
                    badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.onSignal, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(pal.signal).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 12.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun DesktopFiles(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    SettingsGroup(stringResource(R.string.setup_tool_files)) {
        SettingsRow(stringResource(R.string.setup_tool_files), stringResource(R.string.setup_tool_files_hint)) {
            SecondaryButton(stringResource(R.string.store_open), compact = true, onClick = a.onFiles)
        }
        SettingsRow(stringResource(R.string.setup_tool_roms), s.romsDir ?: stringResource(R.string.setup_tool_roms_hint)) {
            SecondaryButton(stringResource(R.string.setup_choose), compact = true, onClick = a.onRoms)
        }
    }
    if (s.desktopPresent) SettingsGroup(stringResource(R.string.content_linux_desktop)) {
        val size by produceState<Long?>(null) { value = withContext(Dispatchers.IO) { runCatching { com.droiddeck.launcher.runtime.DesktopCatalog.desktopSize(ctx) }.getOrNull() } }
        SettingsRow(stringResource(R.string.desktop_size), size?.let { FileUtils.sizeToString(it) } ?: "…") {}
        SettingsRow(stringResource(R.string.desktop_remove_title), stringResource(R.string.desktop_remove_text)) {
            UninstallDesktop(enabled = !s.runtimeActionsBlocked, onUninstall = a.onRemoveDesktop)
        }
    }
}

@Composable
private fun UninstallDesktop(enabled: Boolean, onUninstall: () -> Unit) {
    val canUninstall by rememberUpdatedState(enabled)
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.common_uninstall)
    val title = stringResource(R.string.desktop_uninstall_title)
    val note = stringResource(R.string.desktop_remove_text)
    val keep = stringResource(R.string.flathub_keep)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SecondaryButton(label, enabled = enabled, compact = true) {
            val from = at ?: return@SecondaryButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = colors.error, pillCorner = 12.dp, items = 3,
                handle = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1) },
            ) {
                StepTitle(title)
                Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().stepItem(2)) {
                    StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(label, danger = true, enabled = open && canUninstall) { Steps.fold(); onUninstall() }
                }
            })
        }
    }
}

@Composable
private fun UserAppsProgressLine() {
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
            if (percent >= 0) androidx.compose.material3.LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
            else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        } else UserAppsState.lastError?.let { Note(it) }
    }
}
