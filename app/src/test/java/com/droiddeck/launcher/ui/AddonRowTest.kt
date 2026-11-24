package com.droiddeck.launcher.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.WowAddonCatalog
import com.droiddeck.launcher.runtime.WowAddons
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

    private fun disk(vararg addons: WowAddons.Installed) = addons.associateBy { it.name.lowercase() }

    @Test fun anotherSourcesAddonOnDiskIsUpdatableOnlyToANewerVersion() {
        val onDisk = disk(WowAddons.Installed("Questie", "11.2.0", null), WowAddons.Installed("QuestieDB", null, null))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("Questie"), onDisk, "6.2.5", "1"))
        assertEquals(AddonStatus.UPDATE, diskStatus(listOf("Questie"), onDisk, "11.3.0", "1"))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("Questie"), onDisk, "v11.2.0", "1"))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("QuestieDB"), onDisk, "1.0", "1"))
    }

    @Test fun anIncomparableVersionIsAnUpdateOnlyFromTheSameSource() {
        val onDisk = disk(WowAddons.Installed("TomTom", "v4.0.1-release", "12"))
        assertEquals(AddonStatus.UPDATE, diskStatus(listOf("TomTom"), onDisk, "v4.0.1-beta", "12"))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("TomTom"), onDisk, "v4.0.1-beta", "99"))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("TomTom"), onDisk, "4.0.0", "12"))
    }

    @Test fun foldersOnDiskMatchWhateverTheirCase() {
        val onDisk = disk(WowAddons.Installed("questie", "11.2.0", null))
        assertEquals(AddonStatus.INSTALLED, diskStatus(listOf("Questie"), onDisk, "11.2.0", "1"))
    }

    @Test fun anAddonCountsAsInstalledOnlyWhenEveryFolderIsOnDisk() {
        val onDisk = disk(WowAddons.Installed("Bartender4", "4.19.2", null))
        assertEquals(AddonStatus.INSTALL, diskStatus(listOf("Bartender4", "Bartender4_Options"), onDisk, "4.19.2", "1"))
        assertEquals(AddonStatus.INSTALL, diskStatus(emptyList(), onDisk, "4.19.2", "1"))
        assertEquals(AddonStatus.INSTALL, diskStatus(listOf("TomTom"), onDisk, "4.4.1", "1"))
    }
}
