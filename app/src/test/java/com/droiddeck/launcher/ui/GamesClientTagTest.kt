package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.droiddeck.launcher.frontend.Library
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class GamesClientTagTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private val two = listOf(
        Library.SteamGame(440, "Team Fortress 2", null, "Steam library"),
        Library.SteamGame(70, "Half-Life", null, "Steam library"),
    )

    @Test fun theArm64ClientsListHasNoTag() {
        show(two, x64 = false)
        compose.onAllNodesWithTag("games-client-x64").assertCountEquals(0)
    }

    @Test fun theX8664ClientsListIsTagged() {
        show(two, x64 = true)
        compose.onAllNodesWithTag("games-client-x64").assertCountEquals(1)
    }

    @Test fun anEmptyX8664LibraryIsTaggedToo() {
        show(emptyList(), x64 = true)
        compose.onAllNodesWithTag("games-client-x64").assertCountEquals(1)
    }

    @Test fun aNarrowPaneStillShowsTheTag() {
        show(two, x64 = true, narrow = true)
        compose.onAllNodesWithTag("games-client-x64").assertCountEquals(1)
    }

    private fun show(games: List<Library.SteamGame>, x64: Boolean, narrow: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus(), LocalNarrowPane provides narrow) {
                    FocusGlideHost {
                        LibraryPage(FrontEndState(
                            installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                            desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
                            romsDir = null, logsEnabled = false, steamGames = games, steamGamesX64 = x64, emulators = emptyList(), running = null,
                        ), FrontEndActions(
                            onPlay = {}, onPlaySteamVr = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
                            onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                            onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                            onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
                            onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                            onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
                            onSetPhantomProcessLimit = { _, _ -> },
                        ), selected = "games", onSelect = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }
}
