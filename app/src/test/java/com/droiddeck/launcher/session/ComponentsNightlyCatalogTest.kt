package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentsNightlyCatalogTest {
    @Test
    fun nightlyFilesGoToTheirComponentList() {
        val n = ComponentsManager.DD_NIGHTLY
        assertEquals("dxvk", ComponentsManager.assetComp(n, "DD-dxvk-3.1.1+153-nightly.wcp"))
        assertEquals("dxvk", ComponentsManager.assetComp(n, "DD-dxvk-arm64ec-3.1.1+153-nightly.wcp"))
        assertEquals("dxvk", ComponentsManager.assetComp(n, "DD-dxvk-gplasync-3.1.1+153-nightly.wcp"))
        assertEquals("vkd3d", ComponentsManager.assetComp(n, "DD-vkd3d-proton-arm64ec-3.0.1+455-nightly.wcp"))
        assertEquals("fex", ComponentsManager.assetComp(n, "DD-FEXCore-2610+58-nightly.wcp"))
        assertNull(ComponentsManager.assetComp(n, "state.json"))
        assertNull(ComponentsManager.assetComp(n, "something-else.wcp"))
        assertEquals("dxvk", ComponentsManager.assetComp("Dxvk-gplasync-Linux", "dxvk-gplasync-3.1.1-1-linux.wcp"))
        assertNull(ComponentsManager.assetComp("Unknown-Release", "dxvk-3.1.1-linux.wcp"))
    }

    @Test
    fun nightliesListBelowTheStablePackages() {
        fun item(file: String, release: String) = ComponentsManager.CatalogItem(file, "dxvk", release, "", 0, "")
        val sorted = listOf(
            item("DD-dxvk-3.1.1+153-nightly.wcp", ComponentsManager.DD_NIGHTLY),
            item("dxvk-2.7.1-linux.wcp", "Dxvk-Linux"),
            item("dxvk-3.1.1-linux.wcp", "Dxvk-Linux"),
        ).sortedWith(ComponentsManager.CATALOG_ORDER).map { it.file }
        assertEquals(listOf("dxvk-3.1.1-linux.wcp", "dxvk-2.7.1-linux.wcp", "DD-dxvk-3.1.1+153-nightly.wcp"), sorted)
    }

    @Test
    fun downloadsOnlyFromTheTwoRepositories() {
        assertTrue(ComponentsManager.allowedDownload("https://github.com/The412Banner/Nightlies/releases/download/Dxvk-Linux/dxvk-3.1.1-linux.wcp"))
        assertTrue(ComponentsManager.allowedDownload("https://github.com/Droid-Deck/DroidDeck-Components/releases/download/linux-nightly/DD-FEXCore-2610+58-nightly.wcp"))
        assertFalse(ComponentsManager.allowedDownload("https://github.com/someone/else/releases/download/x/y.wcp"))
        assertFalse(ComponentsManager.allowedDownload("https://github.com/Droid-Deck/DroidDeck-Components-evil/releases/download/x/y.wcp"))
    }
}
