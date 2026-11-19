package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable

/** The launcher as a whole: rail and page, opened on each tab through FrontEndState.navigate. */
@Composable
private fun Launcher(tab: String, games: List<com.droiddeck.launcher.frontend.Library.SteamGame> = sampleGames, ready: Boolean = true) =
    PreviewHost { FrontEndScreen(sampleState(games = games, navigate = tab, ready = ready), sampleActions) }

@DroidDeckPreviews @Composable private fun SteamTabPreview() = Launcher("steam")
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

/** Every preview above, for the render test. Add a case when adding a preview. */
internal val previewCases: List<PreviewCase> = listOf(
    PreviewCase("steam-tab") { Launcher("steam") },
    PreviewCase("library") { Launcher("games") },
    PreviewCase("library-empty") { Launcher("games", games = emptyList()) },
    PreviewCase("stores") { Launcher("stores") },
    PreviewCase("downloads") { Launcher("downloads") },
    PreviewCase("settings") { Launcher("setup") },
    PreviewCase("runtime-missing") { Launcher("setup", ready = false) },
    PreviewCase("session-menu-controller") { SessionMenu(SessionDrawerPage.CONTROLLER) },
    PreviewCase("session-menu-display") { SessionMenu(SessionDrawerPage.DISPLAY) },
    PreviewCase("session-menu-hud") { SessionMenu(SessionDrawerPage.HUD) },
    PreviewCase("session-menu-session") { SessionMenu(SessionDrawerPage.SESSION) },
    PreviewCase("session-menu-desktop") { SessionMenu(SessionDrawerPage.CONTROLLER, steam = false, desktop = true) },
)
