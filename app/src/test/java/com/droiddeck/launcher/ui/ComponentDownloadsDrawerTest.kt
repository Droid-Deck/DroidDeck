package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.ComponentsManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComponentDownloadsDrawerTest {
    @get:Rule val compose = createRobolectricComposeRule()

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
        checking: Boolean = false,
        onRefresh: () -> Unit = {},
        onDownload: (ComponentsManager.CatalogItem) -> Unit = {},
    ) {
        compose.setContent {
            DroidDeckTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ComponentDownloadsDrawerGroup(
                        host = rememberMenuHost(), catalog = catalog, catalogAt = 0,
                        checking = checking,
                        packages = listOf(ComponentsManager.Package(stored.file, "dxvk", "2.3", "", 100)),
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
}
