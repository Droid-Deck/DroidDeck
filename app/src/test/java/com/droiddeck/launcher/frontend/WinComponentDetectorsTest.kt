package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WinComponentDetectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun steamsSharedDepotsBecomeRecommendations() {
        val steamapps = tmp.newFolder("steamapps")
        val game = File(steamapps, "common/BioShock Remastered").apply { mkdirs() }
        File(steamapps, "appmanifest_409710.acf").writeText(
            """
            "AppState"
            {
            	"appid"		"409710"
            	"SharedDepots"
            	{
            		"228983"		"228980"
            		"228984"		"228980"
            		"228988"		"228980"
            		"228987"		"228980"
            		"228990"		"228980"
            		"999999"		"228980"
            	}
            }
            """.trimIndent(),
        )
        val found = SteamRedists.detect(game, 409710)
        assertEquals(listOf("vcredist2010_dll", "vcredist2012_dll", "vcredist2019_dll", "d3dx9"), found.map { it.componentName })
        assertTrue(found.all { it.kind == DependencyDetector.Kind.STEAM })
        assertEquals("VC++ 2010", found.first().reason)
        assertEquals(emptyList<DependencyDetector.Recommendation>(), SteamRedists.detect(game, 1))
    }

    @Test fun thePrefixCountsOnlyWhatDroidDeckDidNotPutThere() {
        val compat = tmp.newFolder("compatdata", "409710")
        val pfx = File(compat, "pfx")
        File(pfx, "drive_c/windows/syswow64").mkdirs()
        File(pfx, "drive_c/windows/syswow64/OpenAL32.dll").writeText("x")
        File(pfx, "system.reg").writeText("[Software\\\\Microsoft\\\\Games for Windows Live]\n")
        assertEquals(setOf("oalinst", "XLiveRedist"), PrefixInstalledDetector.detect(compat))
        File(compat, ".droiddeck-wincomponents.json").writeText("""{"files": {"drive_c/windows/syswow64/openal32.dll": "oalinst_dll"}}""")
        assertEquals(setOf("XLiveRedist"), PrefixInstalledDetector.detect(compat))
        assertEquals(emptySet<String>(), PrefixInstalledDetector.detect(File(tmp.root, "missing")))
    }
}
