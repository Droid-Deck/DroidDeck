package com.droiddeck.launcher.frontend

import android.os.Environment
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Games tab's +: one picked .exe becomes a game, once, wherever the session can see it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedExesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @org.junit.Before fun publishAtOnce() { AddedGamesPublisher.now = true }

    private fun exe(path: File): File = path.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(16)) }

    private fun onStorage(rel: String) = exe(File(Environment.getExternalStorageDirectory(), rel))

    private fun noGamesFolders() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
    }

    @Test fun theEntryStoreKeepsOneEntryPerFolder() {
        assertEquals(0, AddedExes.list(app).size)
        AddedExes.put(app, AddedExes.Entry("/a/One", "/a/One/one.exe"))
        AddedExes.put(app, AddedExes.Entry("/a/Two", "/a/Two/two.exe"))
        AddedExes.put(app, AddedExes.Entry("/a/One", "/a/One/bin/one64.exe"))
        assertEquals(listOf("/a/Two" to "/a/Two/two.exe", "/a/One" to "/a/One/bin/one64.exe"), AddedExes.list(app).map { it.folder to it.exe })
        AddedExes.drop(app, "/a/Two")
        assertEquals(listOf("/a/One"), AddedExes.list(app).map { it.folder })
    }

    @Test fun aPickedExeOnInternalStorageBecomesAGameOnce() {
        noGamesFolders()
        val pick = onStorage("Download/Celeste/Celeste.exe")
        val added = AddedGames.addExe(app, pick)
        assertTrue(added is AddedGames.AddResult.Added)
        val game = (added as AddedGames.AddResult.Added).game
        assertEquals("Celeste", game.name)
        assertEquals(pick.path, game.exe.path)
        assertEquals("/root/Storage/Download/Celeste/Celeste.exe", game.guestExe)
        assertEquals("/root/Storage/Download/Celeste", game.guestDir)
        assertEquals(Library.ADDED, game.source)
        assertTrue(File(app.filesDir, "session/added-games.json").readText().contains("Celeste.exe"))

        // The same exe again: the game it already is, no second entry.
        val again = AddedGames.addExe(app, pick)
        assertTrue(again is AddedGames.AddResult.Existing)
        assertEquals(game.appId, (again as AddedGames.AddResult.Existing).game.appId)
        assertEquals(1, AddedExes.list(app).size)
        assertEquals(1, AddedGames.scan(app).size)
    }

    @Test fun anotherExeInTheSameGameSwitchesItsExe() {
        noGamesFolders()
        val first = onStorage("Games2/Hades/Hades.exe")
        val other = onStorage("Games2/Hades/x64/Hades.exe")
        val game = (AddedGames.addExe(app, first) as AddedGames.AddResult.Added).game
        val switched = AddedGames.addExe(app, other)
        assertTrue(switched is AddedGames.AddResult.Switched)
        assertEquals(other.path, (switched as AddedGames.AddResult.Switched).game.exe.path)
        assertEquals(game.appId, switched.game.appId)
        assertEquals(1, AddedGames.scan(app).size)
    }

    @Test fun anExeInsideAListedGamesFolderGameSwitchesThatGame() {
        val games = tmp.newFolder("PC")
        exe(File(games, "Example/Example.exe"))
        val deep = exe(File(games, "Example/bin/Example64.exe"))
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        val result = AddedGames.addExe(app, deep)
        assertTrue(result is AddedGames.AddResult.Switched)
        assertEquals(deep.path, AddedGames.scan(app).single().exe.path)
        // The Games folder's game is an entry of its own (GamesFolderMigration); no second one.
        assertEquals(listOf(File(games, "Example").path), AddedExes.list(app).map { it.folder })
    }

    @Test fun anExeTheSessionCannotSeeIsNotAdded() {
        noGamesFolders()
        val outside = exe(File(tmp.newFolder("elsewhere"), "Game/Game.exe"))
        assertEquals(AddedGames.AddResult.Unreachable, AddedGames.addExe(app, outside))
        assertEquals(0, AddedExes.list(app).size)
        assertEquals(0, AddedGames.scan(app).size)
    }

    @Test fun aRemovedSingleExeGameStaysOffUntilPickedAgain() {
        noGamesFolders()
        val pick = onStorage("Download/Celeste/Celeste.exe")
        val game = (AddedGames.addExe(app, pick) as AddedGames.AddResult.Added).game
        AddedGames.remove(app, game.folder)
        assertEquals(0, AddedGames.scan(app).size)
        assertTrue(AddedGames.addExe(app, pick) is AddedGames.AddResult.Switched)
        assertEquals(1, AddedGames.scan(app).size)
    }
}
