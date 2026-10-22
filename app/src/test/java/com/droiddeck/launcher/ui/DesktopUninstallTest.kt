package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class DesktopUninstallTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var removals = 0
    private var runtimeBlocked by mutableStateOf(false)

    @After fun closeConfirmation() { Steps.drop() }

    @Test fun uninstallIsAvailableOnTheDesktopPageAndRequiresConfirmation() {
        show()
        compose.onNodeWithText("Open desktop").assertIsDisplayed()
        compose.onNodeWithText("Uninstall").assertIsDisplayed().performClick()
        settle()
        assertEquals(0, removals)
        compose.onNodeWithText("Uninstall the desktop?").assertIsDisplayed()
        compose.onAllNodesWithText("Uninstall").onLast().performClick()
        assertEquals(1, removals)
    }

    @Test fun uninstallIsDisabledWhileRuntimeChangesAreBlocked() {
        show(blocked = true)
        compose.onNodeWithText("Uninstall").assertIsNotEnabled()
    }

    @Test fun aPartialDesktopStillOffersUninstall() {
        show(installed = false)
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
    }

    @Test fun anUninstalledDesktopNoLongerOffersUninstall() {
        show(installed = false, present = false)
        compose.onNodeWithText("Uninstall").assertDoesNotExist()
    }

    @Test fun startingRuntimeWorkWhileConfirmingDisablesUninstall() {
        show()
        compose.onNodeWithText("Uninstall").performClick()
        settle()
        compose.runOnIdle { runtimeBlocked = true }
        settle()
        compose.onAllNodesWithText("Uninstall").onLast().assertIsNotEnabled()
        assertEquals(0, removals)
    }

    private fun settle() = compose.mainClock.advanceTimeBy(2_000)

    private fun show(installed: Boolean = true, blocked: Boolean = false, present: Boolean = true) {
        runtimeBlocked = blocked
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost {
                        Box(Modifier.fillMaxSize()) {
                            DesktopPage(FrontEndState(
                                installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                                desktopInstalled = installed, desktopPresent = present, runtimeActionsBlocked = runtimeBlocked,
                                offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
                                steamGames = emptyList(), emulators = emptyList(), running = null,
                            ), FrontEndActions(
                                onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {}, onRom = {}, onResume = {},
                                onSteamSettings = {}, onDesktopSettings = {}, onInstallPackage = {}, onRemovePackage = {},
                                onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
                                onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                                onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
                                onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = { removals++ },
                            ), Modifier.fillMaxSize(), onSelect = {})
                            StepsLayer(Offset.Zero)
                        }
                    }
                }
            }
        }
        settle()
    }
}
