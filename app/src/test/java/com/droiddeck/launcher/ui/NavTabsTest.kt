package com.droiddeck.launcher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class NavTabsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun reset() {
        ctx.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        NavTabs.load(ctx)
        com.droiddeck.launcher.agent.AgentTab.load(ctx)
    }

    @Test fun musicStartsInMore() {
        assertEquals(listOf("steam", "games", "stores", "desktop", "components", "setup", "updates", NavTabs.MORE), navigationKeys(state()))
        assertEquals(listOf("soundtracks"), NavTabs.more(state()))
        assertEquals(setOf("soundtracks", "agent"), NavTabs.shelved)
        assertTrue(com.droiddeck.launcher.agent.AgentTab.shown)
    }

    @Test fun showingMusicOnceKeepsItShownAfterReload() {
        NavTabs.setPlace(ctx, "soundtracks", TabPlace.BAR)
        NavTabs.load(ctx)
        assertEquals(listOf("steam", "games", "soundtracks", "stores", "desktop", "components", "setup", "updates"), navigationKeys(state()))
    }

    @Test fun shelvedTabsLeaveTheBarAndAddMoreAtTheEnd() {
        NavTabs.setPlace(ctx, "stores", TabPlace.MORE)
        NavTabs.setPlace(ctx, "components", TabPlace.MORE)
        val s = state()
        assertEquals(listOf("soundtracks", "stores", "components"), NavTabs.more(s))
        assertEquals(NavTabs.MORE, navigationKeys(s).last())
        assertFalse("stores" in navigationKeys(s))
    }

    @Test fun hiddenTabsAreNowhereAndSetupCannotBeHidden() {
        NavTabs.setPlace(ctx, "updates", TabPlace.HIDDEN)
        NavTabs.setPlace(ctx, "setup", TabPlace.HIDDEN)
        val s = state()
        assertFalse("updates" in navigationKeys(s))
        assertFalse("updates" in NavTabs.more(s))
        assertTrue("setup" in navigationKeys(s))
    }

    @Test fun disabledStoresFeatureReadsAsHidden() {
        assertEquals(TabPlace.HIDDEN, NavTabs.place(state(storesOn = false), "stores"))
    }

    @Test fun movingSwapsWithTheNextVisibleTabAndPersists() {
        NavTabs.setPlace(ctx, "soundtracks", TabPlace.BAR)
        NavTabs.move(ctx, state(), "stores", -1)
        assertEquals(listOf("steam", "games", "stores", "soundtracks"), navigationKeys(state()).take(4))
        NavTabs.load(ctx)
        assertEquals(listOf("steam", "games", "stores", "soundtracks"), navigationKeys(state()).take(4))
    }

    @Test fun tabsMissingFromASavedOrderSlotInAfterTheirDefaultNeighbour() {
        assertEquals(listOf("updates", "steam", "games", "soundtracks", "stores", "desktop", "agent", "components", "android-apps", "setup"),
            NavTabs.arrange(listOf("updates", "steam")))
        assertEquals(listOf("games", "soundtracks", "steam", "setup", "stores", "desktop", "agent", "components", "android-apps", "updates"),
            NavTabs.arrange(listOf("games", "steam", "setup", "stores", "desktop", "components", "android-apps", "updates", "bogus")))
    }

    @Test fun moreCarriesTheDownloadCountAndBadgesOfShelvedTabs() {
        NavTabs.setPlace(ctx, "stores", TabPlace.MORE)
        NavTabs.setPlace(ctx, "setup", TabPlace.MORE)
        val s = state(downloads = 3, setupReady = false)
        assertEquals(3, railCount(s, NavTabs.MORE))
        assertTrue(railBadge(s, NavTabs.MORE))
        NavTabs.setPlace(ctx, "stores", TabPlace.BAR)
        assertEquals(0, railCount(s, NavTabs.MORE))
        assertEquals(3, railCount(s, "stores"))
    }

    @Test fun sideRailShowsTheDownloadCountOnMoreWhenStoresIsShelved() {
        NavTabs.setPlace(ctx, "stores", TabPlace.MORE)
        val s = state(downloads = 2)
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    Box(Modifier.height(752.dp)) { SideRail(s, "steam", {}, {}, actions(), Modifier) }
                }
            }
        }
        compose.onNodeWithTag("rail-stores").assertDoesNotExist()
        compose.onNode(hasTestTag("rail-more")).assertIsDisplayed()
        compose.onNode(hasText("2") and hasParentTag("rail-more"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun morePageListsShelvedTabsWithTheirCount() {
        NavTabs.setPlace(ctx, "stores", TabPlace.MORE)
        NavTabs.setPlace(ctx, "components", TabPlace.MORE)
        val s = state(downloads = 4)
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost { Box(Modifier.fillMaxSize()) { MorePage(s, actions(), {}, Modifier.fillMaxSize()) } }
                }
            }
        }
        compose.onNodeWithTag("more-stores").assertIsDisplayed()
        compose.onNodeWithTag("more-components").assertIsDisplayed()
        compose.onNode(hasText("4") and hasParentTag("more-stores"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun backFromAShelvedTabReturnsToMore() {
        NavTabs.setPlace(ctx, "desktop", TabPlace.MORE)
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            DroidDeckTheme { FrontEndScreen(state(), actions(), page = null) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("rail-more").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").assertDoesNotExist()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").assertIsDisplayed()
    }

    @Test fun startupWithSteamHiddenNeverLandsOnABareComponentsSelection() {
        NavTabs.setPlace(ctx, "steam", TabPlace.HIDDEN)
        repeat(NavTabs.ALL.size) { NavTabs.move(ctx, state(), "components", -1) }
        assertEquals("components", navigationKeys(state()).first())
        compose.mainClock.autoAdvance = false
        compose.setContent { DroidDeckTheme { FrontEndScreen(state(), actions(), page = null) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNode(hasText(ctx.getString(com.droiddeck.launcher.R.string.content_page_gone))).assertDoesNotExist()
        compose.onNode(hasTestTag("rail-games") and androidx.compose.ui.test.isSelected()).assertExists()
    }

    private fun hasParentTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))

    private fun state(storesOn: Boolean = true, downloads: Int = 0, setupReady: Boolean = true) = FrontEndState(
        installed = "r9", ready = setupReady, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
        steamGames = emptyList(), emulators = emptyList(), running = null,
        gameStoresEnabled = storesOn, storeDownloadsActive = downloads,
    )

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {}, onRom = {}, onResume = {},
        onSteamSettings = {}, onDesktopSettings = {}, onInstallPackage = {}, onRemovePackage = {},
        onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
        onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {},
    )
}
