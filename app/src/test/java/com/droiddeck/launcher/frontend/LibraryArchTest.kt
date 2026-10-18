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
}
