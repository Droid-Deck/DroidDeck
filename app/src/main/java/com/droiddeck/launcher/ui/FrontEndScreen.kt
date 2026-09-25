package com.droiddeck.launcher.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import kotlin.math.roundToInt

class FrontEndState(
    val installed: String?,
    val ready: Boolean,
    val available: String?,
    val busy: Boolean,
    val stage: String,
    val percent: Int,
    val desktopInstalled: Boolean,
    val offlineAccount: String?,
    val offline: Boolean,
    val frameGenLabel: String,
    val romsDir: String?,
    val logsEnabled: Boolean,
    val steamGames: List<Library.SteamGame>,
    val emulators: List<Library.Emulator>,
    val running: String?,
    val frameGenEngine: String = FrameGen.ENGINE_OFF,
    val frameGenMultiplier: Int = 2,
    val lsfgReady: Boolean = false,
    val pageKey: String? = null,
    val theme: String = Themes.PAPER,
    val isHomeApp: Boolean = false,
    val homeScreenEnabled: Boolean = false,
    val defaultHomeLabel: String? = null,
    val androidApps: List<HomeApp.LaunchableApp> = emptyList(),
    val secondScreenDisplays: List<SecondScreenDisplay> = emptyList(),
    val packages: List<PackageRow>? = null,
    val packageCatalogLoading: Boolean = false,
    val packageBusyId: String? = null,
    val packageStage: String? = null,
    val packagePercent: Int = -1,
    val sessionRunning: Boolean = false,
    val backActionsInverted: Boolean = false,
    val buildLabel: String = "local",
)

class FrontEndActions(
    val onPlay: () -> Unit,
    val onPlayDesktopUi: () -> Unit,
    val onSteamGame: (Library.SteamGame) -> Unit,
    val onDesktop: () -> Unit,
    val onEmulator: (Library.Emulator) -> Unit,
    val onRom: (Library.Rom) -> Unit,
    val onResume: () -> Unit,
    val onSteamSettings: () -> Unit,
    val onDesktopSettings: () -> Unit,
    val onInstallPackage: (String) -> Unit,
    val onRemovePackage: (String) -> Unit,
    val onRuntime: () -> Unit,
    val onFrameGenPick: (engine: String, multiplier: Int) -> Unit,
    val onProtons: () -> Unit,
    val onPerformance: () -> Unit,
    val onRoms: () -> Unit,
    val onFiles: () -> Unit,
    val onLogs: () -> Unit,
    val onShareLogs: () -> Unit = {},
    val onOffline: () -> Unit,
    val onCredits: () -> Unit,
    val onPageBack: () -> Unit = {},
    val onTheme: (String) -> Unit = {},
    val onHomeApp: () -> Unit = {},
    val onHomeScreen: (Boolean) -> Unit = {},
    val onAndroidApp: (HomeApp.LaunchableApp, Int?) -> Unit = { _, _ -> },
    val onBackActionsInverted: (Boolean) -> Unit = {},
    val onCheckLatestBuild: () -> Unit = {},
)


internal object Motion {
    var scale = 1f
    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    fun ms(base: Int) = (base * scale).roundToInt()
    fun <T> tw(base: Int, delay: Int = 0): FiniteAnimationSpec<T> = if (scale == 0f) snap() else tween(ms(base), ms(delay), Ease)
    fun <T> sp(damping: Float = 0.7f, stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        if (scale == 0f) snap() else spring(damping, stiffness)
}

private val Shape10 = RoundedCornerShape(10.dp)
private val Shape12 = RoundedCornerShape(12.dp)

private fun hueOf(name: String) = (name.hashCode().toUInt() % 360u).toFloat()
private fun tint(h: Float, s: Float = 0.7f, v: Float = 0.58f) = Color.hsv(h, s, v)
private fun artBrush(h: Float) = Brush.linearGradient(listOf(tint(h), tint((h + 32f) % 360f, 0.65f, 0.30f), tint((h + 64f) % 360f, 0.6f, 0.14f)))

@Composable
internal fun Rise(i: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(
        visibleState = state, modifier = modifier,
        enter = fadeIn(Motion.tw(450, i * 60)) + slideInVertically(Motion.tw(450, i * 60)) { it / 3 },
        exit = fadeOut(Motion.tw(120)),
        label = "rise",
    ) { content() }
}

@Composable
private fun Modifier.staggerIn(i: Int): Modifier {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.tw(360, i * 30)) }
    return graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 10.dp.toPx() }
}

@Composable
private fun Modifier.shine(trigger: Boolean, strength: Float = 0.22f): Modifier {
    val x = remember { Animatable(-1f) }
    LaunchedEffect(trigger) { if (trigger) { x.snapTo(-1f); x.animateTo(1f, Motion.tw(800)) } }
    return drawWithContent {
        drawContent()
        val p = x.value
        if (p > -1f && p < 1f) {
            val w = size.width
            val c = w * 0.5f + p * w * 0.9f
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
                    start = Offset(c - w * 0.35f, 0f), end = Offset(c + w * 0.35f, size.height),
                ),
            )
        }
    }
}


/**
 * Controller focus on the front end: each rail item's requester and the page's main button (Play,
 * Open desktop, Open <emulator>, Launch...). The pane leaves, to the left, for the selected rail
 * item - whatever tile or button it leaves from - and remembers that control, so coming back in
 * lands on it again; a page not yet visited enters on its main button.
 */
private class FrontFocus {
    val rail = HashMap<String, FocusRequester>()
    val primary = FocusRequester()
    var primaryAttached by mutableStateOf(0)
    var railFocused by mutableStateOf(false)
    fun railFor(key: String): FocusRequester = rail.getOrPut(key) { FocusRequester() }
    // The pane's controls by id (a tile's key, a button's label), how many of each are on screen,
    // and the last one focused.
    val items = HashMap<String, FocusRequester>()
    val attached = HashMap<String, Int>()
    var last: String? = null
    // The first tile of the page's grid: Down from the page's buttons goes to it, not to whichever
    // tile happens to sit under the button.
    val firstTile = FocusRequester()
    var firstTileAttached = 0
    fun paneEntry(): FocusRequester {
        val id = last
        return when {
            id == PRIMARY && primaryAttached > 0 -> primary
            id != null && id != PRIMARY && (attached[id] ?: 0) > 0 -> items.getValue(id)
            primaryAttached > 0 -> primary
            else -> FocusRequester.Default
        }
    }
    companion object { const val PRIMARY = "\u0000primary" }
}

/** Down from this button goes to the first tile of the page's grid, when there is one. */
@Composable
private fun Modifier.downToFirstTile(): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    return this.focusProperties { down = if (ff.firstTileAttached > 0) ff.firstTile else FocusRequester.Default }
}

/** Marks the first tile of the page's grid. */
@Composable
private fun Modifier.firstTile(): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    DisposableEffect(Unit) {
        ff.firstTileAttached++
        onDispose { ff.firstTileAttached-- }
    }
    return this.focusRequester(ff.firstTile)
}

/** Lets the pane come back to this control: it is remembered when focused. */
@Composable
private fun Modifier.paneItem(id: String): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    val req = remember(id) { ff.items.getOrPut(id) { FocusRequester() } }
    DisposableEffect(id) {
        ff.attached[id] = (ff.attached[id] ?: 0) + 1
        onDispose { ff.attached[id] = (ff.attached[id] ?: 1) - 1 }
    }
    return this.focusRequester(req).onFocusChanged { if (it.isFocused) ff.last = id }
}

private val LocalFrontFocus = staticCompositionLocalOf<FrontFocus?> { null }

@Composable
fun FrontEndScreen(s: FrontEndState, a: FrontEndActions, page: (@Composable () -> Unit)? = null) {
    val frontFocus = remember { FrontFocus() }
    CompositionLocalProvider(LocalFrontFocus provides frontFocus) { FrontEndScreenBody(s, a, page, frontFocus) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FrontEndScreenBody(s: FrontEndState, a: FrontEndActions, page: (@Composable () -> Unit)?, frontFocus: FrontFocus) {
    var selected by rememberSaveable { mutableStateOf("steam") }
    var appToChooseDisplay by remember { mutableStateOf<HomeApp.LaunchableApp?>(null) }
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    BackHandler(enabled = s.pageKey != null && page != null) { a.onPageBack() }
    // Back (and B) from a game or an emulator steps out one level, as its "‹" link does, instead
    // of leaving the app: a game -> its emulator (or Steam), an emulator -> Desktop.
    BackHandler(
        enabled = (s.pageKey == null || page == null) &&
            (selected.startsWith("app:") || selected.startsWith("emu:") || selected.startsWith("rom:")),
    ) {
        selected = when {
            selected.startsWith("app:") -> "steam"
            selected.startsWith("emu:") -> "desktop"
            else -> "emu:" + selected.removePrefix("rom:").substringBefore(':')
        }
    }
    LaunchedEffect(s.isHomeApp) { if (!s.isHomeApp && selected == "android-apps") selected = "steam" }
    remember { Motion.scale = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f); true }

    var anyFocused by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(colors.background).systemBarsPadding().onFocusChanged { anyFocused = it.hasFocus }) {
        val wide = maxWidth >= 640.dp
        val railSelection = when {
            s.pageKey == "performance" || s.pageKey == "protons" -> "setup"
            s.pageKey?.startsWith("settings:steam") == true -> "steam"
            s.pageKey?.startsWith("settings:") == true -> "desktop"
            selected.startsWith("app:") -> "steam"
            selected.startsWith("emu:") || selected.startsWith("rom:") -> "desktop"
            else -> s.pageKey ?: selected
        }
        val rail: @Composable () -> Unit = {
            Rail(
                s, railSelection,
                onSelect = { key ->
                    if (s.pageKey != null) a.onPageBack()
                    selected = key
                },
                a,
                modifier = if (wide) Modifier.width(236.dp).fillMaxHeight() else Modifier.fillMaxWidth().height(maxHeight * 0.42f),
            )
        }
        // The app opens with the selected rail item focused, so a controller sees where it is. An
        // app starts in touch mode, where clickables refuse focus, so leave it first (a touch goes
        // straight back to it); a request before the window has focus is dropped, so wait for
        // that and retry until it sticks.
        val window = LocalWindowInfo.current
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            snapshotFlow { window.isWindowFocused }.first { it }
            repeat(20) {
                if (frontFocus.railFocused) return@LaunchedEffect
                if (inputMode.inputMode != InputMode.Keyboard) inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { frontFocus.railFor(railSelection).requestFocus() }
                kotlinx.coroutines.delay(100)
            }
        }
        // A tile or button that opens a page goes away with the page it was on, and focus with it;
        // the pad then had nothing to move from (a press landed back on the rail's first item). So
        // once the new page is in, a controller lands on its main button.
        val inputModeManager = LocalInputModeManager.current
        LaunchedEffect(selected, s.pageKey) {
            kotlinx.coroutines.delay(450)
            if (!anyFocused && inputModeManager.inputMode == InputMode.Keyboard) runCatching {
                if (frontFocus.primaryAttached > 0) frontFocus.primary.requestFocus()
                else frontFocus.railFor(railSelection).requestFocus()
            }
        }
        val back = if (wide) FocusDirection.Left else FocusDirection.Up
        val paneFocus = Modifier
            .focusProperties {
                enter = { frontFocus.paneEntry() }
                exit = { dir ->
                    if (dir == back) frontFocus.rail[railSelection] ?: FocusRequester.Default else FocusRequester.Default
                }
            }
            .focusGroup()
        val content: @Composable (Modifier) -> Unit = { m ->
            Pane(s, selected, a, page, m.then(paneFocus), { selected = it }) { app ->
                if (s.secondScreenDisplays.isEmpty()) a.onAndroidApp(app, null)
                else appToChooseDisplay = app
            }
        }
        if (wide) Row(modifier = Modifier.fillMaxSize()) { rail(); content(Modifier.weight(1f).fillMaxHeight()) }
        else Column(modifier = Modifier.fillMaxSize()) { rail(); content(Modifier.weight(1f).fillMaxWidth()) }

        appToChooseDisplay?.let { app ->
            val secondaryDisplay = s.secondScreenDisplays.firstOrNull()
            ChooseAppDisplayDialog(
                app = app,
                secondaryDisplay = secondaryDisplay,
                onPrimary = {
                    appToChooseDisplay = null
                    a.onAndroidApp(app, Display.DEFAULT_DISPLAY)
                },
                onSecondary = {
                    appToChooseDisplay = null
                    secondaryDisplay?.let { a.onAndroidApp(app, it.id) }
                },
                onDismiss = { appToChooseDisplay = null },
            )
        }
    }
}


@Composable
private fun Rail(
    s: FrontEndState, selected: String,
    onSelect: (String) -> Unit, a: FrontEndActions, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.background(colors.surface).padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)) {
                Image(painterResource(R.drawable.logo), null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("DroidDeck", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                    val status = when {
                        s.busy -> if (s.percent >= 0) "${s.stage} ${s.percent}%" else s.stage
                        !s.ready -> "runtime not installed"
                        s.available != null && s.available != s.installed -> "runtime update available"
                        else -> null
                    }
                    if (status != null) Text(status, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            AnimatedVisibility(s.busy, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }

            var lastRunning by remember { mutableStateOf("") }
            if (s.running != null) lastRunning = s.running
            AnimatedVisibility(
                s.running != null,
                enter = expandVertically(Motion.sp(0.75f)) + fadeIn(Motion.tw(300)) + slideInVertically(Motion.sp(0.6f)) { -it / 2 },
                exit = shrinkVertically(Motion.tw(220)) + fadeOut(Motion.tw(180)),
            ) { RunningTile(lastRunning, a.onResume) }

            val pal = LocalPalette.current
            var navOrigin by remember { mutableStateOf(Offset.Zero) }
            val positions = remember { mutableStateMapOf<String, Rect>() }
            Box(modifier = Modifier.fillMaxWidth().onGloballyPositioned { val o = it.positionInRoot(); if (o != navOrigin) navOrigin = o }) {
                val target = positions[selected]?.translate(-navOrigin)
                val y by animateFloatAsState(target?.top ?: 0f, Motion.sp(0.72f, Spring.StiffnessLow), label = "indY")
                val x by animateFloatAsState(target?.left ?: 0f, Motion.sp(0.9f), label = "indX")
                val h by animateFloatAsState(target?.height ?: 0f, Motion.sp(1f, Spring.StiffnessMediumLow), label = "indH")
                val w by animateFloatAsState(target?.width ?: 0f, Motion.sp(1f, Spring.StiffnessMediumLow), label = "indW")
                val alpha by animateFloatAsState(if (target != null) 1f else 0f, Motion.tw(250), label = "indA")
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                        .width(with(density) { w.toDp() }).height(with(density) { h.toDp() })
                        .alpha(alpha)
                        .graphicsLayer { shadowElevation = 6.dp.toPx(); shape = Shape10; clip = false; ambientShadowColor = pal.signal; spotShadowColor = pal.signal }
                        .clip(Shape10)
                        .background(Brush.linearGradient(listOf(colors.primary, pal.primary2))),
                )
                val register: (String, LayoutCoordinates) -> Unit = { key, c ->
                    val o = c.positionInRoot(); val r = Rect(o.x, o.y, o.x + c.size.width, o.y + c.size.height)
                    if (positions[key] != r) positions[key] = r
                }
                val unregister: (String) -> Unit = { positions.remove(it) }
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (s.isHomeApp) {
                        NavItem("Android apps", "android-apps", selected == "android-apps", count = s.androidApps.size,
                            register = register, unregister = unregister) { onSelect("android-apps") }
                    }
                    NavItem("Steam", "steam", selected == "steam", count = s.steamGames.size, register = register, unregister = unregister) { onSelect("steam") }
                    NavItem("Desktop", "desktop", selected == "desktop", count = s.emulators.count { it.installed }, register = register, unregister = unregister) { onSelect("desktop") }
                    NavItem("Setup", "setup", selected == "setup", register = register, unregister = unregister) { onSelect("setup") }
                    Spacer(Modifier.height(6.dp))
                }
            }

            val creditsSrc = remember { MutableInteractionSource() }
            val creditsHot = rememberHot(creditsSrc)
            Text(
                "credits", fontSize = 11.sp, color = if (creditsHot) LocalPalette.current.signal else colors.onSurfaceVariant,
                modifier = Modifier.clip(Shape10)
                    .border(2.dp, if (creditsHot) LocalPalette.current.signal else Color.Transparent, Shape10)
                    .hoverable(creditsSrc).clickable(interactionSource = creditsSrc, indication = null, onClick = a.onCredits)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        BuildStatus(
            label = s.buildLabel,
            onCheckLatest = a.onCheckLatestBuild,
            modifier = Modifier.align(Alignment.Start).padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun RunningTile(name: String, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shift by animateFloatAsState(if (hot) 3f else 0f, Motion.sp(0.6f), label = "runShift")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(0.4f, 1.6f, infiniteRepeatable(tween(1600, easing = Motion.Ease), RepeatMode.Restart), label = "ring")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            .graphicsLayer { translationX = shift.dp.toPx() }
            .clip(Shape12)
            .background(Brush.linearGradient(listOf(pal.signal.copy(alpha = 0.22f), pal.signal.copy(alpha = 0.06f))))
            .border(1.dp, pal.signal.copy(alpha = if (hot) 0.8f else 0.35f), Shape12)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onResume)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(14.dp).graphicsLayer { scaleX = ring; scaleY = ring; alpha = (1.6f - ring) / 1.2f }.border(1.5.dp, pal.good, CircleShape))
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(pal.good))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Resume", fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun NavItem(
    label: String, key: String, current: Boolean, small: Boolean = false, tiny: Boolean = false, muted: Boolean = false,
    caret: Boolean? = null, count: Int? = null, value: String? = null, i: Int = 0,
    register: ((String, LayoutCoordinates) -> Unit)? = null, unregister: ((String) -> Unit)? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val frontFocus = if (register != null && key != "x") LocalFrontFocus.current else null
    val railRequester = frontFocus?.railFor(key)
    if (frontFocus != null) LaunchedEffect(focused) { if (focused) frontFocus.railFocused = true }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val fg by animateColorAsState(if (current) colors.onPrimary else if (muted) colors.onSurfaceVariant else colors.onBackground, Motion.tw(280), label = "navFg")
    val sub by animateColorAsState(if (current) colors.onPrimary else colors.onSurfaceVariant, Motion.tw(280), label = "navSub")
    val ring by animateColorAsState(if (focused) LocalPalette.current.signal else Color.Transparent, Motion.tw(180), label = "navRing")
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "navScale")
    val rot by animateFloatAsState(if (caret == true) 90f else 0f, Motion.sp(0.6f), label = "caret")
    if (key != "x") DisposableEffect(key) { onDispose { unregister?.invoke(key) } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .then(if (register != null && key != "x") Modifier.onGloballyPositioned { register(key, it) } else Modifier)
            .then(if (railRequester != null) Modifier.focusRequester(railRequester) else Modifier)
            .then(if (small && i >= 0 && register != null) Modifier.staggerIn(i) else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape10)
            .background(if (hovered && !current) Color.White.copy(alpha = 0.04f) else if (current && hovered) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.5.dp, ring, Shape10)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (small) 7.dp else 9.dp),
    ) {
        if (caret != null) {
            Text("›", fontSize = 16.sp, color = sub, modifier = Modifier.width(14.dp).rotate(rot))
        }
        Text(
            label, fontSize = if (tiny) 13.sp else if (small) 14.sp else 15.sp,
            fontWeight = if (tiny) FontWeight.Normal else if (small) FontWeight.Medium else FontWeight.SemiBold,
            color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (value != null) Text(value, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text(
            count.toString(), fontSize = 11.sp, color = sub,
            modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(if (current) colors.onPrimary.copy(alpha = 0.16f) else colors.background).padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}


@Composable
private fun Pane(
    s: FrontEndState, selected: String, a: FrontEndActions, page: (@Composable () -> Unit)?, modifier: Modifier,
    onSelect: (String) -> Unit, onAndroidAppClick: (HomeApp.LaunchableApp) -> Unit,
) {
    Box(modifier = modifier) {
        val wash: Pair<File?, Float> = when {
            s.pageKey != null && page != null -> null to 250f
            selected == "steam" -> null to 268f
            selected == "desktop" -> null to 200f
            selected.startsWith("app:") -> s.steamGames.firstOrNull { "app:${it.appId}" == selected }.let { it?.art to hueOf(it?.name ?: "") }
            selected.startsWith("emu:") -> null to hueOf(selected)
            selected.startsWith("rom:") -> romFor(s, selected).let { it?.second?.art to hueOf(it?.second?.name ?: "") }
            selected == "android-apps" -> null to hueOf("android-apps")
            else -> null to 268f
        }
        Backdrop(wash)
        AnimatedContent(
            targetState = if (page != null && s.pageKey != null) s.pageKey else selected,
            transitionSpec = {
                (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                    .togetherWith(fadeOut(Motion.tw(170)) + slideOutVertically(Motion.tw(170)) { -it / 40 })
                    .apply { targetContentZIndex = 1f }
            },
            label = "pane",
        ) { key -> if (page != null && key == s.pageKey) page() else Content(s, key, a, Modifier.fillMaxSize(), onSelect, onAndroidAppClick) }
    }
}

@Composable
private fun BuildStatus(label: String, onCheckLatest: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hot = rememberHot(interaction)
    val contextDivider = label.indexOf(" · ")
    val context = if (contextDivider >= 0) label.substring(0, contextDivider) else ""
    val identity = if (contextDivider >= 0) label.substring(contextDivider + 3) else label
    val identityDivider = identity.lastIndexOf(" at ")
    val branch = (if (identityDivider >= 0) identity.substring(0, identityDivider) else identity).substringAfterLast('/')
    val commit = if (identityDivider >= 0) identity.substring(identityDivider + 4) else ""
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = "Build $label. Check for newest build." }
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onCheckLatest,
            )
            .heightIn(min = 44.dp)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(branch, modifier = Modifier.weight(1f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = if (hot) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (commit.isNotEmpty()) Text("@$commit", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant.copy(alpha = 0.82f), maxLines = 1)
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = if (hot) pal.signal else colors.onSurfaceVariant.copy(alpha = 0.82f), modifier = Modifier.size(12.dp))
        }
        if (context.isNotEmpty()) Text(context, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant.copy(alpha = 0.82f), maxLines = 1)
    }
}

@Composable
private fun Backdrop(wash: Pair<File?, Float>) {
    val colors = MaterialTheme.colorScheme
    Crossfade(targetState = wash, animationSpec = Motion.tw(700), label = "backdrop") { (art, hue) ->
        val canBlur = Build.VERSION.SDK_INT >= 31
        Box(modifier = Modifier.fillMaxSize().alpha(if (canBlur) 0.28f else 0.18f).then(if (canBlur) Modifier.blur(70.dp) else Modifier)) {
            if (art != null && canBlur) {
                AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(tint(hue, 0.8f, 0.55f), Color.Transparent), center = Offset(0.3f, 0.3f), radius = 900f)))
                Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(tint((hue + 60f) % 360f, 0.7f, 0.45f), Color.Transparent), center = Offset(1600f, 1400f), radius = 900f)))
            }
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
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
        when {
            selected == "android-apps" && s.isHomeApp -> {
                Rise(0) { Eyebrow("Android apps") }
                Rise(1) { Title("Installed apps") }
                Rise(2) { SectionTitle("Apps", s.androidApps.size.toString()) }
                if (s.androidApps.isEmpty()) Rise(3) { Note("No launchable Android apps found.") }
                else Rise(3, Modifier.weight(1f).fillMaxWidth()) {
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
            selected == "steam" -> {
                Rise(0) { Eyebrow("Steam") }
                Rise(3) {
                    Actions {
                        // Enabled without a runtime: the session's loading screen installs it first.
                        PrimaryButton("Play", enabled = !s.busy, main = true, onClick = a.onPlay)
                        SecondaryButton("Steam Desktop UI", enabled = !s.busy, onClick = a.onPlayDesktopUi)
                        Cog(a.onSteamSettings)
                    }
                }
                Rise(4) { SectionTitle("Installed", "${s.steamGames.size} game${if (s.steamGames.size == 1) "" else "s"}") }
                if (s.steamGames.isEmpty()) Rise(5) { Note("No games installed.") }
                else Rise(5, Modifier.weight(1f).fillMaxWidth()) { ArtGrid(s.steamGames.map { g -> Tile(g.name, g.library, g.art, "steam:${g.appId}", null) { onSelect("app:${g.appId}") } }, landscape = true) }
            }
            selected.startsWith("app:") -> {
                val g = s.steamGames.firstOrNull { "app:${it.appId}" == selected }
                if (g == null) Note("That game is no longer installed.") else {
                Rise(0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "‹ Steam", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect("steam") }.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                        Eyebrow(g.library)
                    }
                }
                    Rise(1) { Title(g.name) }
                    Rise(2) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Actions {
                                    PrimaryButton("Launch", enabled = s.ready && !s.busy, main = true) { a.onSteamGame(g) }
                                    Cog(a.onSteamSettings)
                                    Chip(if (s.ready) "● ready" else "runtime missing", ok = s.ready)
                                }
                            }
                            Poster(g.art, g.name, Modifier.width(240.dp), landscape = true)
                        }
                    }
                    val others = s.steamGames.filter { it !== g }
                    if (others.isNotEmpty()) {
                        Rise(3) { SectionTitle("More from the library", null) }
                        Rise(4, Modifier.weight(1f).fillMaxWidth()) { ArtGrid(others.map { x -> Tile(x.name, x.library, x.art, "steam:${x.appId}", null) { onSelect("app:${x.appId}") } }, landscape = true) }
                    }
                }
            }
            selected == "desktop" -> {
                Rise(0) { Eyebrow("Desktop") }
                Rise(3) {
                    Actions {
                        // Enabled without a runtime or the desktop: the session's loading screen installs them first.
                        PrimaryButton(if (s.desktopInstalled) "Open desktop" else "Install & open desktop", enabled = !s.busy, main = true, onClick = a.onDesktop)
                        Cog(a.onDesktopSettings)
                    }
                }
                Rise(4) { SectionTitle("Emulators", "${s.emulators.count { it.installed }} installed · ${s.emulators.count { !it.installed }} available") }
                Rise(5, Modifier.weight(1f).fillMaxWidth()) {
                    ArtGrid(s.emulators.map { e -> Tile(e.name, if (e.installed) (if (e.id == "retroarch") null else "${e.games.size} game${if (e.games.size == 1) "" else "s"}") else "Select to install", null, "emu:${e.id}", e.iconRes, dim = !e.installed) { onSelect("emu:${e.id}") } })
                }
            }
            selected == "setup" -> SetupPanel(s, a)
            selected.startsWith("emu:") -> {
                val e = s.emulators.firstOrNull { "emu:${it.id}" == selected }
                if (e == null) Note("Not installed.") else {
                    val pkgId = Library.packageId(e.id)
                    val pkg = pkgId?.let { id -> s.packages?.firstOrNull { it.id == id } }
                    Rise(0) {
                        Text(
                            "‹ Desktop", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect("desktop") }.padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    Rise(1) { Title(e.name) }
                    if (e.installed) {
                        Rise(3) {
                            Actions {
                                Image(painterResource(e.iconRes), null, modifier = Modifier.size(40.dp))
                                PrimaryButton("Open ${e.name}", enabled = s.ready && !s.busy, main = true) { a.onEmulator(e) }
                                SecondaryButton("ROMs folder", onClick = a.onRoms)
                                if (pkg != null) SecondaryButton(
                                    if (pkg.kind == "appimage") "Remove" else "Forget",
                                    enabled = s.packageBusyId == null && !s.sessionRunning,
                                ) { a.onRemovePackage(pkg.id) }
                            }
                        }
                        Rise(4) { SectionTitle("Games", e.games.size.toString()) }
                        if (e.games.isEmpty()) Rise(5) {
                            Note(
                                if (s.romsDir == null) "Choose a ROMs folder."
                                else if (e.id == "retroarch") "Browse to /root/ROMs in RetroArch."
                                else "Add ${e.system} games to ROMs/${e.system.substringBefore(' ')}.",
                            )
                        }
                        else Rise(5, Modifier.weight(1f).fillMaxWidth()) {
                            ArtGrid(e.games.mapIndexed { index, g -> Tile(g.name, if (g.art != null) "installed" else g.hostPath.extension.uppercase().ifEmpty { "folder" }, g.art, "rom:${e.id}:$index", e.iconRes) { onSelect("rom:${e.id}:$index") } }, wide = e.games.none { it.art != null })
                        }
                    } else {
                        if (pkg != null) Rise(2) {
                            Actions {
                                Image(painterResource(e.iconRes), null, modifier = Modifier.size(40.dp))
                                PrimaryButton(
                                    if (s.packageBusyId == pkg.id) "Installing…" else "Install ${e.name}",
                                    enabled = s.packageBusyId == null && s.ready && !s.packageCatalogLoading && !s.sessionRunning,
                                ) { a.onInstallPackage(pkg.id) }
                                if (s.sessionRunning) Chip("Stop session to install", ok = false)
                                else if (!s.ready) Chip("Runtime required", ok = false)
                            }
                        }
                        Rise(3) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Note(when {
                                    s.packageCatalogLoading -> "Loading install details…"
                                    pkg == null -> "Install details are unavailable right now. Try again when the package catalog is reachable."
                                    !s.ready -> "Install the Linux runtime from Setup before installing desktop apps."
                                    s.sessionRunning -> "Stop the active session before installing desktop apps."
                                    pkg.notes.isNotBlank() -> pkg.notes
                                    else -> "Install ${e.name} into the Linux desktop runtime."
                                })
                            }
                        }
                        if (s.packageBusyId == pkg?.id) Rise(4) {
                            val stage = s.packageStage
                            Text(
                                if (stage != null && s.packagePercent >= 0) "$stage · ${s.packagePercent}%" else stage ?: "Starting…",
                                fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (s.packagePercent >= 0) LinearProgressIndicator(progress = { s.packagePercent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                        if (pkg?.kind == "tar") Rise(5) { Note("Forgetting this package hides it from Desktop; its files remain in the Linux runtime.") }
                    }
                }
            }
            selected.startsWith("rom:") -> {
                val pair = romFor(s, selected)
                if (pair == null) Note("That game is gone from the ROMs folder.") else {
                    val (e, g) = pair
                    Rise(0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "‹ ${e.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect("emu:${e.id}") }.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                            Eyebrow("Desktop · ${e.system}")
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
                                    PrimaryButton("Launch in ${e.name}", enabled = s.ready && !s.busy, main = true) { a.onRom(g) }
                                    Chip(g.hostPath.extension.uppercase().ifEmpty { "folder" }, ok = false)
                                }
                            }
                            if (g.art != null) Poster(g.art, g.name, Modifier.width(120.dp))
                        }
                    }
                    val others = e.games.filter { it !== g }
                    if (others.isNotEmpty()) {
                        Rise(3) { SectionTitle("Also in ${e.name}", null) }
                        Rise(4, Modifier.weight(1f).fillMaxWidth()) {
                            ArtGrid(others.map { x ->
                                val index = e.games.indexOf(x)
                                Tile(x.name, if (x.art != null) "installed" else x.hostPath.extension.uppercase().ifEmpty { "folder" }, x.art, "rom:${e.id}:$index", e.iconRes) { onSelect("rom:${e.id}:$index") }
                            }, wide = others.none { it.art != null })
                        }
                    }
                }
            }
            else -> Note("Select an item.")
        }
    }
}

@Composable
private fun SetupPanel(s: FrontEndState, a: FrontEndActions) {
    val host = rememberMenuHost()
    val runtime = when {
        s.busy -> "Working…"
        !s.ready -> "Install"
        s.available != null && s.available != s.installed -> "Update"
        else -> "Manage"
    }
    Rise(0, Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Rise(0) { Eyebrow("Setup") }
                Rise(1) { Title("Setup") }
                SettingsGroup("Launcher tools") {
                    ActionRow("Files", "Browse and manage files", "Open", a.onFiles)
                    ActionRow("Compatibility tools", "Install ARM64 Proton builds", "Manage", a.onProtons)
                    ActionRow("Performance", "CPU core assignment", "Configure", a.onPerformance)
                    ActionRow("ROMs folder", s.romsDir ?: "Choose where emulator games are stored", "Choose", a.onRoms)
                }
                SettingsGroup("Session") {
                    ChoiceRow(
                        host, "back-actions", "Back", SessionPrefs.backActionsOrder(s.backActionsInverted),
                        listOf(
                            false to SessionPrefs.BACK_MENU_THEN_QAM,
                            true to SessionPrefs.BACK_QAM_THEN_MENU,
                        ), s.backActionsInverted, onPick = a.onBackActionsInverted,
                    )
                    SettingsRow("Frame generation", "Select the frame generation mode") {
                        Box {
                            ValueChip(s.frameGenLabel, host.open == "fg") { host.open = if (host.open == "fg") null else "fg" }
                            AnchoredMenu(host.open == "fg", onDismiss = { if (host.open == "fg") host.open = null }, title = "Frame generation") { firstItemFocus ->
                                val need = if (s.lsfgReady) null else "Install Lossless Scaling in Steam"
                                MenuItem("Off", checked = s.frameGenEngine == FrameGen.ENGINE_OFF, focusRequester = firstItemFocus) { a.onFrameGenPick(FrameGen.ENGINE_OFF, 2); host.open = null }
                                for (m in 2..4) MenuItem("Win-FG ${m}×", checked = s.frameGenEngine == FrameGen.ENGINE_WINFG && s.frameGenMultiplier == m) { a.onFrameGenPick(FrameGen.ENGINE_WINFG, m); host.open = null }
                                for (m in 2..4) MenuItem("LSFG ${m}×", checked = s.frameGenEngine == FrameGen.ENGINE_LSFG && s.frameGenMultiplier == m, enabled = s.lsfgReady, detail = need) { a.onFrameGenPick(FrameGen.ENGINE_LSFG, m); host.open = null }
                            }
                        }
                    }
                    SettingsRow("Session logs", "${if (s.logsEnabled) "Enabled" else "Disabled"} · logs are saved after each session") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SecondaryButton(if (s.logsEnabled) "Turn off" else "Turn on") { a.onLogs() }
                            SecondaryButton("Share latest") { a.onShareLogs() }
                        }
                    }
                    SettingsRow("Offline mode", s.offlineAccount?.let { if (s.offline) "Enabled for $it" else "Signed in as $it" } ?: "Sign in to Steam first") {
                        SecondaryButton(if (s.offline) "Turn off" else "Turn on", enabled = s.offlineAccount != null) { a.onOffline() }
                    }
                }
                SettingsGroup("Application") {
                    ActionRow("Linux runtime", "${s.installed ?: "Not installed"}${if (s.available != null && s.available != s.installed) " · update available" else ""}", runtime, a.onRuntime)
                    SettingsRow("Theme", "Choose the launcher appearance") {
                        Box {
                            ValueChip(Themes.byId(s.theme).label, host.open == "theme") { host.open = if (host.open == "theme") null else "theme" }
                            AnchoredMenu(host.open == "theme", onDismiss = { if (host.open == "theme") host.open = null }, title = "Theme") { firstItemFocus ->
                                Themes.all.forEachIndexed { index, theme ->
                                    MenuItem(theme.label, checked = s.theme == theme.id, focusRequester = if (index == 0) firstItemFocus else null) {
                                        a.onTheme(theme.id)
                                        host.open = null
                                    }
                                }
                            }
                        }
                    }
                    ToggleRow(
                        host, "home-screen", "Use as a Home screen",
                        if (s.homeScreenEnabled) "DroidDeck can be the phone's Home app" else "Off: DroidDeck is never offered as a Home app",
                        s.homeScreenEnabled,
                    ) { a.onHomeScreen(it) }
                    if (s.homeScreenEnabled) {
                        ActionRow("Default Home app", s.defaultHomeLabel ?: "Choose a Home app", "Choose", a.onHomeApp)
                    }
                }
        }
    }
}

private class Tile(
    val title: String, val sub: String?, val art: File?, val key: String,
    val iconRes: Int? = null, val dim: Boolean = false, val iconBitmap: Bitmap? = null,
    val onClick: () -> Unit,
)


@Composable
internal fun Eyebrow(t: String) {
    val pal = LocalPalette.current
    val rule = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rule.animateTo(1f, Motion.tw(600, 120)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.width(18.dp).height(1.5.dp).graphicsLayer { scaleX = rule.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(pal.signal))
        Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = pal.signal)
    }
}
@Composable internal fun Title(t: String) = Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
@Composable internal fun Lede(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
@Composable
private fun SectionTitle(t: String, detail: String?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = colors.onSurfaceVariant)
        if (detail != null) Text(detail, fontSize = 11.sp, color = colors.onBackground)
        Box(modifier = Modifier.weight(1f).height(1.dp).background(LocalPalette.current.line))
    }
}
@Composable private fun Note(t: String) = Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().clip(Shape12).background(MaterialTheme.colorScheme.surface).border(1.dp, LocalPalette.current.line2, Shape12).padding(12.dp))
@Composable private fun Actions(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }

@Composable
private fun Chip(t: String, ok: Boolean) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Text(
        t, fontSize = 11.sp, color = if (ok) pal.good else colors.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(colors.surfaceVariant).border(1.dp, if (ok) pal.good.copy(alpha = 0.3f) else pal.line, RoundedCornerShape(99.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun rememberHot(src: MutableInteractionSource): Boolean = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, main: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    // The page's main button is where the pane is entered from the rail.
    val frontFocus = if (main) LocalFrontFocus.current else null
    if (frontFocus != null) DisposableEffect(Unit) {
        frontFocus.primaryAttached++
        onDispose { frontFocus.primaryAttached-- }
    }
    val track = (if (frontFocus == null) Modifier.paneItem("btn:$text") else Modifier).downToFirstTile()
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else if (hot) 1.02f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "btnScale")
    val lift by animateFloatAsState(if (hot) 14f else 6f, Motion.tw(300), label = "btnLift")
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = track
            .then(if (frontFocus != null) Modifier.focusRequester(frontFocus.primary).onFocusChanged { if (it.isFocused) frontFocus.last = FrontFocus.PRIMARY } else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (enabled) lift.dp.toPx() else 0f; shape = Shape12; clip = false; ambientShadowColor = pal.signal; spotShadowColor = pal.signal }
            .clip(Shape12)
            .background(if (enabled) Brush.linearGradient(listOf(colors.primary, pal.primary2)) else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surfaceVariant)))
            .shine(hot, 0.45f)
            // The grow and shine alone barely show on the light fill: outline it when a
            // controller is on it, as the other controls are.
            .border(2.dp, if (hot) pal.signal else Color.Transparent, Shape12)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = if (enabled) colors.onPrimary else colors.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else if (hot) 1.02f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "secScale")
    val pal = LocalPalette.current
    val edge by animateColorAsState(if (hot) pal.signal else pal.line2, Motion.tw(250), label = "secEdge")
    val fill by animateColorAsState(if (hot) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f), Motion.tw(250), label = "secFill")
    Box(
        modifier = Modifier.paneItem("btn:$text").downToFirstTile().graphicsLayer { scaleX = scale; scaleY = scale }.clip(Shape12).background(fill).border(1.dp, edge, Shape12)
            .alpha(if (enabled) 1f else 0.5f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = colors.onBackground, maxLines = 1) }
}

@Composable
private fun Cog(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val rot by animateFloatAsState(if (hot) 90f else 0f, Motion.sp(0.55f), label = "cog")
    val pal = LocalPalette.current
    val edge by animateColorAsState(if (hot) pal.signal else pal.line2, Motion.tw(250), label = "cogEdge")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("cog").downToFirstTile().size(42.dp).clip(Shape12).background(Color.White.copy(alpha = 0.03f)).border(1.dp, edge, Shape12)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
    ) { Icon(Icons.Filled.Settings, "Settings", tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(18.dp).rotate(rot)) }
}

@Composable
private fun Poster(art: File?, name: String, modifier: Modifier, landscape: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.sp(0.6f, Spring.StiffnessLow)) }
    Box(
        modifier = modifier.padding(start = 16.dp).aspectRatio(if (landscape) HEADER_RATIO else 2f / 3f)
            .graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 16.dp.toPx(); rotationZ = (1f - t.value) * 2f; scaleX = 0.94f + 0.06f * t.value; scaleY = scaleX; shadowElevation = 22.dp.toPx(); shape = Shape12; clip = false }
            .clip(Shape12).background(artBrush(hueOf(name))),
    ) {
        if (art != null) CoverImage(art, Modifier.fillMaxSize(), landscape)
        else Text(name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}


private const val HEADER_RATIO = 460f / 215f

@Composable
private fun landscapeColumns(avail: androidx.compose.ui.unit.Dp, gap: androidx.compose.ui.unit.Dp): Int {
    val metrics = LocalContext.current.resources.displayMetrics
    val nominal = metrics.densityDpi.toFloat()
    val dpi = if (metrics.xdpi > nominal * 0.6f && metrics.xdpi < nominal * 1.6f) metrics.xdpi else nominal
    val mmPerDp = metrics.density / dpi * 25.4f
    val widthMm = avail.value * mmPerDp
    val gapMm = gap.value * mmPerDp
    return ((widthMm + gapMm) / (MIN_LANDSCAPE_TILE_MM + gapMm)).toInt().coerceIn(2, 4)
}

private const val MIN_LANDSCAPE_TILE_MM = 34f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtGrid(tiles: List<Tile>, wide: Boolean = false, landscape: Boolean = false) {
    val square = tiles.isNotEmpty() && tiles.all { it.art == null && (it.iconRes != null || it.iconBitmap != null) }
    // Thumbnails to recognise a game by, not posters; icon tiles are squares.
    val minSize = if (square) 64.dp else if (wide) 92.dp else 70.dp
    val gap = 8.dp
    // Laid out whole, not lazily: the pad's focus search only finds tiles that exist, and a lazy
    // grid composes only the rows on screen, so a press towards the next row bounced back among
    // the visible tiles. A few hundred tiles lay out fine; the scroll follows the focused one.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val avail = maxWidth - 8.dp
        val cols = if (landscape) landscapeColumns(avail, gap) else ((avail + gap) / (minSize + gap)).toInt().coerceAtLeast(1)
        val tileWidth = (avail - gap * (cols - 1)) / cols
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 14.dp, start = 4.dp, end = 4.dp)) {
            for (row in tiles.chunked(cols)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth().padding(bottom = gap)) {
                    for (t in row) key(t.key) {
                        val src = remember { MutableInteractionSource() }
                        val hot = rememberHot(src)
                        val track = Modifier.paneItem("tile:" + t.key).then(if (t === tiles.first()) Modifier.firstTile() else Modifier)
                        Box(modifier = Modifier.width(tileWidth).zIndex(if (hot) 1f else 0f)) { GameTile(t, wide, square, landscape, src, hot, track) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameTile(t: Tile, wide: Boolean, square: Boolean, landscape: Boolean, src: MutableInteractionSource, hot: Boolean, track: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else if (hot) 1.04f else 1f, Motion.sp(0.55f, Spring.StiffnessMedium), label = "tileScale")
    val lift by animateFloatAsState(if (hot) -5f else 0f, Motion.sp(0.6f), label = "tileLift")
    val elev by animateFloatAsState(if (hot) 18f else 2f, Motion.tw(300), label = "tileElev")
    val pal = LocalPalette.current
    val ring by animateColorAsState(if (hot) pal.signal else Color.Transparent, Motion.tw(220), label = "tileRing")
    Column(
        modifier = track
            .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift.dp.toPx(); shadowElevation = elev.dp.toPx(); shape = Shape12; clip = false; ambientShadowColor = if (hot) pal.signal else Color.Black; spotShadowColor = if (hot) pal.signal else Color.Black; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.9f) }
            .clip(Shape12)
            .background(colors.surface)
            .border(1.5.dp, ring, Shape12)
            .alpha(if (t.dim && !hot) 0.55f else 1f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = t.onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().shine(hot)) {
            Art(t.art, t.iconRes, t.title, Modifier.fillMaxWidth(), wide, t.iconBitmap, landscape)
            androidx.compose.animation.AnimatedVisibility(
                visible = hot, modifier = Modifier.align(Alignment.Center),
                enter = scaleIn(Motion.sp(0.5f), initialScale = 0.5f) + fadeIn(Motion.tw(200)),
                exit = scaleOut(Motion.tw(150), targetScale = 0.6f) + fadeOut(Motion.tw(150)),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(if (square) 26.dp else 32.dp).graphicsLayer { shadowElevation = 10.dp.toPx(); shape = CircleShape; clip = false; spotShadowColor = pal.signal }.clip(CircleShape).background(pal.signal),
                ) { Text("›", fontSize = if (square) 16.sp else 20.sp, color = Color.White) }
            }
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(t.title, fontSize = if (landscape) 12.sp else 10.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.sub != null) Text(t.sub, fontSize = if (landscape) 10.sp else 8.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Art for a portrait (2:3) tile. Box art fills it; wide art - a PS3 disc's ICON0, a game's header -
 * is shown whole over a blurred, darkened copy of itself instead of losing its sides to the crop.
 */
@Composable
private fun CoverImage(art: File, modifier: Modifier, landscape: Boolean = false) {
    var mismatch by remember(art, landscape) { mutableStateOf(false) }
    Box(modifier) {
        if (mismatch) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }.blur(14.dp),
            )
            Spacer(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        AsyncImage(
            model = art, contentDescription = null,
            contentScale = if (mismatch) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                mismatch = (size.width > size.height * 1.1f) != landscape
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Art(art: File?, iconRes: Int?, label: String, modifier: Modifier, wide: Boolean = false, iconBitmap: Bitmap? = null, landscape: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val ratio = if (landscape) HEADER_RATIO else if (art == null && (iconRes != null || iconBitmap != null)) 1f else if (wide) 16f / 9f else 2f / 3f
    Box(modifier = modifier.aspectRatio(ratio).background(if (art == null && iconRes == null && iconBitmap == null) artBrush(hueOf(label)) else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surface)))) {
        when {
            art != null && landscape -> CoverImage(art, Modifier.fillMaxSize(), landscape = true)
            art != null -> if (wide) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else CoverImage(art, Modifier.fillMaxSize())
            iconRes != null -> Image(painterResource(iconRes), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(if (wide) 10.dp else 8.dp))
            iconBitmap != null -> Image(bitmap = iconBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            else -> {
                Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.92f), modifier = Modifier.align(Alignment.BottomStart).padding(5.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
