package com.droiddeck.launcher.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WinComponentRevisionTest {
    @get:Rule val temp = TemporaryFolder()

    private fun installed(revision: Int? = null): File = temp.newFolder().also { directory ->
        val meta = JSONObject().put("version", "10.0.40219")
        revision?.let { meta.put("revision", it) }
        File(directory, "component.json").writeText(meta.toString())
    }

    @Test fun legacyComponentsStayInstalledUntilTheCatalogRequestsARepair() {
        val directory = installed()
        assertEquals("10.0.40219", WinComponents.installedVersion(directory, 0))
        assertNull(WinComponents.installedVersion(directory, 1))
    }

    @Test fun repairedComponentsStayInstalledAndAreNotDowngradedByAnOlderCatalog() {
        val directory = installed(2)
        assertEquals("10.0.40219", WinComponents.installedVersion(directory, 2))
        assertEquals("10.0.40219", WinComponents.installedVersion(directory, 1))
        assertNull(WinComponents.installedVersion(directory, 3))
    }

    @Test fun outdatedComponentsRemainDownloadedForOfflineControls() {
        val directory = installed()
        assertNull(WinComponents.installedVersion(directory, 1))
        assertEquals(listOf(directory.name), WinComponents.downloadedIds(directory.parentFile))
    }

    @Test fun missingOrInvalidInstallMetadataNeedsInstallation() {
        val directory = temp.newFolder()
        assertNull(WinComponents.installedVersion(directory, 0))
        File(directory, "component.json").writeText("{")
        assertNull(WinComponents.installedVersion(directory, 1))
    }
}
