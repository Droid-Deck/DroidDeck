package com.droiddeck.launcher.storage

import com.droiddeck.launcher.frontend.AddedExes
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGameMoveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun game(name: String = "BigGame"): File {
        val folder = tmp.newFolder(name)
        File(folder, "game.exe").writeText("exe")
        File(folder, "data").apply { mkdirs() }
        File(folder, "data/blob.bin").writeText("x".repeat(1024))
        return folder
    }

    @Test fun folderExeRecordsAndBindMoveToTheNewLocation() {
        val folder = game()
        val exe = File(folder, "game.exe")
        AddedExes.put(app, AddedExes.Entry(folder.path, exe.path))
        SessionPrefs.setAddedGameExe(app, folder.path, exe.path)
        val targetParent = tmp.newFolder("ssd")

        val error = AddedGameMove.move(app, folder, targetParent, { _, _ -> })

        assertNull(error)
        val dest = File(targetParent, folder.name)
        assertTrue(File(dest, "game.exe").isFile)
        assertTrue(File(dest, "data/blob.bin").isFile)
        assertFalse(folder.exists())
        val entry = AddedExes.list(app).firstOrNull { it.folder == dest.path }
        assertNotNull(entry)
        assertEquals(File(dest, "game.exe").path, entry!!.exe)
        assertEquals(File(dest, "game.exe").path, SessionPrefs.addedGameExe(app, dest.path))
        assertTrue(SessionPrefs.addedGameExe(app, folder.path).isEmpty())
        assertNotNull(AddedGames.guestPath(app, File(dest, "game.exe")))
        assertNotNull(AddedGames.single(app, dest.path))
    }

    @Test fun aMissingSourceIsRejected() {
        val gone = File(tmp.root, "gone-${System.nanoTime()}")
        val error = AddedGameMove.move(app, gone, tmp.root)
        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_added_move_missing), error)
    }

    @Test fun anExistingDestinationIsRejected() {
        val folder = game("Dup")
        val targetParent = tmp.newFolder("full")
        File(targetParent, folder.name).mkdirs()
        val error = AddedGameMove.move(app, folder, targetParent)
        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_exists), error)
        assertTrue(File(folder, "game.exe").isFile)
    }

    @Test fun movingIntoItselfIsRejected() {
        val folder = game("Loop")
        val error = AddedGameMove.move(app, folder, File(folder, "sub"))
        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_inside), error)
        assertTrue(File(folder, "game.exe").isFile)
        assertFalse(File(folder, "sub").exists())
    }

    @Test fun adoptIntoSteamInstallsTheFolderAsTheManifestDir() {
        val folder = game("AdoptMe")
        val exe = File(folder, "game.exe")
        AddedExes.put(app, AddedExes.Entry(folder.path, exe.path))
        SessionPrefs.setAddedGameExe(app, folder.path, exe.path)
        val library = tmp.newFolder("steamlibrary")
        File(library, "steamapps/common").mkdirs()
        // The adopted dir lives on the card, so the session keeps seeing it after the move.
        SessionPrefs.setGameStorage(app, library.path, "Card")
        val target = MissingInstall(220, "AdoptMe", library, "AdoptMe", "Card")

        val error = AddedGameMove.adoptIntoSteam(app, folder, target, { _, _ -> })

        assertNull(error)
        val dest = File(File(library, "steamapps/common"), "AdoptMe")
        assertTrue(File(dest, "game.exe").isFile)
        assertTrue(File(dest, "data/blob.bin").isFile)
        assertFalse(folder.exists())
        assertTrue(AddedExes.list(app).none { it.folder == folder.path })
        assertEquals(File(dest, "game.exe").path, SessionPrefs.addedGameExe(app, dest.path))
        assertNotNull(AddedGames.guestPath(app, File(dest, "game.exe")))
        assertNotNull(AddedGames.single(app, dest.path))
    }

    @Test fun adoptIntoSteamMovesThePrefixOntoTheSteamAppId() {
        val folder = game("PrefixGame")
        val crc = 12345678901L
        SessionPrefs.addedGameAppId(app, folder.path, crc)
        val internal = SessionPrefs.steamRoot(app, "arm64")
        val oldPrefix = File(File(internal, "steamapps/compatdata"), crc.toString()).apply { mkdirs() }
        File(oldPrefix, "save.dat").writeText("save")
        val library = tmp.newFolder("prefixlib")
        File(library, "steamapps/common").mkdirs()
        SessionPrefs.setGameStorage(app, library.path, "Card")
        val target = MissingInstall(220, "PrefixGame", library, "PrefixGame", "Card")

        val error = AddedGameMove.adoptIntoSteam(app, folder, target, { _, _ -> })

        assertNull(error)
        val targetData = com.droiddeck.launcher.session.SecondaryLibrary.privateRoot(app.filesDir, library)
        assertTrue(File(File(targetData, "steamapps/compatdata/220"), "save.dat").isFile)
        assertFalse(oldPrefix.exists())
    }

    @Test fun adoptIntoSteamRefusesAnOccupiedInstallDir() {
        val folder = game("Clash")
        val library = tmp.newFolder("steamlib")
        val occupied = File(File(library, "steamapps/common"), "Clash").apply { mkdirs() }
        File(occupied, "other.exe").writeText("x")
        val error = AddedGameMove.adoptIntoSteam(app, folder, library, MissingInstall(221, "Clash", library, "Clash", "Card"))
        assertEquals(app.getString(com.droiddeck.launcher.R.string.storage_move_exists), error)
        assertTrue(File(folder, "game.exe").isFile)
    }
}
