package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

/** The Stardew Valley companion page, fed with the mod's messages. */
@Composable
private fun BottomStardew() = PreviewHost {
    val S = com.droiddeck.launcher.companion.StardewCompanion
    val state = listOf(
        """{"type":"game_info","saveName":"Sunny Farm"}""",
        """{"type":"sdv_day","season":"spring","day":5,"year":1,"weekday":"Fri","timeOfDay":1330,"weatherToday":"Sunny","gold":12500,"energy":200,"maxEnergy":270,"health":90,"maxHealth":100,"location":"Farm"}""",
        """{"type":"sdv_inventory","selected":2,"items":[{"slot":0,"id":"(T)Axe","name":"Axe"},{"slot":1,"id":"(T)Hoe","name":"Hoe"},{"slot":2,"id":"(O)24","name":"Parsnip","count":15},{"slot":4,"id":"(O)388","name":"Wood","count":99}]}""",
        """{"type":"sdv_villagers","villagers":[{"name":"Haley","birthdayIn":0},{"name":"Abigail","birthdayIn":3}]}""",
        """{"type":"sdv_quests","quests":[{"title":"Introductions","objective":"Meet 28 people (12 of 28)"},{"title":"How To Win Friends","objective":"Give a gift to someone"}]}""",
    ).fold(com.droiddeck.launcher.companion.StardewCompanion.State()) { st, m -> S.reduce(st, m) }
    androidx.compose.foundation.layout.Box(Modifier.size(640.dp, 420.dp).background(androidx.compose.material3.MaterialTheme.colorScheme.background)) { StardewPanel(state) {} }
}

@DroidDeckPreviews @Composable private fun BottomStardewPreview() = BottomStardew()

/** The Fallout 4 companion page, fed with a Thor Pip-Boy data message. */
@Composable
private fun BottomPipBoy() = PreviewHost {
    val rpg = com.droiddeck.launcher.companion.RpgCompanion
    val state = rpg.reduce(rpg.FALLOUT_4, com.droiddeck.launcher.companion.RpgCompanion.State(), """{"type":"data","id":"droiddeck","data":{
        "player.stats":{"level":23,"hp":245,"hpMax":310,"ap":88,"apMax":105,"rads":65,"caps":1834,"weight":187.4,"weightMax":260},
        "player.inventory":[{"id":"0x102","name":"Service Pistol","count":1,"equipped":true,"fav":1},{"id":"0x236","name":"Med Kit","count":7,"fav":0},
            {"id":"0x303","name":"Clean Water","count":4,"fav":2}],"radio":{"enabled":true}}}""")
    androidx.compose.foundation.layout.Box(Modifier.size(640.dp, 420.dp).background(androidx.compose.material3.MaterialTheme.colorScheme.background)) { RpgPanel(state, {}, {}) }
}

@DroidDeckPreviews @Composable private fun BottomPipBoyPreview() = BottomPipBoy()

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
    PreviewCase("bottom-companion-stardew") { BottomStardew() },
    PreviewCase("bottom-companion-pipboy") { BottomPipBoy() },
)
