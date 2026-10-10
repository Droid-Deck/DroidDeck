package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGamesScanTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theSteamLibraryInsideAGamesFolderIsNotAGame() {
        val games = tmp.newFolder("PC")
        File(games, "Example").mkdirs()
        File(games, "Example/Example.exe").writeText("game")
        File(games, "steamapps/downloading").mkdirs()
        File(games, "steamapps/downloading/Depot.exe").writeText("depot")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        assertEquals(listOf("Example"), AddedGames.scan(app).map { it.name })
    }
}
