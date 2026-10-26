package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class LibraryVisibilityTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private var generation by mutableIntStateOf(0)

    @Before fun prepare() {
        ctx.getSharedPreferences("session", 0).edit().clear().commit()
        LibraryView.source = LIBRARY_ALL
        LibraryView.query = ""
        LibraryView.focusFirst = false
        StoresState.library.clear()
        StoresState.accounts.clear()
        for (store in listOf(Store.GOG, Store.EPIC)) {
            StoreAccounts.write(ctx, store, JSONObject().put("access_token", "test-only").put("display_name", "Library test"))
            StoresState.accounts[store] = "Library test"
            StoresState.library[store] = listOf(CatalogItem(store, "owned", "Uninstalled ${store.shortLabel}", null, owned = true, sizeBytes = 1024))
        }
    }

    @After fun clear() {
        for (store in listOf(Store.GOG, Store.EPIC)) StoreAccounts.clear(ctx, store)
        StoresState.library.clear()
        StoresState.accounts.clear()
        ctx.getSharedPreferences("session", 0).edit().clear().commit()
        LibraryView.source = LIBRARY_ALL
        LibraryView.query = ""
        LibraryView.focusFirst = false
    }

    @Test fun uninstalledGamesAreHiddenUntilToggledAndTheChoiceSurvivesReopeningLibrary() {
        show()
        compose.onNodeWithText("Installed Steam game").assertExists()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        compose.onNodeWithTag("library-show-uninstalled").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        assertTrue(SessionPrefs.libraryShowUninstalled(ctx))
        compose.runOnIdle { generation++ }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        compose.onNodeWithTag("library-show-uninstalled").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
    }

    @Test fun aSearchWithOnlyUninstalledMatchesOffersToRevealThem() {
        LibraryView.query = "Uninstalled GOG"
        show()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        compose.onNodeWithTag("library-browse-owned").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        assertEquals("Uninstalled GOG", LibraryView.query)
    }

    @Test fun aHiddenStoreRemainsReachableInLibraryInItsConfiguredOrder() {
        SessionPrefs.setStoresTabOrder(ctx, listOf("epic", "gog"))
        SessionPrefs.setStoresTabHidden(ctx, setOf("gog"))
        show()
        val steam = compose.onNodeWithTag("library-source-steam").fetchSemanticsNode().boundsInRoot.left
        val epic = compose.onNodeWithTag("library-source-epic").fetchSemanticsNode().boundsInRoot.left
        val gog = compose.onNodeWithTag("library-source-gog").fetchSemanticsNode().boundsInRoot.left
        assertTrue(steam < epic && epic < gog)
        compose.onNodeWithTag("library-source-gog").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("library-browse-owned").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
    }

    @Test fun theStoresOwnedGamesLinkRevealsOwnedGamesDespiteTheSavedInstalledOnlyView() {
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
        LibraryView.showOwned(ctx, Store.GOG)
        show()
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        compose.onNodeWithText("Installed Steam game").assertDoesNotExist()
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { key(generation) {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost { LibraryPage(state(), actions(), "games", {}, Modifier.fillMaxSize()) }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun state() = FrontEndState(
        installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
        romsDir = null, logsEnabled = false,
        steamGames = listOf(Library.SteamGame(440, "Installed Steam game", null, "internal")),
        emulators = emptyList(), running = null,
    )

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
        onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
        onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
        onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
        onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> },
    )
}
