package com.droiddeck.launcher.session

import com.droiddeck.launcher.frontend.AddedGames
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
class GameStorageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun onlyAGamesFolderSteamInstalledIntoStaysALibrary() {
        val pc = tmp.newFolder("PC")
        File(pc, "steamapps").mkdirs()
        File(pc, "steamapps/appmanifest_220.acf").writeText("\"AppState\" { \"appid\" \"220\" }")
        val plain = tmp.newFolder("Games")
        File(plain, "steamapps/common").mkdirs()
        val missing = File(tmp.root, "Unplugged")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path, plain.path, missing.path))
        val libraries = GameStorage.gamesFolderLibraries(app)
        assertEquals(listOf(pc), libraries.map { it.host })
        assertEquals(listOf(AddedGames.GUEST_DIR + "/PC"), libraries.map { it.guest })
        assertEquals(listOf("PC"), libraries.map { it.label })
    }

    @Test fun aChosenLibraryOrInternalKeepsGamesFoldersPlain() {
        val pc = tmp.newFolder("PC")
        File(pc, "steamapps").mkdirs()
        File(pc, "steamapps/appmanifest_220.acf").writeText("x")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
        SessionPrefs.setGameStorage(app, tmp.newFolder("chosen").path, "Chosen")
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
    }

    @Test fun namesTheClientCannotStoreAreLeftOut() {
        val odd = listOf(tmp.newFolder("Quote\"d"), tmp.newFolder("Back\\slash"))
        odd.forEach { File(it, "steamapps").mkdirs(); File(it, "steamapps/appmanifest_220.acf").writeText("x") }
        SessionPrefs.setAddedGamesDirs(app, odd.map { it.path })
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
    }
}
