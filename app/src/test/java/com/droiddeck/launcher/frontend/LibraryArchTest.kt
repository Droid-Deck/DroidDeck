package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryArchTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun aScanReadsTheClientItWasGivenNotTheSelectedOne() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setSteamClientArch(app, "arm64")
        val apps = File(SessionPrefs.steamRoot(app, "x86_64"), "steamapps").apply { mkdirs() }
        File(apps, "appmanifest_440.acf").writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"440\"\n\t\"name\"\t\t\"Team Fortress 2\"\n\t\"StateFlags\"\t\t\"4\"\n}\n")
        assertEquals(listOf("Team Fortress 2"), Library.launchableGames(app, added = emptyList(), arch = "x86_64").map { it.name })
        assertEquals(emptyList<String>(), Library.launchableGames(app, added = emptyList(), arch = "arm64").map { it.name })
    }

    @Test fun aGameTheOtherClientSharesIsListedFromItsFiles() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val apps = File(SessionPrefs.steamRoot(app, "x86_64"), "steamapps").apply { mkdirs() }
        File(apps, "common/Team Fortress 2").mkdirs()
        val manifest = "\"AppState\"\n{\n\t\"appid\"\t\t\"440\"\n\t\"name\"\t\t\"Team Fortress 2\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"Team Fortress 2\"\n}\n"
        File(apps, "appmanifest_440.acf").writeText(manifest)
        val view = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app), "root/.droiddeck-x64/shared/x86-64/steamapps").apply { mkdirs() }
        File(view, "appmanifest_440.acf").writeText(manifest)
        val shared = Library.steamGames(app, arch = "arm64").single()
        assertEquals("Team Fortress 2", shared.name)
        assertEquals(File(apps, "common/Team Fortress 2"), shared.gameFiles)
        assertEquals(listOf("Team Fortress 2"), Library.steamGames(app, arch = "x86_64").map { it.name })
    }

    @Test fun anAddedGameSteamAlreadyListsFromItsFolderIsShownOnce() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val folder = File(app.filesDir, "games/Example").apply { mkdirs() }
        val exe = File(folder, "Example.exe").apply { writeText("game") }
        val apps = File(SessionPrefs.steamRoot(app, "arm64"), "steamapps").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(apps, "common").apply { mkdirs() }.toPath().resolve("DroidDeck-42"), folder.toPath())
        File(apps, "appmanifest_42.acf").writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"42\"\n\t\"name\"\t\t\"Example\"\n\t\"StateFlags\"\t\t\"4\"\n\t\"installdir\"\t\t\"DroidDeck-42\"\n}\n")
        val added = AddedGames.Game(folder, "Example", exe, "/g/Example.exe", "/g", 0x87654321L, (0x87654321L shl 32) or 0x02000000L, emptyList())
        val other = File(app.filesDir, "games/Other").apply { mkdirs() }
        val second = AddedGames.Game(other, "Other", File(other, "Other.exe"), "/g/Other.exe", "/g", 0x81234567L, (0x81234567L shl 32) or 0x02000000L, emptyList())
        assertEquals(listOf("Example" to "internal", "Other" to Library.ADDED), Library.launchableGames(app, added = listOf(added, second), arch = "arm64").map { it.name to it.library })
    }
}
