package com.droiddeck.launcher.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationSelectionTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Before fun controlAnimationFrames() {
        compose.mainClock.autoAdvance = false
    }

    @Test fun tabStripAnnouncesSelectionAndUpdatesAfterClick() {
        val selected = mutableIntStateOf(0)
        compose.setContent { DroidDeckTheme { TabStrip(listOf("First", "Second"), selected.intValue, { selected.intValue = it }) } }
        verify("First", "Second")
    }

    @Test fun sheetChipsAnnounceSelectionAndUpdateAfterClick() {
        val selected = mutableIntStateOf(0)
        compose.setContent { DroidDeckTheme { SheetChips(listOf("First", "Second"), selected.intValue) { selected.intValue = it } } }
        verify("First", "Second")
    }

    @Test fun segmentedTabsHaveOneTabRoleAndAnnounceTheirGroup() {
        val selected = mutableIntStateOf(0)
        compose.setContent { DroidDeckTheme { SegmentedTabs(listOf(0 to "First", 1 to "Second"), selected.intValue) { selected.intValue = it } } }
        verify("First", "Second")
    }

    @Test fun sideRailExposesSelectionEvenWhenOnlyIconsAreVisible() {
        val selected = mutableStateOf("steam")
        Hops.railPreview = null
        compose.setContent {
            DroidDeckTheme {
                SideRail(FrontEndState(
                    installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                    desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
                    romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
                ), selected.value, { selected.value = it }, {}, actions, Modifier.size(300.dp, 600.dp))
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val steam = compose.onNodeWithTag("rail-steam")
        val games = compose.onNodeWithTag("rail-games")
        assertTab(steam, true)
        assertTab(games, false)
        games.performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTab(steam, false)
        assertTab(games, true)
        assertGroup()
    }

    private fun verify(first: String, second: String) {
        compose.mainClock.advanceTimeBy(1_000)
        val a = compose.onNodeWithText(first)
        val b = compose.onNodeWithText(second)
        assertTab(a, true)
        assertTab(b, false)
        b.performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertTab(a, false)
        assertTab(b, true)
        assertGroup()
    }

    private fun assertTab(node: SemanticsNodeInteraction, selected: Boolean) {
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertHasClickAction()
        if (selected) node.assertIsSelected() else node.assertIsNotSelected()
    }

    private fun assertGroup() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertCountEquals(1)
    }

    private val actions = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
        onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
        onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
        onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
        onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> },
    )
}
