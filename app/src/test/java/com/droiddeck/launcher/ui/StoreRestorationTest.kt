package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreSizes
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.xbox.XboxBackend
import com.droiddeck.launcher.stores.xbox.XboxContent
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class StoreRestorationTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val product = CatalogItem(Store.XBOX, "9NBLGGH4R315", "Test game", null, owned = true, sizeBytes = 10_485_760L, extra = mapOf("cloud" to "1"))
    private val cloud = Library.SteamGame(-20, product.title, null, Library.XBOX_CLOUD, source = "xbox", storeId = product.id)

    @After fun clear() {
        StoresState.publishDownloads(emptyList())
        StoresState.unfinished = emptyList()
        StoreSizes.onDisk.clear()
        XboxContent.dismiss()
    }

    @Test fun manageContentReportsItsResultWithoutComposingTheStoresPage() {
        val base = InstalledStoreGame(StoreGameSidecar(Store.XBOX, product.id, product.title, "game.exe"), File(ctx.cacheDir, "missing-xbox-manifest"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme { FrontEndScreen(state(), actions(), page = { Text("Library game page") }) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { XboxContent.manage(ctx, base) }
        compose.waitUntil(5_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            XboxContent.plan != null
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(ctx.getString(R.string.stores_xbox_content_title)).assertExists()
        compose.onNodeWithText("Missing or ambiguous MicrosoftGame.config.").assertExists()
        compose.onNodeWithText(ctx.getString(android.R.string.ok)).performClick()
        compose.runOnIdle { assertNull(XboxContent.plan) }
    }

    @Test fun packageChoicesRenderOutsideTheStoresPage() {
        val choices = (1..24).map { JSONObject().put("title", "Package $it").put("kind", "Game") }
        val pending = XboxBackend.PackageChoice(product, ctx.cacheDir, choices)
        compose.setContent { DroidDeckTheme { XboxStoreDialogs(content = null, pending = pending) } }
        compose.onNodeWithText(ctx.getString(R.string.stores_xbox_choose_content)).assertExists()
        choices.forEach { compose.onNodeWithText(ctx.getString(R.string.stores_xbox_choice, it.getString("title"), "Game")).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(ctx.getString(android.R.string.cancel)).assertExists()
    }

    @Test fun everyContentOfferCanBeReachedInALandscapeDialog() {
        val base = InstalledStoreGame(StoreGameSidecar(Store.XBOX, product.id, product.title, "game.exe"), ctx.cacheDir)
        val offers = (1..24).map { XboxContent.Offer(product.copy(id = "dlc-$it", title = "Content $it"), installed = false) }
        val plan = XboxContent.Plan(base, offers)
        compose.setContent { DroidDeckTheme { XboxStoreDialogs(content = plan, pending = null) } }
        offers.forEach { compose.onNodeWithText(ctx.getString(R.string.stores_xbox_content_install, it.item.title)).performScrollTo().assertIsDisplayed() }
    }

    @Test fun libraryCardsShowDownloadAndInstalledSizes() {
        val owned = LibraryEntry("owned", "xbox", product.title, null, product, false)
        val installed = LibraryEntry("installed", "xbox", product.title, null, product.copy(id = "installed"), true)
        StoreSizes.onDisk["xbox:installed"] = 20_971_520L
        compose.setContent { DroidDeckTheme { Column {
            LibraryMarker(owned, null)
            LibraryMarker(installed, null)
        } } }
        compose.onNodeWithText("10.0 MB").assertExists()
        compose.onNodeWithText("20.0 MB").assertExists()
    }

    @Test fun aSavedCloudGamesDownloadKeepsPauseResumeCancelAndFailureRecovery() {
        val entry = DownloadEntry(Store.XBOX, product.id, product.title, state = DownloadState.RUNNING)
        StoresState.publishDownloads(listOf(entry))
        compose.setContent { DroidDeckTheme { GameActions(Store.XBOX, product.id, cloud, product, null, state(), actions(), Modifier) } }
        compose.onNodeWithText(ctx.getString(R.string.stores_dl_pause)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.stores_dl_cancel)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.game_stream)).assertExists()
        compose.runOnIdle { StoresState.publishDownloads(listOf(entry.copy(state = DownloadState.PAUSED))) }
        compose.onNodeWithText(ctx.getString(R.string.stores_dl_resume)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.stores_dl_cancel)).assertExists()
        compose.runOnIdle { StoresState.publishDownloads(listOf(entry.copy(state = DownloadState.FAILED))) }
        compose.onNodeWithText(ctx.getString(R.string.stores_dl_resume)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.stores_dl_clear)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.game_stream)).assertExists()
    }

    @Test fun aConsoleOnlyCloudGameDoesNotOfferAPcInstall() {
        compose.setContent { DroidDeckTheme { GameActions(Store.XBOX, product.id, cloud, product.copy(sizeBytes = 0, extra = mapOf("pc" to "0", "cloud" to "1")), null, state(), actions(), Modifier) } }
        compose.onNodeWithText(ctx.getString(R.string.game_stream)).assertExists()
        compose.onNodeWithText(ctx.getString(R.string.stores_install)).assertDoesNotExist()
    }

    @Test fun anInterruptedCloudDownloadCanBeClearedAfterItsLicenseOrPcMetadataChanges() {
        StoresState.unfinished = listOf(StoreInstallRoot.Unfinished(Store.XBOX, product.id, File(ctx.cacheDir, "partial")))
        var item by mutableStateOf(product)
        compose.setContent { DroidDeckTheme { GameActions(Store.XBOX, product.id, cloud, item, null, state(), actions(), Modifier) } }
        compose.onNodeWithText(ctx.getString(R.string.stores_resume_install)).assertExists()
        for (changed in listOf(product.copy(owned = false), product.copy(extra = mapOf("pc" to "0", "cloud" to "1")))) {
            compose.runOnIdle { item = changed }
            compose.onNodeWithText(ctx.getString(R.string.stores_dl_clear)).assertExists()
            compose.onNodeWithText(ctx.getString(R.string.stores_resume_install)).assertDoesNotExist()
        }
    }

    private fun state() = FrontEndState(
        installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
        romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
        pageKey = "test-game", navigate = "games",
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
