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
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    }

    @Test fun aFreshInstallShowsTheDefaultBarMoreAndHiddenTabs() {
        assertEquals(listOf("steam", "desktop", "games", "downloads", "setup", "updates", "stores", NavTabs.MORE), navigationKeys(state()))
        assertEquals(listOf("soundtracks", "components", "storage", "sync", "decky", "boot-movies"), NavTabs.more(state()))
        assertEquals(TabPlace.HIDDEN, NavTabs.place(state(), "android-apps"))
        assertEquals(TabPlace.HIDDEN, NavTabs.place(state(), "agent"))
        assertFalse(com.droiddeck.launcher.agent.AgentTab.shown)
        assertTrue(NavTabs.isDefault())
    }

    @Test fun showingMusicOnceKeepsItShownAfterReload() {
        place("soundtracks", TabPlace.BAR)
        NavTabs.load(ctx)
        assertEquals(listOf("steam", "desktop", "games", "downloads", "setup", "updates", "stores", "soundtracks", NavTabs.MORE), navigationKeys(state()))
    }

    @Test fun shelvedTabsLeaveTheBarAndAddMoreAtTheEnd() {
        place("desktop", TabPlace.MORE)
        val s = state()
        assertEquals(listOf("desktop", "soundtracks", "components", "storage", "sync", "decky", "boot-movies"), NavTabs.more(s))
        assertEquals(NavTabs.MORE, navigationKeys(s).last())
        assertFalse("desktop" in navigationKeys(s))
    }

    @Test fun appsAndTheDevHarnessCanBeShownWithoutHomeScreenOrDeveloperTools() {
        place("android-apps", TabPlace.MORE)
        assertTrue("android-apps" in NavTabs.more(state()))
        place("agent", TabPlace.BAR)
        assertTrue(com.droiddeck.launcher.agent.AgentTab.shown)
    }

    @Test fun resettingPutsBackTheDefaultLayout() {
        place("stores", TabPlace.MORE)
        place("updates", TabPlace.HIDDEN)
        moveTab("downloads", -1)
        assertFalse(NavTabs.isDefault())
        NavTabs.reset(ctx)
        NavTabs.load(ctx)
        assertTrue(NavTabs.isDefault())
        assertEquals(listOf("steam", "desktop", "games", "downloads", "setup", "updates", "stores", NavTabs.MORE), navigationKeys(state()))
    }

    @Test fun anOldLayoutKeepsWhatItsSwitchesShowed() {
        ctx.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("gameStoresEnabled", false).putBoolean("soundtracksEnabled", false).commit()
        NavTabs.load(ctx)
        val s = state()
        assertEquals(listOf("steam", "games", "desktop", "components", "setup", "updates", NavTabs.MORE), navigationKeys(s))
        assertEquals(listOf("storage", "sync", "decky", "boot-movies"), NavTabs.more(s))
        listOf("stores", "downloads", "soundtracks", "android-apps", "agent").forEach { assertEquals(it, TabPlace.HIDDEN, NavTabs.place(s, it)) }
        val prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("gameStoresEnabled"))
        assertFalse(prefs.contains("soundtracksEnabled"))
    }

    @Test fun hiddenTabsAreNowhereAndSetupCannotBeHidden() {
        place("updates", TabPlace.HIDDEN)
        place("setup", TabPlace.HIDDEN)
        val s = state()
        assertFalse("updates" in navigationKeys(s))
        assertFalse("updates" in NavTabs.more(s))
        assertTrue("setup" in navigationKeys(s))
    }

    @Test fun storesAndMusicHideLikeAnyOtherTab() {
        place("stores", TabPlace.HIDDEN)
        place("soundtracks", TabPlace.HIDDEN)
        assertEquals(TabPlace.HIDDEN, NavTabs.place(state(), "stores"))
        assertEquals(TabPlace.MORE, NavTabs.place(state(), "components"))
        assertEquals(TabPlace.BAR, NavTabs.place(state(), "downloads"))
        assertEquals(listOf("components", "storage", "sync", "decky", "boot-movies"), NavTabs.more(state()))
    }

    @Test fun movingSwapsWithTheNextVisibleTabAndPersists() {
        moveTab("games", -1)
        assertEquals(listOf("steam", "games", "desktop", "downloads"), navigationKeys(state()).take(4))
        NavTabs.load(ctx)
        assertEquals(listOf("steam", "games", "desktop", "downloads"), navigationKeys(state()).take(4))
    }

    @Test fun tabsMissingFromASavedOrderSlotInAfterTheirDefaultNeighbour() {
        assertEquals(listOf("updates", "stores", "soundtracks", "components", "storage", "sync", "decky", "boot-movies", "android-apps", "agent", "steam", "desktop", "games", "downloads", "setup"),
            NavTabs.arrange(listOf("updates", "steam")))
        assertEquals(listOf("games", "downloads", "steam", "setup", "stores", "soundtracks", "desktop", "components", "storage", "sync", "decky", "boot-movies", "android-apps", "agent", "updates"),
            NavTabs.arrange(listOf("games", "steam", "setup", "stores", "desktop", "components", "android-apps", "updates", "bogus")))
    }

    @Test fun storageAndSyncJoinMoreForUsersWithASavedLayout() {
        ctx.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .putString("navTabOrder", "steam,games,soundtracks,stores,desktop,agent,components,android-apps,setup,updates")
            .putString("navTabShelved", "soundtracks").commit()
        NavTabs.load(ctx)
        assertEquals(listOf("soundtracks", "storage", "sync", "decky", "boot-movies"), NavTabs.more(state()))
        place("sync", TabPlace.BAR)
        NavTabs.load(ctx)
        assertTrue("sync" in navigationKeys(state()))
    }

    @Test fun moreCarriesTheDownloadCountAndBadgesOfShelvedTabs() {
        place("downloads", TabPlace.MORE)
        place("setup", TabPlace.MORE)
        val s = state(downloads = 3, setupReady = false)
        assertEquals(3, railCount(s, NavTabs.MORE))
        assertTrue(railBadge(s, NavTabs.MORE))
        place("downloads", TabPlace.BAR)
        assertEquals(0, railCount(s, NavTabs.MORE))
        assertEquals(3, railCount(s, "downloads"))
        assertEquals(0, railCount(s, "stores"))
    }

    @Test fun sideRailShowsTheDownloadCountOnMoreWhenDownloadsIsShelved() {
        place("downloads", TabPlace.MORE)
        val s = state(downloads = 2)
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    Box(Modifier.height(752.dp)) { SideRail(s, "steam", {}, {}, actions(), Modifier) }
                }
            }
        }
        compose.onNodeWithTag("rail-downloads").assertDoesNotExist()
        compose.onNode(hasTestTag("rail-more")).assertIsDisplayed()
        compose.onNode(hasText("2") and hasParentTag("rail-more"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun morePageListsShelvedTabsWithTheirCount() {
        place("downloads", TabPlace.MORE)
        place("components", TabPlace.MORE)
        val s = state(downloads = 4)
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost { Box(Modifier.fillMaxSize()) { MorePage(s, actions(), {}, Modifier.fillMaxSize()) } }
                }
            }
        }
        compose.onNodeWithTag("more-downloads").assertIsDisplayed()
        compose.onNodeWithTag("more-components").assertIsDisplayed()
        compose.onNode(hasText("4") and hasParentTag("more-downloads"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun backFromAShelvedTabReturnsToMore() {
        place("desktop", TabPlace.MORE)
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
        place("steam", TabPlace.HIDDEN)
        repeat(NavTabs.ALL.size) { moveTab("components", -1) }
        assertEquals("components", navigationKeys(state()).first())
        compose.mainClock.autoAdvance = false
        compose.setContent { DroidDeckTheme { FrontEndScreen(state(), actions(), page = null) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNode(hasText(ctx.getString(com.droiddeck.launcher.R.string.content_page_gone))).assertDoesNotExist()
        compose.onNode(hasTestTag("rail-desktop") and androidx.compose.ui.test.isSelected()).assertExists()
    }

    @Test fun setupListMovesATabAcrossTheMoreLineWithTheControllerAndHidesWithItsSwitch() {
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    androidx.compose.foundation.layout.Column(Modifier.height(752.dp).verticalScroll(rememberScrollState())) {
                        TabsSettingsGroup(MenuHost(), state(), actions())
                    }
                }
            }
        }
        val last = navigationKeys(state()).last { it != NavTabs.MORE }
        compose.onNodeWithTag("tab-row-$last").requestFocus()
        compose.onNodeWithTag("tab-row-$last").performKeyPress(pad(android.view.KeyEvent.KEYCODE_BUTTON_Y))
        compose.onNodeWithTag("tab-row-$last").performKeyPress(pad(android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        compose.onNodeWithTag("tab-row-$last").performKeyPress(pad(android.view.KeyEvent.KEYCODE_BUTTON_Y))
        compose.runOnIdle { assertEquals(last, NavTabs.more(state()).first()) }
        compose.onNodeWithTag("tab-shown-desktop").performClick()
        compose.runOnIdle { assertFalse("desktop" in navigationKeys(state())) }
        compose.onNodeWithTag("tab-shown-setup").performClick()
        compose.runOnIdle { assertTrue("setup" in navigationKeys(state())) }
    }

    private fun pad(code: Int) = androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))

    private fun place(key: String, where: TabPlace, s: FrontEndState = state()) {
        NavTabs.setHidden(ctx, key, where == TabPlace.HIDDEN)
        if (where == TabPlace.HIDDEN) return
        val keys = NavTabs.listed(s)
        val more = keys.filter { if (it == key) where == TabPlace.MORE else it in NavTabs.shelved }
        NavTabs.rearrange(ctx, s, keys - more.toSet(), more)
    }

    private fun moveTab(key: String, step: Int, s: FrontEndState = state()) {
        val keys = NavTabs.listed(s)
        val rows = moveTabRow(tabRows(keys.filter { it !in NavTabs.shelved }, keys.filter { it in NavTabs.shelved }), key, step) ?: return
        val split = rows.indexOf(rows.first { it !in keys })
        NavTabs.rearrange(ctx, s, rows.subList(0, split), rows.subList(split + 1, rows.size))
    }

    private fun hasParentTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))

    private fun state(downloads: Int = 0, setupReady: Boolean = true) = FrontEndState(
        installed = "r9", ready = setupReady, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
        steamGames = emptyList(), emulators = emptyList(), running = null,
        storeDownloadsActive = downloads,
    )

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {}, onRom = {}, onResume = {},
        onSteamSettings = {}, onDesktopSettings = {}, onInstallPackage = {}, onRemovePackage = {},
        onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
        onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        
        onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {},
    )
}
