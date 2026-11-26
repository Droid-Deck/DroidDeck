package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.SecondScreenMode

/** The launcher as a whole: rail and page, opened on each tab through FrontEndState.navigate. */
@Composable
private fun Launcher(tab: String, games: List<com.droiddeck.launcher.frontend.Library.SteamGame> = sampleGames, ready: Boolean = true) =
    PreviewHost { FrontEndScreen(sampleState(games = games, navigate = tab, ready = ready), sampleActions) }

@DroidDeckPreviews @Composable private fun SteamTabPreview() = Launcher("steam")
@DroidDeckPreviews @Composable private fun SteamEmptyPreview() = Launcher("steam", games = emptyList())
@DroidDeckPreviews @Composable private fun LibraryPreview() = Launcher("games")
@DroidDeckPreviews @Composable private fun LibraryEmptyPreview() = Launcher("games", games = emptyList())
@DroidDeckPreviews @Composable private fun StoresPreview() = Launcher("stores")
@DroidDeckPreviews @Composable private fun DownloadsPreview() = Launcher("downloads")
@DroidDeckPreviews @Composable private fun SettingsPreview() = Launcher("setup")
@DroidDeckPreviews @Composable private fun RuntimeMissingPreview() = Launcher("setup", ready = false)

/** The in-game menu on one page, as a session shows it. */
@Composable
private fun SessionMenu(page: SessionDrawerPage, steam: Boolean = true, desktop: Boolean = false) = PreviewHost {
    SessionDrawer(true, page, controllerActive = true, onPageChange = {}, a = sampleDrawerActions(steam, desktop))
}

@DroidDeckPreviews @Composable private fun SessionMenuControllerPreview() = SessionMenu(SessionDrawerPage.CONTROLLER)
@DroidDeckPreviews @Composable private fun SessionMenuDisplayPreview() = SessionMenu(SessionDrawerPage.DISPLAY)
@DroidDeckPreviews @Composable private fun SessionMenuHudPreview() = SessionMenu(SessionDrawerPage.HUD)
@DroidDeckPreviews @Composable private fun SessionMenuSessionPreview() = SessionMenu(SessionDrawerPage.SESSION)
@DroidDeckPreviews @Composable private fun SessionMenuDesktopPreview() = SessionMenu(SessionDrawerPage.CONTROLLER, steam = false, desktop = true)

/** The in-game menu docked on the bottom screen, with the screen picker in its header. */
@Composable
private fun DockedMenu(page: SessionDrawerPage) = PreviewHost {
    SessionDrawer(true, page, controllerActive = false, onPageChange = {},
        a = sampleDrawerActions(secondScreen = SecondScreenMode.KEYBOARD_TRACKPAD), docked = true)
}

/** The bottom screen's bar on a panel: the panel's name is the screen picker. */
@Composable
private fun BottomPanelBar() = PreviewHost {
    val modes = secondScreenModes(false, deckPad = false, dashboard = true, desktop = false)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        BottomScreenBar(BottomBarModel("Keyboard + trackpad", {}, {}, {}, {},
            listOf(BarExtra("Keyboard", icon = R.drawable.ic_bottom_keyboard) {}),
            picker = ScreenPicker(modes, SecondScreenMode.KEYBOARD_TRACKPAD, {}, onMenu = {})))
    }
}

@DroidDeckPreviews @Composable private fun SessionMenuDockedPreview() = DockedMenu(SessionDrawerPage.CONTROLLER)
@DroidDeckPreviews @Composable private fun SessionMenuDockedDisplayPreview() = DockedMenu(SessionDrawerPage.DISPLAY)
@DroidDeckPreviews @Composable private fun BottomPanelBarPreview() = BottomPanelBar()

/** The dashboard's stats page with no game running: the game highlighted in Steam. */
@Composable
private fun BottomIdle() = PreviewHost {
    Column(Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
        IdleHomeCard(IdleGame(1145360, "Hades", null, 2843, System.currentTimeMillis() / 1000 - 2 * 86_400, highlighted = true)) {}
    }
}

@DroidDeckPreviews @Composable private fun BottomIdlePreview() = BottomIdle()

/** The Library and Stores tabs read store accounts, which load on a background thread. */
private val storesLoaded = { com.droiddeck.launcher.stores.StoresState.accountsLoaded }

/** Every preview above, for the render test. Add a case when adding a preview. */
internal val previewCases: List<PreviewCase> = listOf(
    PreviewCase("steam-tab") { Launcher("steam") },
    PreviewCase("library", storesLoaded) { Launcher("games") },
    PreviewCase("library-empty", storesLoaded) { Launcher("games", games = emptyList()) },
    PreviewCase("stores", storesLoaded) { Launcher("stores") },
    PreviewCase("downloads") { Launcher("downloads") },
    PreviewCase("settings") { Launcher("setup") },
    PreviewCase("runtime-missing") { Launcher("setup", ready = false) },
    PreviewCase("session-menu-controller") { SessionMenu(SessionDrawerPage.CONTROLLER) },
    PreviewCase("session-menu-display") { SessionMenu(SessionDrawerPage.DISPLAY) },
    PreviewCase("session-menu-hud") { SessionMenu(SessionDrawerPage.HUD) },
    PreviewCase("session-menu-session") { SessionMenu(SessionDrawerPage.SESSION) },
    PreviewCase("session-menu-desktop") { SessionMenu(SessionDrawerPage.CONTROLLER, steam = false, desktop = true) },
    PreviewCase("session-menu-docked") { DockedMenu(SessionDrawerPage.CONTROLLER) },
    PreviewCase("session-menu-docked-display") { DockedMenu(SessionDrawerPage.DISPLAY) },
    PreviewCase("bottom-panel-bar") { BottomPanelBar() },
    PreviewCase("bottom-idle-home") { BottomIdle() },
)
