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

    @Test fun gamesFoldersBecomeLibrariesOnAutomatic() {
        val pc = tmp.newFolder("PC")
        val missing = File(tmp.root, "Unplugged")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path, missing.path))
        val libraries = GameStorage.gamesFolderLibraries(app)
        assertEquals(listOf(pc), libraries.map { it.host })
        assertEquals(listOf(AddedGames.GUEST_DIR + "/PC"), libraries.map { it.guest })
        assertEquals(listOf("PC"), libraries.map { it.label })
    }

    @Test fun aChosenLibraryOrInternalKeepsGamesFoldersPlain() {
        SessionPrefs.setAddedGamesDirs(app, listOf(tmp.newFolder("PC").path))
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
        SessionPrefs.setGameStorage(app, tmp.newFolder("chosen").path, "Chosen")
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
    }

    @Test fun namesTheClientCannotStoreAreLeftOut() {
        SessionPrefs.setAddedGamesDirs(app, listOf(tmp.newFolder("Quote\"d").path, tmp.newFolder("Back\\slash").path))
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
    }
}
