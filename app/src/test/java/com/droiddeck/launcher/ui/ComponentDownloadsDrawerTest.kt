package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.ComponentsManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComponentDownloadsDrawerTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private lateinit var inputMode: InputModeManager

    private val dxvk = item("dxvk-2.4.1-linux.wcp", "dxvk")
    private val stored = item("dxvk-2.3-linux.wcp", "dxvk")
    private val inFlight = item("dxvk-2.7.1-linux.wcp", "dxvk")
    private val fex = item("fex-2609-linux.wcp", "fex")

    @Test fun choosingDxvkDownloadsTheCatalogItemAndPreventsStoredOrInFlightDownloads() {
        val requested = mutableListOf<ComponentsManager.CatalogItem>()
        show(downloads = mapOf(inFlight.file to 45), onDownload = requested::add)
        compose.onNodeWithTag("cmp-download-dxvk").performClick()
        compose.onNodeWithText(stored.file.removeSuffix(".wcp")).assertIsNotEnabled()
        compose.onNodeWithText(inFlight.file.removeSuffix(".wcp")).assertIsNotEnabled()
        compose.onNodeWithText(fex.file.removeSuffix(".wcp")).assertDoesNotExist()
        compose.onNodeWithText(dxvk.file.removeSuffix(".wcp")).performClick()
        assertEquals(listOf(dxvk), requested)
        compose.onNodeWithText(dxvk.file.removeSuffix(".wcp")).assertDoesNotExist()
        compose.onNodeWithText("45%").assertExists()
    }

    @Test fun pairedNightlyRidesOnTheStableRowBehindTheSwitch() {
        val stable = ComponentsManager.CatalogItem("dxvk-3.1.1-linux.wcp", "dxvk", "Dxvk-Linux", "https://github.com/example/s", 100, "sha256:test")
        val nightly = ComponentsManager.CatalogItem("DD-dxvk-3.1.1+153-nightly.wcp", "dxvk", ComponentsManager.DD_NIGHTLY, "https://github.com/example/n", 100, "sha256:test")
        val requested = mutableListOf<ComponentsManager.CatalogItem>()
        show(catalog = listOf(stable, nightly), onDownload = requested::add)
        compose.onNodeWithTag("cmp-download-dxvk").performClick()
        compose.onNodeWithText("dxvk-3.1.1-linux").assertExists()
        compose.onNodeWithText("DD-dxvk-3.1.1+153-nightly").assertDoesNotExist()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.comp_channel_nightly)).performClick()
        compose.onNodeWithText("dxvk-3.1.1-linux").assertDoesNotExist()
        compose.onNodeWithText("DD-dxvk-3.1.1+153-nightly").performClick()
        assertEquals(listOf(nightly), requested)
    }

    @Test fun controllerPicksNightlyOnItsTabThenDownloadsItFromTheRow() {
        val stable = ComponentsManager.CatalogItem("dxvk-3.1.1-linux.wcp", "dxvk", "Dxvk-Linux", "https://github.com/example/s", 100, "sha256:test")
        val nightly = ComponentsManager.CatalogItem("DD-dxvk-3.1.1+153-nightly.wcp", "dxvk", ComponentsManager.DD_NIGHTLY, "https://github.com/example/n", 100, "sha256:test")
        val requested = mutableListOf<ComponentsManager.CatalogItem>()
        show(catalog = listOf(stable, nightly), onDownload = requested::add)
        compose.onNodeWithTag("cmp-download-dxvk").performClick()
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithText("dxvk-3.1.1-linux").requestFocus().assertIsFocused()
        compose.onNodeWithText("dxvk-3.1.1-linux").performKeyInput { pressKey(Key.DirectionRight) }
        val nightlyTab = RuntimeEnvironment.getApplication().getString(R.string.comp_channel_nightly)
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.comp_channel_stable)).assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText(nightlyTab).assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }
        compose.onNodeWithText("DD-dxvk-3.1.1+153-nightly").assertExists()
        assertEquals(emptyList<ComponentsManager.CatalogItem>(), requested)
        compose.onNodeWithText(nightlyTab).performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("DD-dxvk-3.1.1+153-nightly").assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(listOf(nightly), requested)
    }

    @Test fun sameNamedComponentsFromDifferentSourcesToggleIndependently() {
        val stableA = sourced("dxvk-3.1.1-linux.wcp", "owner/a", false, 1)
        val stableB = sourced("dxvk-3.1.1-linux.wcp", "owner/b", false, 2)
        val nightlyA = sourced("DD-dxvk-3.1.1+101-nightly.wcp", "owner/a", true, 3)
        val nightlyB = sourced("DD-dxvk-3.1.1+202-nightly.wcp", "owner/b", true, 4)
        show(catalog = listOf(stableA, stableB, nightlyA, nightlyB))
        compose.onNodeWithTag("cmp-download-dxvk").performClick()
        compose.onNodeWithText("owner/a", substring = true).assertExists()
        compose.onNodeWithText("owner/b", substring = true).assertExists()

        val nightlyLabel = RuntimeEnvironment.getApplication().getString(R.string.comp_channel_nightly)
        compose.onAllNodesWithText(nightlyLabel).onFirst().performClick()
        compose.onNodeWithText("DD-dxvk-3.1.1+101-nightly").assertExists()
        compose.onNodeWithText("DD-dxvk-3.1.1+202-nightly").assertDoesNotExist()
        compose.onAllNodesWithText("dxvk-3.1.1-linux").assertCountEquals(1)
        compose.onAllNodesWithText(nightlyLabel).onLast().performClick()
        compose.onNodeWithText("DD-dxvk-3.1.1+202-nightly").assertExists()
    }

    @Test fun qualifiedDownloadDoesNotDisableAnotherSourceWithSameFilename() {
        val stableA = sourced("dxvk-3.1.1-linux.wcp", "owner/a", false, 1)
        val stableB = sourced("dxvk-3.1.1-linux.wcp", "owner/b", false, 2)
        val requested = mutableListOf<ComponentsManager.CatalogItem>()
        show(catalog = listOf(stableA, stableB), downloads = mapOf(stableA.id to 45), onDownload = requested::add)
        compose.onNodeWithTag("cmp-download-dxvk").performClick()
        compose.onNodeWithText("owner/a", substring = true).assertExists()
        compose.onNodeWithText("owner/b", substring = true).assertExists()
        compose.onAllNodesWithText("dxvk-3.1.1-linux").onFirst().assertIsNotEnabled()
        compose.onAllNodesWithText("dxvk-3.1.1-linux").onLast().performClick()
        assertEquals(listOf(stableB), requested)
    }

    @Test fun storedQualifiedPackageDoesNotDisableAnotherSourceWithSameFilename() {
        val stableA = sourced("dxvk-3.1.1-linux.wcp", "owner/a", false, 1)
        val stableB = sourced("dxvk-3.1.1-linux.wcp", "owner/b", false, 2)
        val requested = mutableListOf<ComponentsManager.CatalogItem>()
        show(catalog = listOf(stableA, stableB),
            packages = listOf(ComponentsManager.Package(stableA.localFile, "dxvk", "3.1.1", "", 100)),
            onDownload = requested::add)
        compose.onNodeWithTag("cmp-download-dxvk").performClick()
        compose.onNodeWithText("owner/a", substring = true).assertExists()
        compose.onNodeWithText("owner/b", substring = true).assertExists()
        compose.onAllNodesWithText("dxvk-3.1.1-linux").onFirst().assertIsNotEnabled()
        compose.onAllNodesWithText("dxvk-3.1.1-linux").onLast().performClick()
        assertEquals(listOf(stableB), requested)
    }

    @Test fun emptyCatalogOffersRefreshAndDisablesVersionChoicesWhileChecking() {
        var refreshes = 0
        show(catalog = emptyList(), checking = false, onRefresh = { refreshes++ })
        compose.onNodeWithTag("cmp-download-refresh").performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("cmp-download-dxvk").assertIsNotEnabled()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.comp_refresh_to_list)).assertExists()
    }

    @Test fun catalogRefreshCannotBeStartedTwiceWhileChecking() {
        show(checking = true)
        compose.onNodeWithTag("cmp-download-refresh").assertIsNotEnabled()
    }

    private fun show(
        catalog: List<ComponentsManager.CatalogItem> = listOf(dxvk, stored, inFlight, fex),
        downloads: Map<String, Int> = emptyMap(),
        packages: List<ComponentsManager.Package> = listOf(ComponentsManager.Package(stored.file, "dxvk", "2.3", "", 100)),
        checking: Boolean = false,
        onRefresh: () -> Unit = {},
        onDownload: (ComponentsManager.CatalogItem) -> Unit = {},
    ) {
        compose.setContent {
            inputMode = LocalInputModeManager.current
            DroidDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ComponentDownloadsDrawerGroup(
                        host = rememberMenuHost(), catalog = catalog, catalogAt = 0,
                        checking = checking,
                        packages = packages,
                        downloads = downloads, onRefresh = onRefresh, onDownload = onDownload,
                        track = { Modifier.testTag(it) },
                    )
                }
            }
        }
    }

    private fun item(file: String, comp: String) = ComponentsManager.CatalogItem(
        file, comp, "Nightlies", "https://github.com/example/$file", 100, "sha256:test",
    )

    private fun sourced(file: String, source: String, nightly: Boolean, asset: Long) = ComponentsManager.CatalogItem(
        file, "dxvk", if (nightly) ComponentsManager.DD_NIGHTLY else "Dxvk-Linux",
        "https://github.com/$source/releases/download/v1/$file", 100, "sha256:$source",
        sourceId = source, repository = source, assetId = asset, localFile = "${source.substringAfter('/')}-$file",
    )
}
