package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoresState

/** What the Library pretends while a developer previews it. Nothing here is saved: a restart always comes back to [OFF]. */
internal enum class LibraryPreview { OFF, NOTHING_INSTALLED, SIGNED_OUT, SAMPLE }

internal enum class DrawerPreview { STEAM, DESKTOP, GAME }

/** Developer tools' preview states: screens that are otherwise hard to reach, shown without changing anything real. */
internal object DevPreview {
    var library by mutableStateOf(LibraryPreview.OFF)
    var drawer by mutableStateOf<DrawerPreview?>(null)
    /** The UI zoo sheet: where previews are picked, one at a time, only when asked for. */
    var zoo by mutableStateOf(false)
    /** Opt-in: a preview button where its state lives (the Library's toolbar), for switching states without the zoo. */
    var shortcuts by mutableStateOf(false)

    fun setShortcuts(context: android.content.Context, on: Boolean) {
        shortcuts = on
        SessionPrefs.setPreviewShortcuts(context, on)
    }

    /** Set when a Library preview is picked, so the launcher switches to the Library to show it. */
    var openLibrary by mutableStateOf(false)
}

/** How many of each store's sample games look installed. */
private const val SAMPLE_INSTALLED = 3

/** A store's storefront games, passed off as owned, for the sample library. Empty until the store's shelves have loaded. */
internal fun sampleOwned(store: Store): List<CatalogItem> =
    StoresState.shelves[store]?.all.orEmpty().filter { !it.mature }.distinctBy { it.id }.take(24).map { it.copy(owned = true) }

internal fun sampleInstalled(owned: Map<Store, List<CatalogItem>>): Set<String> =
    owned.values.flatMap { items -> items.take(SAMPLE_INSTALLED).map { it.key } }.toSet()

/** The pill a previewed Library shows, so the fake state is never mistaken for the real one. */
@Composable
internal fun LibraryPreviewPill(modifier: Modifier = Modifier) {
    val preview = DevPreview.library
    if (preview == LibraryPreview.OFF) return
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val label = stringResource(
        when (preview) {
            LibraryPreview.NOTHING_INSTALLED -> R.string.dev_preview_library_nothing_installed
            LibraryPreview.SIGNED_OUT -> R.string.dev_preview_library_signed_out
            else -> R.string.dev_preview_library_sample
        },
    )
    val end = stringResource(R.string.dev_preview_end)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.background(colors.surface, CircleShape).border(1.dp, pal.signal, CircleShape)
            .clickable(role = Role.Button, onClickLabel = end) { DevPreview.library = LibraryPreview.OFF }
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).testTag("dev-preview-end"),
    ) {
        Icon(Icons.Outlined.Visibility, null, tint = pal.signal, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.dev_preview_banner, label), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Icon(Icons.Filled.Close, end, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * The in-game menu over the launcher, for looking at without starting a session. Its switches change only this preview: nothing
 * is saved and no session is told. Back or Close ends it.
 */
@Composable
internal fun SessionDrawerPreview(secondScreens: List<SecondScreenDisplay>) {
    val mode = DevPreview.drawer ?: return
    val ctx = LocalContext.current
    val close = { DevPreview.drawer = null }
    var page by remember { mutableStateOf(SessionDrawerPage.CONTROLLER) }
    // A session's activity routes these keys to its menu; the launcher has no such routing, so the preview does it.
    var layerOpen by remember { mutableStateOf(false) }
    var backRequests by remember { mutableStateOf(0) }
    val back = { if (layerOpen) backRequests++ else close() }
    BackHandler { back() }
    var hud by remember { mutableStateOf(SessionPrefs.hudEnabled(ctx)) }
    var touch by remember { mutableStateOf(SessionPrefs.touchMode(ctx)) }
    var osc by remember { mutableStateOf(SessionPrefs.oscMode(ctx)) }
    var fex by remember { mutableStateOf(SessionPrefs.fexPreset(ctx)) }
    var inverted by remember { mutableStateOf(SessionPrefs.backActionsInverted(ctx)) }
    var suspend by remember { mutableStateOf(SessionPrefs.SUSPEND_MANUAL) }
    var frameGen by remember { mutableStateOf(FrameGen.Mode.OFF) }
    var second by remember { mutableStateOf(SecondScreenMode.NONE) }
    var fps by remember { mutableStateOf(0) }
    val steam = mode == DrawerPreview.STEAM
    val actions = DrawerActions(
        steam = steam,
        desktop = mode == DrawerPreview.DESKTOP,
        title = if (mode == DrawerPreview.GAME) stringResource(R.string.session_title_game) else null,
        isHomeApp = false,
        androidApps = emptyList(),
        hudOn = hud, onHud = { hud = it },
        frameGen = frameGen, onFrameGenPick = { frameGen = it },
        lossless = Lossless.State.NONE,
        oscMode = osc, onOsc = { osc = it },
        suspendPolicy = suspend, onSuspendPolicy = { suspend = it },
        backActionsInverted = inverted, onBackActionsInverted = { inverted = it },
        touchMode = touch, onTouch = { touch = it },
        touchAuto = stringResource(R.string.session_touch_auto_direct),
        fexPreset = fex, onFexPreset = { fex = it },
        fpsLimit = fps, onFpsLimit = { fps = it },
        secondScreenMode = second, onSecondScreenMode = { second = it },
        secondScreenDisplays = secondScreens,
        selectedSecondScreenDisplay = secondScreens.firstOrNull()?.id ?: -1,
        onSecondScreenDisplay = {},
        onImportLossless = {},
        onKeyboard = {}, onHardwareKeyboard = {},
        onSteamMenu = if (steam) ({}) else null,
        onQam = if (steam) ({}) else null,
        onLaunchAndroidApp = { _, _ -> },
        onBackground = close,
        onShareLogs = {},
        onStop = close,
        onClose = close,
    )
    Box(
        Modifier.fillMaxSize().testTag("dev-preview-drawer")
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                if (code != android.view.KeyEvent.KEYCODE_BUTTON_L1 && code != android.view.KeyEvent.KEYCODE_BUTTON_R1) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    page = sessionDrawerPages().step(page, if (code == android.view.KeyEvent.KEYCODE_BUTTON_R1) 1 else -1)
                }
                true
            }
            .onKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BUTTON_B) return@onKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) back()
                true
            }
            .background(Brush.linearGradient(listOf(Color(0xFF1B2A3A), Color(0xFF0B0F14), Color(0xFF2A1B2E)))),
    ) {
        Text(
            stringResource(R.string.dev_preview_drawer_backdrop), fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
        )
        SessionDrawer(
            true, page, controllerActive = true, onPageChange = { page = it }, a = actions,
            backRequest = backRequests, onLayerChange = { layerOpen = it },
        )
    }
}

/** Developer tools' UI zoo: a sheet listing the previews. Nothing shows until one is picked, and picking one closes the sheet. */
@Composable
internal fun UiZooSheet() {
    val close = { DevPreview.zoo = false }
    SideSheet(DevPreview.zoo, onClose = close, returnFocus = null, tag = "ui-zoo-sheet") {
        PageHeader(stringResource(R.string.dev_ui_zoo)) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            SmallIconButton(Icons.Filled.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant) { close() }
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val show = stringResource(R.string.dev_ui_zoo_show)
            SettingsGroup(stringResource(R.string.dev_preview_drawer)) {
                for ((mode, label) in listOf(
                    DrawerPreview.STEAM to R.string.source_steam,
                    DrawerPreview.DESKTOP to R.string.rail_desktop,
                    DrawerPreview.GAME to R.string.session_title_game,
                )) ActionRow(stringResource(label), null, show, onClick = { close(); DevPreview.drawer = mode }, modifier = Modifier.testTag("zoo-drawer-${mode.name.lowercase()}"))
            }
            SettingsGroup(stringResource(R.string.dev_preview_library)) {
                for ((mode, label) in listOf(
                    LibraryPreview.NOTHING_INSTALLED to R.string.dev_preview_library_nothing_installed,
                    LibraryPreview.SIGNED_OUT to R.string.dev_preview_library_signed_out,
                    LibraryPreview.SAMPLE to R.string.dev_preview_library_sample,
                )) ActionRow(
                    stringResource(label), null, show,
                    onClick = { close(); DevPreview.library = mode; DevPreview.openLibrary = true },
                    modifier = Modifier.testTag("zoo-library-${mode.name.lowercase()}"),
                )
            }
            Text(
                stringResource(R.string.dev_ui_zoo_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** The Library toolbar's preview button, shown only with preview shortcuts on: picks the Library state in place. */
@Composable
internal fun LibraryPreviewButton() {
    val pal = LocalPalette.current
    val title = stringResource(R.string.dev_preview_library)
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val states = listOf(
        LibraryPreview.OFF to R.string.dev_preview_real,
        LibraryPreview.NOTHING_INSTALLED to R.string.dev_preview_library_nothing_installed,
        LibraryPreview.SIGNED_OUT to R.string.dev_preview_library_signed_out,
        LibraryPreview.SAMPLE to R.string.dev_preview_library_sample,
    )
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        HeaderIconButton(stringResource(R.string.dev_preview_shortcut), Icons.Outlined.Science, "library-preview") {
            val from = at ?: return@HeaderIconButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = pal.signal, pillCorner = 8.dp, items = states.size,
                handle = { Icon(Icons.Outlined.Science, null, tint = pal.signal, modifier = Modifier.size(18.dp)) },
            ) {
                StepTitle(title)
                StepList {
                    states.forEachIndexed { i, (state, label) ->
                        StepChoice(
                            stringResource(label), selected = DevPreview.library == state, enabled = open,
                            modifier = Modifier.fillMaxWidth().stepItem(i).let { if (i == 0) it.focusRequester(first) else it },
                        ) { DevPreview.library = state; Steps.fold() }
                    }
                }
            })
        }
    }
}
