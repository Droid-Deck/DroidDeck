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

    @Test fun skyrimGetsTheXactAudioItsInstallScriptRunsOnWindows() {
        val steamapps = tmp.newFolder("steamapps")
        val game = File(steamapps, "common/Skyrim Special Edition").apply { mkdirs() }
        File(steamapps, "appmanifest_489830.acf").writeText("\"AppState\"\n{\n\t\"SharedDepots\"\n\t{\n\t\t\"228990\"\t\t\"228980\"\n\t}\n}\n")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val found = WinCompSources.steamList(context, game, 489830, "Skyrim Special Edition", auto = true)
        assertEquals(listOf("d3dx9", "xact_x64"), found.map { it.component })
        assertTrue(found.all { it.auto && it.origin == WinCompSources.Origin.STEAM })
        assertEquals(listOf("DirectX XACT audio (64-bit)", "Skyrim Special Edition", "489830"), found.last().args)
        assertEquals(emptyList<String?>(), WinCompSources.steamList(context, game, 409710, "Other", auto = true).map { it.component })
    }

    @Test fun theGamesInstallScriptRedistsAreInstalledAsSteamWould() {
        val steamapps = tmp.newFolder("steamapps")
        val game = File(steamapps, "common/DiRT 3 Complete Edition").apply { mkdirs() }
        File(steamapps, "appmanifest_321040.acf").writeText("\"AppState\"\n{\n\t\"SharedDepots\"\n\t{\n\t\t\"228983\"\t\t\"228980\"\n\t}\n}\n")
        File(game, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
            	"Registry" { "HKEY_LOCAL_MACHINE\\Software\\Codemasters" { "string" { "Path" "%INSTALLDIR%" } } }
            	"Run Process"
            	{
            		"OpenAL"
            		{
            			"HasRunKey"		"HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\321040"
            			"process 1"		"%INSTALLDIR%\\_CommonRedist\\OpenAL\\2.0.7.0\\oalinst.exe"
            			"command 1"		"/s"
            		}
            		"VC2010"
            		{
            			"process 1"		"%INSTALLDIR%\\_CommonRedist\\vcredist\\2010\\vcredist_x86.exe"
            		}
            	}
            }
            """.trimIndent(),
        )
        assertEquals(listOf("oalinst", "vcredist2010"), SteamRedists.installScript(game).map { it.first })
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val found = WinCompSources.steamList(context, game, 321040, "DiRT 3 Complete Edition", auto = true)
        assertEquals(listOf("vcredist2010_dll", "oalinst"), found.map { it.component })
        assertTrue(found.all { it.auto && it.origin == WinCompSources.Origin.STEAM })
        assertEquals(emptyList<Pair<String, String>>(), SteamRedists.installScript(File(steamapps, "missing")))
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
