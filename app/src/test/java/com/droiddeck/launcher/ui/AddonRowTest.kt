package com.droiddeck.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.WowAddonCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land", application = android.app.Application::class)
class AddonRowTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private val addon = WowAddonCatalog.Addon("298842", "Leatrix Maps", "Leatrix", "", 0, 0, null)
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show(status: AddonStatus, uninstalls: MutableList<String> = mutableListOf(), installs: MutableList<String> = mutableListOf()) {
        val state = mutableStateOf(status)
        compose.setContent {
            DroidDeckTheme {
                AddonRow(addon, state.value, null, Modifier, { uninstalls += addon.id }) { installs += addon.id }
            }
        }
    }

    @Test fun aMissingAddonOffersOnlyInstall() {
        val installs = mutableListOf<String>()
        show(AddonStatus.INSTALL, installs = installs)
        compose.onAllNodesWithText(text(R.string.common_uninstall)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.common_update)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.store_install)).performClick()
        assertEquals(listOf("298842"), installs)
    }

    @Test fun aCurrentAddonOffersOnlyUninstallAfterAConfirm() {
        val uninstalls = mutableListOf<String>()
        show(AddonStatus.INSTALLED, uninstalls)
        compose.onAllNodesWithText(text(R.string.store_install)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.common_update)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.stores_installed_chip)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.common_uninstall)).performClick()
        assertEquals(emptyList<String>(), uninstalls)
        compose.onAllNodesWithText(text(R.string.common_uninstall))[1].performClick()
        assertEquals(listOf("298842"), uninstalls)
    }

    @Test fun anOutdatedAddonOffersUpdateAndUninstall() {
        val installs = mutableListOf<String>()
        show(AddonStatus.UPDATE, installs = installs)
        compose.onAllNodesWithText(text(R.string.common_uninstall)).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.common_update)).performClick()
        assertEquals(listOf("298842"), installs)
    }

    @Test fun anotherSourcesAddonOnDiskIsInstalledOrUpdatable() {
        val onDisk = mapOf("questie" to "11.2.0", "questiedb" to null)
        assertEquals(AddonStatus.UPDATE, diskStatus(listOf("Questie"), onDisk, "6.2.5"))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("Questie"), onDisk, "v11.2.0"))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("QuestieDB"), onDisk, "1.0"))
    }

    @Test fun anAddonCountsAsInstalledOnlyWhenEveryFolderIsOnDisk() {
        val onDisk = mapOf<String, String?>("bartender4" to "4.19.2")
        assertEquals(AddonStatus.INSTALL, diskStatus(listOf("Bartender4", "Bartender4_Options"), onDisk, "4.19.2"))
        assertEquals(AddonStatus.INSTALL, diskStatus(emptyList(), onDisk, "4.19.2"))
        assertEquals(AddonStatus.INSTALL, diskStatus(listOf("TomTom"), onDisk, "4.4.1"))
    }
}
