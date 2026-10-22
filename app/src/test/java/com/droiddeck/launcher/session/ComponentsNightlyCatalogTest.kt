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
    fun nightliesPairWithTheStableOfTheSameFlavorAndVersion() {
        val n = ComponentsManager.DD_NIGHTLY
        fun item(file: String, comp: String, release: String) = ComponentsManager.CatalogItem(file, comp, release, "", 0, "")
        val dxvk = item("dxvk-3.1.1-linux.wcp", "dxvk", "Dxvk-Linux")
        val ec = item("dxvk-arm64ec-3.1.1-linux.wcp", "dxvk", "Dxvk-arm64ec-Linux")
        val gpl = item("dxvk-gplasync-3.1.1-1-linux.wcp", "dxvk", "Dxvk-gplasync-Linux")
        val fex = item("FEXCore-2610-linux.wcp", "fex", "FexCore-Linux")
        val vk = item("vkd3d-proton-3.0.1-linux.wcp", "vkd3d", "Vkd3d-proton-Linux")
        val others = listOf(
            item("dxvk-low-latency-3.1.1-2-linux.wcp", "dxvk", "Dxvk-Linux"),
            item("dxvk-3.1-linux.wcp", "dxvk", "Dxvk-Linux"),
            item("dxvk-gplasync-3.1-1-linux.wcp", "dxvk", "Dxvk-gplasync-Linux"),
            item("FEXCore-2610-d2rfix28-linux.wcp", "fex", "FexCore-Linux"),
        )
        val nDxvk = item("DD-dxvk-3.1.1+153-nightly.wcp", "dxvk", n)
        val nEc = item("DD-dxvk-arm64ec-3.1.1+153-nightly.wcp", "dxvk", n)
        val nGpl = item("DD-dxvk-gplasync-3.1.1+153-nightly.wcp", "dxvk", n)
        val nFex = item("DD-FEXCore-2610+58-nightly.wcp", "fex", n)
        val nVk = item("DD-vkd3d-proton-3.0.1+455-nightly.wcp", "vkd3d", n)
        val nVkEc = item("DD-vkd3d-proton-arm64ec-3.0.1+455-nightly.wcp", "vkd3d", n)
        val pairs = ComponentsManager.nightlyPairs(listOf(dxvk, ec, gpl, fex, vk) + others + listOf(nDxvk, nEc, nGpl, nFex, nVk, nVkEc))
        assertEquals(mapOf(dxvk to nDxvk, ec to nEc, gpl to nGpl, fex to nFex, vk to nVk), pairs)
        // Two nightlies for one stable build: the newer one wins.
        val nDxvkNewer = item("DD-dxvk-3.1.1+154-nightly.wcp", "dxvk", n)
        assertEquals(mapOf(dxvk to nDxvkNewer), ComponentsManager.nightlyPairs(listOf(dxvk, nDxvkNewer, nDxvk)))
        assertEquals(mapOf(dxvk to nDxvkNewer), ComponentsManager.nightlyPairs(listOf(dxvk, nDxvk, nDxvkNewer)))
        // A nightly of a version with no stable build pairs with nothing.
        assertEquals(emptyMap<Any, Any>(), ComponentsManager.nightlyPairs(listOf(item("dxvk-3.1-linux.wcp", "dxvk", "Dxvk-Linux"), nDxvk)))
    }

    @Test
    fun downloadsOnlyFromTheTwoRepositories() {
        assertTrue(ComponentsManager.allowedDownload("https://github.com/The412Banner/Nightlies/releases/download/Dxvk-Linux/dxvk-3.1.1-linux.wcp"))
        assertTrue(ComponentsManager.allowedDownload("https://github.com/Droid-Deck/DroidDeck-Components/releases/download/linux-nightly/DD-FEXCore-2610+58-nightly.wcp"))
        assertFalse(ComponentsManager.allowedDownload("https://github.com/someone/else/releases/download/x/y.wcp"))
        assertFalse(ComponentsManager.allowedDownload("https://github.com/Droid-Deck/DroidDeck-Components-evil/releases/download/x/y.wcp"))
    }
}
