package com.droiddeck.launcher.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentsBionicFexTest {
    private val bionicTool = "DroidDeck-Bionic-12.8-r24"
    private val ownTool = "DroidDeck-Proton-11-12.8"

    private fun proton(dir: String, valve: Boolean = false) =
        ComponentsManager.Proton(dir, dir, File("/steam/compatibilitytools.d/$dir"), "/p/$dir", "$dir-1", valve)

    private fun item(file: String, release: String) = ComponentsManager.CatalogItem(file, "fex", release, "", 1, "")

    @Test fun onlyBionicNamedPackagesFitDroidDeckBionic() {
        assertTrue(ComponentsManager.bionicFex("FEXCore-2611.1-bionic.wcp"))
        assertTrue(ComponentsManager.bionicFex("FEXCore-2611.1-PPA-BIONIC.WCP"))
        assertFalse(ComponentsManager.bionicFex("FEXCore-2611.1-linux.wcp"))
        assertFalse(ComponentsManager.bionicFex("DD-FEXCore-2611+51-nightly.wcp"))
        assertFalse(ComponentsManager.bionicFex("bionic-FEXCore-2611.wcp"))
        assertTrue(ComponentsManager.bionicTool(bionicTool))
        assertFalse(ComponentsManager.bionicTool(ownTool))
        assertFalse(ComponentsManager.bionicTool("GE-Proton10-25"))
        assertFalse(ComponentsManager.bionicTool(null))
        assertTrue(ComponentsManager.fexFits(bionicTool, "FEXCore-2611-bionic.wcp"))
        assertFalse(ComponentsManager.fexFits(bionicTool, "FEXCore-2611-linux.wcp"))
        assertFalse(ComponentsManager.fexFits(bionicTool, "DD-FEXCore-2611+51-nightly.wcp"))
        assertTrue(ComponentsManager.fexFits(ownTool, "DD-FEXCore-2611+51-nightly.wcp"))
        assertTrue(ComponentsManager.fexFits(ownTool, "FEXCore-2611-linux.wcp"))
        assertFalse(ComponentsManager.fexFits(ownTool, "FEXCore-2611-bionic.wcp"))
        assertFalse(ComponentsManager.fexFits(null, "FEXCore-2611-bionic.wcp"))
    }

    @Test fun aTwinSwapsTheLinuxAndBionicEnding() {
        assertEquals("FEXCore-2611.1-bionic.wcp", ComponentsManager.fexTwin("FEXCore-2611.1-linux.wcp"))
        assertEquals("FEXCore-2611.1-linux.wcp", ComponentsManager.fexTwin("FEXCore-2611.1-bionic.wcp"))
        assertEquals("FEXCore-2611-PPA-bionic.wcp", ComponentsManager.fexTwin("FEXCore-2611-PPA-linux.wcp"))
        assertNull(ComponentsManager.fexTwin("DD-FEXCore-2611+51-nightly.wcp"))
        assertNull(ComponentsManager.fexTwin("my-fex.wcp"))
    }

    @Test fun aLaunchTakesTheChoiceOrItsStoredTwinForEachFlavor() {
        val stored = setOf("FEXCore-2611.1-linux.wcp", "FEXCore-2611.1-bionic.wcp", "FEXCore-2610-linux.wcp")
        val has: (String) -> Boolean = { it in stored }
        assertEquals("FEXCore-2611.1-bionic.wcp", ComponentsManager.launchFex(bionicTool, "FEXCore-2611.1-linux.wcp", has))
        assertEquals("FEXCore-2611.1-bionic.wcp", ComponentsManager.launchFex(bionicTool, "FEXCore-2611.1-bionic.wcp", has))
        assertEquals("FEXCore-2611.1-linux.wcp", ComponentsManager.launchFex(ownTool, "FEXCore-2611.1-bionic.wcp", has))
        assertEquals("FEXCore-2610-linux.wcp", ComponentsManager.launchFex(ownTool, "FEXCore-2610-linux.wcp", has))
        assertEquals("DD-FEXCore-2611+51-nightly.wcp", ComponentsManager.launchFex(ownTool, "DD-FEXCore-2611+51-nightly.wcp", has))
        assertNull(ComponentsManager.launchFex(bionicTool, "FEXCore-2610-linux.wcp", has))
        assertNull(ComponentsManager.launchFex(bionicTool, "DD-FEXCore-2611+51-nightly.wcp", has))
    }

    @Test fun fexListsShowTheProtonsFlavorAndTheCurrentChoice() {
        val files = listOf("FEXCore-2611.1-linux.wcp", "FEXCore-2611.1-bionic.wcp", "DD-FEXCore-2611+51-nightly.wcp", "FEXCore-2610-bionic.wcp")
        assertEquals(listOf("FEXCore-2611.1-bionic.wcp", "FEXCore-2610-bionic.wcp"), ComponentsManager.forTool("fex", bionicTool, files) { it })
        assertEquals(listOf("FEXCore-2611.1-linux.wcp", "DD-FEXCore-2611+51-nightly.wcp"), ComponentsManager.forTool("fex", ownTool, files) { it })
        assertEquals(listOf("FEXCore-2611.1-linux.wcp", "DD-FEXCore-2611+51-nightly.wcp"), ComponentsManager.forTool("fex", null, files) { it })
        assertEquals(listOf("FEXCore-2611.1-linux.wcp", "FEXCore-2611.1-bionic.wcp", "FEXCore-2610-bionic.wcp"),
            ComponentsManager.forTool("fex", bionicTool, files, selected = "FEXCore-2611.1-linux.wcp") { it })
        assertEquals(files, ComponentsManager.forTool("dxvk", bionicTool, files) { it })
    }

    @Test fun bionicStableAssetsAreFex() {
        assertEquals("fex", ComponentsManager.assetComp(ComponentsManager.DD_BIONIC, "FEXCore-2611.1-bionic.wcp"))
        assertEquals("fex", ComponentsManager.assetComp(ComponentsManager.DD_BIONIC, "FEXCore-2610-PPA-bionic.wcp"))
        assertEquals("fex", ComponentsManager.assetComp("FexCore-Linux", "FEXCore-2611.1-linux.wcp"))
    }

    @Test fun aGameFexChoiceKeepsItsTwinInUseWhileTheOtherFlavorIsInstalled() {
        val state = JSONObject().put("profiles", JSONObject().put("42", JSONObject().put("fex", "FEXCore-2611.1-linux.wcp")))
        assertEquals(setOf("FEXCore-2611.1-linux.wcp"), ComponentsManager.packagesInUse(state, listOf(proton(ownTool))))
        assertEquals(setOf("FEXCore-2611.1-linux.wcp", "FEXCore-2611.1-bionic.wcp"),
            ComponentsManager.packagesInUse(state, listOf(proton(ownTool), proton(bionicTool))))
    }

    @Test fun aTwinAGameChoiceStillNeedsCannotBeDeletedAsUnused() {
        val state = JSONObject().put("profiles", JSONObject().put("42", JSONObject().put("fex", "FEXCore-2611.1-linux.wcp")))
        assertEquals(bionicTool, ComponentsManager.twinUser(state, listOf(proton(ownTool), proton(bionicTool)), "FEXCore-2611.1-bionic.wcp")?.dir?.name)
        assertEquals(null, ComponentsManager.twinUser(state, listOf(proton(ownTool)), "FEXCore-2611.1-bionic.wcp"))
        assertEquals(null, ComponentsManager.twinUser(state, listOf(proton(ownTool), proton(bionicTool)), "FEXCore-2610-bionic.wcp"))
        assertEquals(null, ComponentsManager.twinUser(JSONObject(), listOf(proton(bionicTool)), "FEXCore-2611.1-bionic.wcp"))
    }

    @Test fun droidDeckBionicFollowsTheNewestBionicFexAndNothingElse() {
        val bionic = proton(bionicTool)
        val own = proton(ownTool)
        assertTrue(ComponentUpdates.autoFollows(bionic, "fex"))
        assertFalse(ComponentUpdates.autoFollows(bionic, "dxvk"))
        assertFalse(ComponentUpdates.autoFollows(bionic, "vkd3d"))
        assertFalse(ComponentUpdates.autoFollows(proton(bionicTool, valve = true), "fex"))
        assertFalse(ComponentUpdates.follows(bionic))
        assertTrue(ComponentUpdates.autoFollows(own, "dxvk"))
        assertFalse(ComponentUpdates.autoFollows(proton("GE-Proton10-25"), "fex"))
        assertEquals("fex-bionic", ComponentUpdates.fexLine(bionic).key)
        assertEquals("fex", ComponentUpdates.fexLine(own).key)
        assertEquals("fex", ComponentUpdates.line("fex", false).key)

        val catalog = listOf(
            item("DD-FEXCore-2611+51-nightly.wcp", ComponentsManager.DD_NIGHTLY),
            item("DD-FEXCore-2611+52-bionic.wcp", ComponentsManager.DD_NIGHTLY),
            item("FEXCore-2611.1-linux.wcp", "FexCore-Linux"),
            item("FEXCore-2610-bionic.wcp", ComponentsManager.DD_BIONIC),
            item("FEXCore-2611-PPA-bionic.wcp", ComponentsManager.DD_BIONIC),
            item("FEXCore-2611.1-bionic.wcp", ComponentsManager.DD_BIONIC),
        )
        assertEquals("FEXCore-2611.1-bionic.wcp", ComponentUpdates.pick(catalog, ComponentUpdates.fexLine(bionic), emptySet())?.file)
        assertNull(ComponentUpdates.pick(catalog, ComponentUpdates.fexLine(bionic), setOf("FEXCore-2611.1-bionic.wcp")))
        assertEquals("DD-FEXCore-2611+51-nightly.wcp", ComponentUpdates.pick(catalog, ComponentUpdates.fexLine(own), emptySet())?.file)
    }

    @Test fun pruningTheBionicLineKeepsBionicPackagesTheUserDownloaded() {
        val line = ComponentUpdates.fexLine(proton(bionicTool))
        val current = "FEXCore-2611.1-bionic.wcp"
        val stored = listOf("FEXCore-2610-bionic.wcp", "FEXCore-2611-PPA-bionic.wcp", current, "FEXCore-2610-linux.wcp")
        assertEquals(listOf("FEXCore-2610-bionic.wcp"),
            ComponentUpdates.stale(line, setOf("FEXCore-2610-bionic.wcp", current), stored, setOf(current), current))
    }

    @Test fun aBionicProtonsOwnFexChoiceIsNotHandedBack() {
        val bionic = proton(bionicTool)
        val choices = mapOf(bionic.id to mapOf(
            "fex" to ComponentsManager.Choice("FEXCore-2611.1-bionic.wcp", false, bionic.version),
            "dxvk" to ComponentsManager.Choice("DD-dxvk-3.1.1+190-nightly.wcp", false, bionic.version),
        ))
        assertEquals(listOf(bionic to "dxvk"),
            ComponentUpdates.strays(listOf(bionic), choices, setOf("FEXCore-2611.1-bionic.wcp", "DD-dxvk-3.1.1+190-nightly.wcp")))
    }
}
