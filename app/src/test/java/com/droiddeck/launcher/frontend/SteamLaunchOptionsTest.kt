package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamLaunchOptionsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun config(apps: String) = listOf(
        "\"UserLocalConfigStore\"", "{", "\t\"Software\"", "\t{", "\t\t\"Valve\"", "\t\t{", "\t\t\t\"Steam\"", "\t\t\t{",
        apps, "\t\t\t}", "\t\t}", "\t}", "\t\"WebStorage\"", "\t{", "\t\t\"x\"\t\t\"1\"", "\t}", "}", "",
    ).joinToString("\n")

    private fun apps(vararg lines: String) = (listOf("\t\t\t\t\"apps\"", "\t\t\t\t{") + lines + "\t\t\t\t}").joinToString("\n")

    private val sparking = arrayOf(
        "\t\t\t\t\t\"1790600\"", "\t\t\t\t\t{", "\t\t\t\t\t\t\"LastPlayed\"\t\t\"1761897600\"", "\t\t\t\t\t\t\"LaunchOptions\"\t\t\"-dx11\"", "\t\t\t\t\t}",
    )

    @Test fun replacesTheOptionsSteamHas() {
        val text = config(apps(*sparking))
        val updated = SteamLaunchOptions.withLaunchOptions(text, 1790600, "-dx12")!!
        assertEquals(text.replace("\"-dx11\"", "\"-dx12\""), updated)
        assertEquals("-dx12", SteamLaunchOptions.launchOptions(updated, 1790600))
    }

    @Test fun addsOptionsToAGameThatHasNone() {
        val text = config(apps("\t\t\t\t\t\"1790600\"", "\t\t\t\t\t{", "\t\t\t\t\t\t\"LastPlayed\"\t\t\"1761897600\"", "\t\t\t\t\t}"))
        assertEquals(config(apps(*sparking)).replace("-dx11", "-vulkan"), SteamLaunchOptions.withLaunchOptions(text, 1790600, "-vulkan"))
    }

    @Test fun addsTheGameAndTheAppsSectionWhenSteamHasNeither() {
        val updated = SteamLaunchOptions.withLaunchOptions(config("\t\t\t\t\"language\"\t\t\"english\""), 1790600, "-dx11")!!
        assertEquals(
            config(
                "\t\t\t\t\"language\"\t\t\"english\"\n" +
                    apps("\t\t\t\t\t\"1790600\"", "\t\t\t\t\t{", "\t\t\t\t\t\t\"LaunchOptions\"\t\t\"-dx11\"", "\t\t\t\t\t}"),
            ),
            updated,
        )
        assertEquals("-dx11", SteamLaunchOptions.launchOptions(updated, 1790600))
    }

    @Test fun quotesAndBackslashesAreEscapedAndReadBack() {
        val options = "PROTON_LOG=1 %command% -path \"C:\\Games\\DB\""
        val updated = SteamLaunchOptions.withLaunchOptions(config(apps(*sparking)), 1790600, options)!!
        assertEquals(true, updated.contains("\"PROTON_LOG=1 %command% -path \\\"C:\\\\Games\\\\DB\\\"\""))
        assertEquals(options, SteamLaunchOptions.launchOptions(updated, 1790600))
    }

    @Test fun emptyOptionsClearSteams() {
        val updated = SteamLaunchOptions.withLaunchOptions(config(apps(*sparking)), 1790600, "")!!
        assertEquals("", SteamLaunchOptions.launchOptions(updated, 1790600))
    }

    @Test fun windowsLineEndsAreKept() {
        val text = config("\t\t\t\t\"language\"\t\t\"english\"").replace("\n", "\r\n")
        val updated = SteamLaunchOptions.withLaunchOptions(text, 1790600, "-dx11")!!
        assertEquals(false, updated.replace("\r\n", "").contains('\n'))
        assertEquals("-dx11", SteamLaunchOptions.launchOptions(updated, 1790600))
    }

    @Test fun onlyTheGamesOwnSectionChanges() {
        // A section named like the game, deeper inside another app, is not the game's.
        val other = arrayOf(
            "\t\t\t\t\t\"7\"", "\t\t\t\t\t{", "\t\t\t\t\t\t\"1790600\"", "\t\t\t\t\t\t{", "\t\t\t\t\t\t\t\"LaunchOptions\"\t\t\"keep\"", "\t\t\t\t\t\t}", "\t\t\t\t\t}",
        )
        val updated = SteamLaunchOptions.withLaunchOptions(config(apps(*other, *sparking)), 1790600, "-dx12")!!
        assertEquals(true, updated.contains("\"keep\""))
        assertEquals("-dx12", SteamLaunchOptions.launchOptions(updated, 1790600))
        assertNull(SteamLaunchOptions.launchOptions(config(apps(*other)), 1790600))
    }

    @Test fun otherFilesAreLeftAlone() {
        assertNull(SteamLaunchOptions.withLaunchOptions("\"InstallConfigStore\"\n{\n}\n", 1790600, "-dx11"))
        assertNull(SteamLaunchOptions.withLaunchOptions("", 1790600, "-dx11"))
        assertNull(SteamLaunchOptions.launchOptions("", 1790600))
    }

    private fun steamHome(): File = tmp.newFolder("Steam").also { home ->
        File(home, "config").mkdirs()
        File(home, "config/loginusers.vdf").writeText(
            "\"users\"\n{\n\t\"76561198021239924\"\n\t{\n\t\t\"AccountName\"\t\t\"a\"\n\t\t\"MostRecent\"\t\t\"1\"\n\t}\n}\n")
    }

    private fun localConfig(home: File, account: String) = File(home, "userdata/$account/config/localconfig.vdf").apply { parentFile!!.mkdirs() }

    @Test fun sessionStartPutsTheOptionsSetHereIntoEveryAccount() {
        val home = steamHome()
        val first = localConfig(home, "60974196").apply { writeText(config(apps(*sparking))) }
        val second = localConfig(home, "123").apply { writeText(config("\t\t\t\t\"language\"\t\t\"english\"")) }
        SteamLaunchOptions.stage(app, home)
        assertEquals("-dx11", SteamLaunchOptions.launchOptions(first.readText(), 1790600))
        assertNull(SteamLaunchOptions.launchOptions(second.readText(), 1790600))

        SessionPrefs.setSteamGameLaunch(app, 1790600, " -dx12 ")
        SessionPrefs.setSteamGameLaunch(app, 400, "-novid")
        SteamLaunchOptions.stage(app, home)
        for (file in listOf(first, second)) {
            assertEquals("-dx12", SteamLaunchOptions.launchOptions(file.readText(), 1790600))
            assertEquals("-novid", SteamLaunchOptions.launchOptions(file.readText(), 400))
        }
        assertEquals(false, File(first.parentFile, "localconfig.vdf.staged").exists())
    }

    @Test fun steamsOwnOptionsComeFromTheSignedInAccount() {
        val home = steamHome()
        localConfig(home, "60974196").writeText(config(apps(*sparking)))
        assertEquals("-dx11", SteamLaunchOptions.steam(home, 1790600))
        assertNull(SteamLaunchOptions.steam(home, 400))
        assertEquals(mapOf<Int, String>(), SessionPrefs.steamGameLaunches(app))
        SessionPrefs.setSteamGameLaunch(app, 1790600, "")
        assertEquals(mapOf(1790600 to ""), SessionPrefs.steamGameLaunches(app))
    }
}
