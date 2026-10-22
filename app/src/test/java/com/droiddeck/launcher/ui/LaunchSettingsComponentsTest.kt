package com.droiddeck.launcher.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LaunchSettingsComponentsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var openedGame: Library.SteamGame? = null
    private var defaultOpens = 0

    @Test fun gameComponentsCardPassesItsGameInsteadOfOpeningDefault() {
        val game = Library.SteamGame(42, "Game A", null, "internal")
        show(game)
        clickComponents()
        assertEquals(game, openedGame)
        assertEquals(0, defaultOpens)
    }

    @Test fun addedGameComponentsCardPreservesItsUnsignedProfileKey() {
        val game = Library.SteamGame(-42, "Added game", null, Library.ADDED)
        show(game)
        clickComponents()
        assertEquals(game.profileKey, openedGame?.profileKey)
        assertEquals("4294967254", openedGame?.profileKey)
        assertEquals(0, defaultOpens)
    }

    @Test fun generalComponentsCardStillOpensDefault() {
        show(null)
        clickComponents()
        assertNull(openedGame)
        assertEquals(1, defaultOpens)
    }

    private fun clickComponents() {
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.setup_card_components)).performClick()
        compose.waitForIdle()
    }

    private fun show(game: Library.SteamGame?) {
        compose.setContent {
            DroidDeckTheme {
                LaunchSettings(
                    FrontEndState(
                        installed = null, ready = false, available = null, busy = false, stage = "", percent = -1,
                        desktopInstalled = false, offlineAccount = null, offline = false, frameGenLabel = "",
                        romsDir = null, logsEnabled = false, steamGames = listOfNotNull(game),
                        emulators = emptyList(), running = null,
                    ),
                    FrontEndActions(
                        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
                        onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                        onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                        onImportLossless = {}, onProtons = {}, onComponents = { defaultOpens++ },
                        onGameComponents = { openedGame = it }, onPerformance = {},
                        onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
                        onSetPhantomProcessLimit = { _, _ -> },
                    ),
                    rememberMenuHost(),
                    game,
                )
            }
        }
    }
}
