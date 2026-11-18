package com.droiddeck.launcher.stores.ea

import com.droiddeck.launcher.stores.CloudSaves
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class EaCloudTest {
    private val heat = """<saveFileCriteria>
<include order="0">%Documents%/Need for Speed Heat/SaveGame/savegame/</include>
<include order="1">%Documents%/Need for Speed Heat/SaveGame/wraps/</include>
<include order="2">%Documents%/Need for Speed Heat/settings/PROFILEOPTIONS</include>
</saveFileCriteria>"""

    @Test fun rulesFindTheFolderTheyShareAndPickOnlyTheirFiles() {
        val rules = EaSaveRules.parse(heat)!!
        assertEquals("%Documents%/Need for Speed Heat/", rules.base())
        assertEquals("%DOCUMENTS%/Need for Speed Heat/", EaSaveRules.template(rules.base()!!))
        assertTrue(rules.matches("%Documents%/Need for Speed Heat/SaveGame/savegame/1"))
        assertTrue(rules.matches("%documents%\\need for speed heat\\settings\\PROFILEOPTIONS"))
        assertFalse(rules.matches("%Documents%/Need for Speed Heat/settings/PROFILEOPTIONS.bak"))
        assertFalse(rules.matches("%Documents%/Need for Speed Heat/Logs/crash.txt"))
    }

    @Test fun globsStayInsideTheirFolderAndExcludesWin() {
        val bf = EaSaveRules.parse("""<saveFileCriteria><include order="0">%Documents%/Battlefield 6/settings/*.xml</include>
            <include order="2">%Documents%/Battlefield 6/settings/*.sav</include></saveFileCriteria>""")!!
        assertEquals("%Documents%/Battlefield 6/settings/", bf.base())
        assertTrue(bf.matches("%Documents%/Battlefield 6/settings/a.sav"))
        assertFalse(bf.matches("%Documents%/Battlefield 6/settings/sub/a.sav"))
        val le = EaSaveRules.parse("""<saveFileCriteria>
   <exclude order="0">%Documents%/BioWare/Mass Effect Legendary Edition/Config/</exclude>
   <include order="1">%Documents%/BioWare/Mass Effect Legendary Edition/</include></saveFileCriteria>""")!!
        assertTrue(le.matches("%Documents%/BioWare/Mass Effect Legendary Edition/Save/ME1/x.pcsav"))
        assertFalse(le.matches("%Documents%/BioWare/Mass Effect Legendary Edition/Config/GamerSettings.ini"))
        assertNull(EaSaveRules.parse("<saveFileCriteria></saveFileCriteria>"))
        assertNull(EaSaveRules.parse("""<saveFileCriteria><include order="0">C:/Games/x/</include></saveFileCriteria>""")!!.base())
    }

    @Test fun aManifestKeepsItsFilesSnapshotsAndEaTimestampsThroughAWrite() {
        val xml = """<manifest xmlns="http://origin.com/cloudsaves/manifest" lineage="{00000000-0000-0000-0000-000000000000}">
 <file size="115352" href="content/115352-c7996be80a6e38d1f8ef28866b114f8b" md5="x5lr6ApuONH47yiGaxFPiw==">
  <localName created="2022-10-17T23:51:04ZZ" lastModified="2022-10-17T23:51:04ZZ">%Documents%/Need for Speed Heat/SaveGame/savegame/1</localName>
 </file>
 <snapshot>80cmnqT/rIKQRmciINSVhQ==</snapshot>
</manifest>"""
        val m = EaCloud.parse(xml)
        assertEquals(1, m.files.size)
        assertEquals("c7996be80a6e38d1f8ef28866b114f8b", EaCloud.hex(m.files[0].md5))
        assertEquals(1666050664000L, EaCloud.parseTime(m.files[0].modified))
        assertEquals("2022-10-17T23:51:04ZZ", EaCloud.stamp(1666050664123L))
        val again = EaCloud.parse(EaCloud.write(m))
        assertEquals(m.files.single().localName, again.files.single().localName)
        assertEquals(m.files.single().href, again.files.single().href)
        assertEquals(listOf("80cmnqT/rIKQRmciINSVhQ=="), again.snapshots)
        assertEquals(m.lineage, again.lineage)
    }

    @Test fun commentedRulesAreIgnoredAndNamesKeepTheirSpaces() {
        val rules = EaSaveRules.parse("""<saveFileCriteria>
<!-- <exclude order="0">%Documents%/Game/Config/</exclude> -->
<include order="1"> %Documents%/Game/ </include></saveFileCriteria>""")!!
        assertEquals(listOf("%Documents%/Game/"), rules.includes)
        assertTrue(rules.excludes.isEmpty())
        assertTrue(rules.matches("%Documents%/Game/Config/a.ini"))
        assertEquals("%Documents%/Game/slot.sav ", EaSaveRules.normalize("%Documents%\\Game\\slot.sav "))
    }

    @Test fun snapshotEntitiesAreDecodedOnceAndWrittenBackTheSame() {
        val m = EaCloud.parse("""<manifest lineage="{1}"><snapshot>abc&#x2B;def</snapshot></manifest>""")
        assertEquals(listOf("abc+def"), m.snapshots)
        assertEquals(listOf("abc+def"), EaCloud.parse(EaCloud.write(m)).snapshots)
    }

    @Test fun cloudKeysFollowTheMultiplayerId() {
        assertEquals(listOf("195133_195133"), EaCloud.keys("195133", "195133"))
        assertEquals(listOf("52209", "52209_52209"), EaCloud.keys("52209", ""))
        assertEquals("https://x/y?a=1&b=2", EaCloud.manifestUrl("<sync><manifest>https://x/y?a=1&amp;b=2</manifest></sync>"))
        assertTrue(CloudSaves.supports(Store.EA))
    }
}
