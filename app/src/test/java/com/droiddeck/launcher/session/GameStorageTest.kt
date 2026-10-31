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

    @Test fun anUnpluggedDriveFallsBackToInternalWithoutForgettingIt() {
        val gone = "/storage/0000-0000/Android/data/com.droiddeck.launcher/files/steam"
        SessionPrefs.setGameStorage(app, gone, "USB drive")
        assertNull(GameStorage.effective(app))
        assertNull(GameStorage.x64CardLibrary(app))
        assertEquals(gone, SessionPrefs.gameStorage(app))
    }

    @Test fun aPluggedInDriveStaysTheLibrary() {
        val files = tmp.newFolder("files")
        val steam = File(files, "steam")
        SessionPrefs.setGameStorage(app, steam.path, "USB drive")
        assertEquals(steam.path, GameStorage.effective(app)?.path)
        steam.mkdirs()
        assertEquals(steam.path, GameStorage.effective(app)?.path)
        assertEquals("USB drive", GameStorage.effective(app)?.label)
    }

    @Test fun onlyASavedDriveThatIsMissingCountsAsDisconnected() {
        SessionPrefs.setGameStorage(app, "", "")
        assertFalse(GameStorage.disconnected(app))
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        assertFalse(GameStorage.disconnected(app))
        SessionPrefs.setGameStorage(app, "/storage/0000-0000/Android/data/com.droiddeck.launcher/files/steam", "USB drive")
        assertTrue(GameStorage.disconnected(app))
        SessionPrefs.setGameStorage(app, tmp.newFolder("plugged").path, "USB drive")
        assertFalse(GameStorage.disconnected(app))
    }
}
