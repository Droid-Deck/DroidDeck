package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h900dp-mdpi")
class PortraitLayoutTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Before fun freezeAnimations() {
        compose.mainClock.autoAdvance = false
        Hops.railPreview = null
    }

    @Test fun navigationSitsAtBottomAndRotatingPreservesContentState() {
        val portrait = mutableStateOf(true)
        compose.setContent {
            DroidDeckTheme {
                LauncherLayout(portrait.value, Color.Gray,
                    Modifier.size(if (portrait.value) 480.dp else 800.dp, if (portrait.value) 800.dp else 480.dp).testTag("launcher"),
                    navigation = {
                        if (portrait.value) BottomBar(state, "steam", {}, {}, actions, Modifier)
                        else SideRail(state, "steam", {}, {}, actions, Modifier.fillMaxHeight())
                    },
                ) {
                    var count by remember { mutableIntStateOf(0) }
                    Box(Modifier.fillMaxSize().testTag("pane")) {
                        TextButton(onClick = { count++ }) { Text("Count $count") }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val root = compose.onNodeWithTag("launcher").getUnclippedBoundsInRoot()
        val pane = compose.onNodeWithTag("pane").getUnclippedBoundsInRoot()
        val steam = compose.onNodeWithTag("rail-steam").getUnclippedBoundsInRoot()
        assertEquals(root.left, pane.left)
        assertEquals(root.right, pane.right)
        assertTrue(steam.top >= pane.bottom)
        assertEquals(root.bottom - 4.dp, steam.bottom)
        compose.onNodeWithText("Count 0").performClick()
        compose.runOnIdle { portrait.value = false }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Count 1").assertExists()
        val landscapePane = compose.onNodeWithTag("pane").getUnclippedBoundsInRoot()
        val rail = compose.onNodeWithTag("rail-steam").getUnclippedBoundsInRoot()
        assertTrue(landscapePane.left > rail.right)
        assertEquals(0.dp, landscapePane.top)
        compose.runOnIdle { portrait.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Count 1").assertExists()
    }

    @Test fun narrowBottomBarScrollsToEveryDestinationWithAppsAndResumeVisible() {
        val selected = mutableStateOf("steam")
        compose.setContent {
            DroidDeckTheme {
                BottomBar(stateWithAppsAndSession, selected.value, { selected.value = it }, {}, actions, Modifier.size(320.dp, 64.dp))
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        for (key in listOf("games", "stores", "desktop", "components", "android-apps", "setup", "updates", "steam")) {
            compose.onNodeWithTag("rail-$key").performScrollTo()
            compose.mainClock.advanceTimeBy(1_000)
            compose.onNodeWithTag("rail-$key").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnIdle { assertEquals(key, selected.value); assertNull(Hops.railPreview) }
            compose.onNodeWithTag("rail-$key").assertIsSelected().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithContentDescription("Resume Steam").assertIsDisplayed()
    }

    @Test fun compactHeaderKeepsAllActionsReachable() {
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalNarrowPane provides true) {
                    Box(Modifier.size(320.dp, 240.dp)) {
                        ModeHeader("Library", "", {}, "Search library") {
                            for (label in listOf("Filter", "Sort", "View", "Frontend files", "Add game")) {
                                SecondaryButton(label) {}
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Add game").performScrollTo()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Add game").assertIsDisplayed()
        compose.onNodeWithText("Filter").performScrollTo()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Filter").assertIsDisplayed()
    }

    private fun state(home: Boolean = false, running: String? = null) = FrontEndState(
        installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
        romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = running,
        isHomeApp = home,
    )
    private val state get() = state()
    private val stateWithAppsAndSession get() = state(home = true, running = "Steam")

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
