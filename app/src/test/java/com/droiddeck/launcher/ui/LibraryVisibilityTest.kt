package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.CredentialCipher
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.xbox.XboxLibraryFilter
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
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class LibraryVisibilityTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private var generation by mutableIntStateOf(0)

    @Before fun prepare() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        StoreAccounts.keystoreFailed = false
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
        Steps.drop()
        XboxLibraryFilter.set(ctx, XboxLibraryFilter.ALL)
        for (store in listOf(Store.GOG, Store.EPIC)) StoreAccounts.clear(ctx, store)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
        StoresState.library.clear()
        StoresState.accounts.clear()
        ctx.getSharedPreferences("session", 0).edit().clear().commit()
        LibraryView.source = LIBRARY_ALL
        LibraryView.query = ""
        LibraryView.focusFirst = false
    }

    @Test fun uninstalledGamesStayFoldedUnderTheirRowAndTheChoiceSurvivesReopeningLibrary() {
        show()
        compose.onNodeWithText("Installed Steam game").assertExists()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        compose.onNodeWithText("NOT INSTALLED  ·  2").assertExists()
        toggleNotInstalled(true)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        compose.runOnIdle { generation++; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        toggleNotInstalled(false)
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
    }

    @Test fun theNotInstalledRowReplacesTheFilterButtonAndSortStillOpens() {
        show()
        compose.onNodeWithContentDescription("Filter · Installed only").assertDoesNotExist()
        compose.onNodeWithTag("library-not-installed").assertExists()
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodesWithText("Sort").assertCountEquals(1)
        compose.onNodeWithText("Recently played").assertIsDisplayed()
    }

    @Test fun theXboxFilterOnlyOffersTheXboxAccessChoices() {
        StoreAccounts.write(ctx, Store.XBOX, JSONObject().put("username", "Library test"))
        StoresState.accounts[Store.XBOX] = "Library test"
        StoresState.library[Store.XBOX] = listOf(CatalogItem(Store.XBOX, "owned", "Uninstalled Xbox", null, owned = true))
        LibraryView.source = Store.XBOX.id
        try {
            show()
            compose.onNodeWithContentDescription("Filter · All").performClick()
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithText("Installed only").assertDoesNotExist()
            compose.onNodeWithText("Game Pass").assertIsDisplayed().performClick()
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals(XboxLibraryFilter.GAME_PASS, XboxLibraryFilter.current)
            assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
        } finally {
            StoreAccounts.clear(ctx, Store.XBOX)
        }
    }

    @Test fun aSearchWithOnlyUninstalledMatchesShowsTheFoldedRowToRevealThem() {
        LibraryView.query = "Uninstalled GOG"
        show()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        compose.onNodeWithText("No installed games match these filters", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("library-not-installed").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        assertEquals("Uninstalled GOG", LibraryView.query)
        compose.onNodeWithTag("library-not-installed").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        compose.onNodeWithTag("library-not-installed").assertExists()
    }

    @Test fun aSwitchedOffStoreLeavesTheChipsButItsGamesStayUnderAll() {
        SessionPrefs.setStoresTabOrder(ctx, listOf("epic", "gog"))
        SessionPrefs.setStoresTabHidden(ctx, setOf("gog"))
        show()
        val steam = compose.onNodeWithTag("library-source-steam").fetchSemanticsNode().boundsInRoot.left
        val epic = compose.onNodeWithTag("library-source-epic").fetchSemanticsNode().boundsInRoot.left
        assertTrue(steam < epic)
        compose.onNodeWithTag("library-source-gog").assertDoesNotExist()
        compose.onNodeWithTag("library-not-installed").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
    }

    @Test fun theCustomGamesTabIsAlwaysThereAndOffersToAddWhenEmpty() {
        show()
        compose.onNodeWithTag("library-source-added").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("library-custom-empty").assertExists()
        compose.onNodeWithTag("library-custom-add").performClick()
        assertTrue(GamesDialogState.adding)
        GamesDialogState.adding = false
    }

    @Test fun theStoresOwnedGamesLinkRevealsOwnedGamesDespiteTheSavedInstalledOnlyView() {
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
        LibraryView.showOwned(ctx, Store.GOG)
        show()
        compose.onNodeWithText("Uninstalled GOG").assertExists()
        compose.onNodeWithText("Installed Steam game").assertDoesNotExist()
    }

    @Test fun theStoresOwnedGamesLinkUpdatesALibraryPageThatIsStillComposed() {
        LibraryView.source = Store.GOG.id
        show()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        compose.runOnIdle { LibraryView.showOwned(ctx, Store.GOG); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Uninstalled GOG").assertExists()
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { key(generation) {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost { Box(Modifier.fillMaxSize()) {
                        LibraryPage(state(), actions(), "games", {}, Modifier.fillMaxSize())
                        StepsLayer(Offset.Zero)
                    } }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun toggleNotInstalled(show: Boolean) {
        compose.onNodeWithTag("library-not-installed").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(show, SessionPrefs.libraryShowUninstalled(ctx))
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
