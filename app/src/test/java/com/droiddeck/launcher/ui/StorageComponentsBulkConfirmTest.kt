package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.storage.ComponentUsage
import com.droiddeck.launcher.storage.StorageReport
import com.droiddeck.launcher.storage.StorageState
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorageComponentsBulkConfirmTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private fun reportWith(components: List<ComponentUsage>) = StorageReport(
        at = 1L,
        total = 64L * 1024 * 1024 * 1024,
        free = 32L * 1024 * 1024 * 1024,
        buckets = emptyMap(),
        prefixes = 0L,
        shaders = 0L,
        workshop = 0L,
        flatpak = 0L,
        games = emptyList(),
        tools = emptyList(),
        leftovers = emptyList(),
        logs = emptyList(),
        logBytes = 0L,
        components = components,
        flatpakApps = emptyList(),
        flatpakRuntimes = 0L,
        otherPrefixes = 0L,
        leftoversChecked = true,
    )

    @Test fun bulkRemoveWithUnusedPackageShowsImportedWarningAndCancelKeepsIdle() {
        val context = RuntimeEnvironment.getApplication()
        val mb10 = 10L * 1024 * 1024
        val report = reportWith(
            listOf(
                ComponentUsage(comp = "dxvk", version = "2.4.1", bytes = mb10, inUse = false, packageFile = "dxvk-2.4.1.wcp"),
                ComponentUsage(comp = "vkd3d-proton", version = "2.13", bytes = mb10, inUse = false, protonId = "proton-9", protonName = "Proton 9"),
            ),
        )
        compose.setContent {
            DroidDeckTheme {
                Column {
                    ComponentsView(report = report, sessionRunning = false, onBack = {})
                }
            }
        }
        val removeUnusedBtn = context.getString(R.string.storage_remove_unused, "20 MB")
        compose.onNodeWithText(removeUnusedBtn).performClick()
        assertFalse(StorageState.cleaning)

        val expectedWarning = context.getString(R.string.storage_component_confirm, "20 MB")
        compose.onNodeWithText(expectedWarning, substring = true).assertExists()
        compose.onNodeWithText("• DXVK 2.4.1  10 MB", substring = true).assertExists()

        compose.onNodeWithText(context.getString(R.string.common_cancel)).performClick()
        compose.onNodeWithText(expectedWarning, substring = true).assertDoesNotExist()
        assertFalse(StorageState.cleaning)
    }

    @Test fun bulkRemoveWithOnlyUnusedOriginalsShowsOriginalConfirmText() {
        val context = RuntimeEnvironment.getApplication()
        val mb10 = 10L * 1024 * 1024
        val report = reportWith(
            listOf(
                ComponentUsage(comp = "dxvk", version = "2.4", bytes = mb10, inUse = false, protonId = "proton-9", protonName = "Proton 9"),
            ),
        )
        compose.setContent {
            DroidDeckTheme {
                Column {
                    ComponentsView(report = report, sessionRunning = false, onBack = {})
                }
            }
        }
        val removeUnusedBtn = context.getString(R.string.storage_remove_unused, "10 MB")
        compose.onNodeWithText(removeUnusedBtn).performClick()
        assertFalse(StorageState.cleaning)

        val expectedWarning = context.getString(R.string.storage_original_confirm, "10 MB")
        compose.onNodeWithText(expectedWarning, substring = true).assertExists()
    }
}
