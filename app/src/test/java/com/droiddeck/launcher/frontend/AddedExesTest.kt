package com.droiddeck.launcher.frontend

import android.os.Environment
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.Assert.assertEquals
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
        assertEquals(listOf(File(games, "Example").path), AddedExes.list(app).map { it.folder })
    }

    @Test fun aGameInsideAFolderAddedAsOneGameIsAddedOnItsOwn() {
        noGamesFolders()
        val held = onStorage("Download/ADM/Death Stranding/ds.exe")
        val pick = onStorage("Download/ADM/Pro Evolution Soccer 2017/PES2017.exe")
        val container = held.parentFile!!.parentFile!!
        AddedExes.put(app, AddedExes.Entry(container.path, held.path, picked = false))
        val added = AddedGames.addExe(app, pick)
        assertTrue(added is AddedGames.AddResult.Added)
        assertEquals(pick.parentFile!!.path, (added as AddedGames.AddResult.Added).game.folder.path)
        assertEquals(setOf(held.path, pick.path), AddedGames.scan(app).map { it.exe.path }.toSet())
    }

    @Test fun aRemovedFolderGameDoesNotComeBackForAnotherGame() {
        noGamesFolders()
        val held = onStorage("Download/ADM/Death Stranding/ds.exe")
        val pick = onStorage("Download/ADM/Blur Game/Blur.exe")
        val container = held.parentFile!!.parentFile!!
        AddedExes.put(app, AddedExes.Entry(container.path, held.path, picked = false))
        SessionPrefs.setAddedGameName(app, container.path, "Death Stranding Directors Cut")
        AddedGames.remove(app, container)
        val added = AddedGames.addExe(app, pick)
        assertTrue(added is AddedGames.AddResult.Added)
        assertEquals("Blur Game", (added as AddedGames.AddResult.Added).game.name)
        assertTrue(container.path in SessionPrefs.removedAddedGames(app))
        assertEquals(listOf(pick.path), AddedGames.scan(app).map { it.exe.path })
    }

    private fun result(r: AddedGames.AddResult): AddedGames.Game = when (r) {
        is AddedGames.AddResult.Added -> r.game
        is AddedGames.AddResult.Existing -> r.game
        is AddedGames.AddResult.Switched -> r.game
        else -> throw AssertionError("not added: $r")
    }

    private fun visualNovels(): File {
        val storage = tmp.newFolder("PC Games")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        SessionPrefs.setGameStorage(app, storage.path, "SD card")
        exe(File(storage, "VNs/Albatross/Albatross Log.exe"))
        exe(File(storage, "VNs/Rance 01/Rance01.exe"))
        exe(File(storage, "VNs/Angel Beats/SiglusEngine.exe"))
        exe(File(storage, "VNs/Sayonara/SiglusEngine.exe"))
        return storage
    }

    @Test fun aGroupFolderOnGameStorageListsEachGameInsideIt() {
        val storage = visualNovels()
        val vns = File(storage, "VNs")
        assertEquals(listOf("Albatross", "Angel Beats", "Rance 01", "Sayonara").map { File(vns, it).path },
            AddedGames.scan(app).map { it.folder.path })
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun anExePickedInAGroupFolderOnGameStorageIsItsOwnGame() {
        val storage = visualNovels()
        val vns = File(storage, "VNs")
        SessionPrefs.setAddedGameExe(app, vns.path, File(vns, "Albatross/Albatross Log.exe").path)
        for (pick in listOf("Rance 01/Rance01.exe", "Sayonara/SiglusEngine.exe", "Angel Beats/SiglusEngine.exe")) {
            val game = result(AddedGames.addExe(app, File(vns, pick)))
            assertEquals(File(vns, pick).parentFile!!.path, game.folder.path)
        }
        assertEquals(4, AddedGames.scan(app).size)
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aRemovedGroupFolderOnGameStorageStaysRemoved() {
        val storage = visualNovels()
        val group = File(storage, "VNs")
        val pick = File(group, "Rance 01/Rance01.exe")
        AddedGames.remove(app, group)
        val game = result(AddedGames.addExe(app, pick))
        assertEquals(pick.parentFile!!.path, game.folder.path)
        assertTrue(group.path in SessionPrefs.removedAddedGames(app))
        assertEquals(listOf(pick.parentFile!!.path), AddedGames.scan(app).map { it.folder.path })
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aSiblingExeOfAnAddedGameOnGameStorageSwitchesThatGame() {
        val storage = tmp.newFolder("PC Games")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        SessionPrefs.setGameStorage(app, storage.path, "SD card")
        val held = exe(File(storage, "Game/bin/Game.exe"))
        val pick = exe(File(storage, "Game/launcher/Launcher.exe"))
        val game = File(storage, "Game")
        AddedExes.put(app, AddedExes.Entry(game.path, held.path))
        val result = AddedGames.addExe(app, pick)
        assertTrue(result is AddedGames.AddResult.Switched)
        assertEquals(game.path, (result as AddedGames.AddResult.Switched).game.folder.path)
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aHiddenFolderInAGroupIsAddedAsItsOwnGame() {
        val storage = visualNovels()
        val vns = File(storage, "VNs")
        val pick = exe(File(vns, ".Gamma/Gamma.exe"))
        val game = result(AddedGames.addExe(app, pick))
        assertEquals(pick.parentFile!!.path, game.folder.path)
        assertTrue(AddedGames.scan(app).any { it.folder.path == pick.parentFile!!.path })
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aFolderThatDeclaresItsExeInASubfolderStaysOneGame() {
        val storage = tmp.newFolder("PC Games")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        SessionPrefs.setGameStorage(app, storage.path, "SD card")
        exe(File(storage, "Halo/Content/Halo.exe"))
        exe(File(storage, "Halo/Tools/ModKit.exe"))
        File(storage, "Halo/MicrosoftGame.config").writeText("<Game><Executable Name=\"Content\\Halo.exe\" /></Game>")
        assertEquals(listOf(File(storage, "Halo").path), AddedGames.scan(app).map { it.folder.path })
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aGroupFolderTheUserAddedAsOneGameStaysOneGame() {
        val storage = visualNovels()
        val vns = File(storage, "VNs")
        AddedExes.put(app, AddedExes.Entry(vns.path, File(vns, "Albatross/Albatross Log.exe").path))
        assertEquals(listOf(vns.path), AddedGames.scan(app).map { it.folder.path })
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aGameWithBuildFoldersOnGameStorageStaysOneGame() {
        val storage = tmp.newFolder("PC Games")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        SessionPrefs.setGameStorage(app, storage.path, "SD card")
        exe(File(storage, "Oddworld/x64/Oddworld.exe"))
        exe(File(storage, "Oddworld/x86/Oddworld.exe"))
        exe(File(storage, "Episodes/Episode1/Episode1.exe"))
        File(storage, "Episodes/Movies").mkdirs()
        assertEquals(listOf("Episodes", "Oddworld").map { File(storage, it).path }, AddedGames.scan(app).map { it.folder.path })
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aSiblingExeOfAGameOnGameStorageSwitchesThatGame() {
        val storage = tmp.newFolder("PC Games")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        SessionPrefs.setGameStorage(app, storage.path, "SD card")
        exe(File(storage, "Game/bin/Game.exe"))
        val pick = exe(File(storage, "Game/launcher/Launcher.exe"))
        val result = AddedGames.addExe(app, pick)
        assertTrue(result is AddedGames.AddResult.Switched)
        assertEquals(File(storage, "Game").path, (result as AddedGames.AddResult.Switched).game.folder.path)
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun aDeeperExeOfAGameOnGameStorageStillSwitchesThatGame() {
        val storage = tmp.newFolder("PC Games")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        SessionPrefs.setGameStorage(app, storage.path, "SD card")
        exe(File(storage, "Example/Example.exe"))
        val deep = exe(File(storage, "Example/bin/Example64.exe"))
        val result = AddedGames.addExe(app, deep)
        assertTrue(result is AddedGames.AddResult.Switched)
        assertEquals(File(storage, "Example").path, (result as AddedGames.AddResult.Switched).game.folder.path)
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
    }

    @Test fun anotherBuildOfTheSameExeInASiblingFolderSwitchesTheGame() {
        noGamesFolders()
        val held = onStorage("Games2/Oddworld/x64/Oddworld.exe")
        val other = onStorage("Games2/Oddworld/x86/Oddworld.exe")
        val folder = held.parentFile!!.parentFile!!
        AddedExes.put(app, AddedExes.Entry(folder.path, held.path, picked = false))
        val switched = AddedGames.addExe(app, other)
        assertTrue(switched is AddedGames.AddResult.Switched)
        assertEquals(folder.path, (switched as AddedGames.AddResult.Switched).game.folder.path)
        assertEquals(listOf(other.path), AddedGames.scan(app).map { it.exe.path })
    }

    @Test fun aDifferentGameWithANumberedNameIsNotTakenAsAnotherBuild() {
        noGamesFolders()
        val held = onStorage("Games2/id/Doom/Doom.exe")
        val pick = onStorage("Games2/id/Doom64/Doom64.exe")
        AddedExes.put(app, AddedExes.Entry(held.parentFile!!.parentFile!!.path, held.path, picked = false))
        assertTrue(AddedGames.addExe(app, pick) is AddedGames.AddResult.Added)
        assertEquals(2, AddedGames.scan(app).size)
    }

    @Test fun anExeTheSessionCannotSeeBindsItsFolderAndIsAdded() {
        noGamesFolders()
        val outside = exe(File(tmp.newFolder("card"), "Hades/Hades.exe"))
        assertNull(AddedGames.guestPath(app, outside))
        val game = (AddedGames.addExe(app, outside) as AddedGames.AddResult.Added).game
        assertEquals(listOf(outside.parent to "${AddedGames.GUEST_DIR}/Hades"), SessionPrefs.gameFolderBinds(app))
        assertEquals("${AddedGames.GUEST_DIR}/Hades/Hades.exe", game.guestExe)
        assertTrue(AddedGames.addExe(app, outside) is AddedGames.AddResult.Existing)
        assertEquals(1, SessionPrefs.gameFolderBinds(app).size)
    }

    @Test fun anotherExeOfAGameTheSessionCannotSeeBindsTheGameFolder() {
        noGamesFolders()
        val held = exe(File(tmp.newFolder("card"), "Hades/Hades.exe"))
        val pick = exe(File(held.parentFile, "x64/Hades.exe"))
        AddedExes.put(app, AddedExes.Entry(held.parent!!, held.path, picked = false))
        val switched = AddedGames.addExe(app, pick) as AddedGames.AddResult.Switched
        assertEquals(listOf(held.parent to "${AddedGames.GUEST_DIR}/Hades"), SessionPrefs.gameFolderBinds(app))
        assertEquals("${AddedGames.GUEST_DIR}/Hades/x64/Hades.exe", switched.game.guestExe)
    }

    @Test fun aGameFolderTheSessionAlreadySeesIsNotBoundAgain() {
        noGamesFolders()
        val held = onStorage("Games2/Hades/Hades.exe")
        onStorage("Games2/Hades/x64/Hades.exe")
        AddedExes.put(app, AddedExes.Entry(held.parent!!, held.path, picked = false))
        val alias = File(tmp.root, "alias").toPath()
        java.nio.file.Files.createSymbolicLink(alias, held.parentFile!!.parentFile!!.toPath())
        assertEquals(AddedGames.AddResult.Unreachable, AddedGames.addExe(app, File(alias.toFile(), "Hades/x64/Hades.exe")))
        assertEquals(0, SessionPrefs.gameFolderBinds(app).size)
    }

    @Test fun anExeInAFolderTheAppCannotReadIsNotAdded() {
        noGamesFolders()
        val outside = exe(File(tmp.newFolder("elsewhere"), "Game/Game.exe"))
        outside.parentFile!!.setReadable(false)
        try {
            assertEquals(AddedGames.AddResult.Unreachable, AddedGames.addExe(app, outside))
        } finally {
            outside.parentFile!!.setReadable(true)
        }
        assertEquals(0, AddedExes.list(app).size)
        assertEquals(0, SessionPrefs.gameFolderBinds(app).size)
    }

    @Test fun addingAFolderTheSessionCannotSeeBindsItAndAddsItsGames() {
        noGamesFolders()
        val games = File(tmp.newFolder("card"), "Winlator/Games")
        exe(File(games, "Alpha/Alpha.exe"))
        exe(File(games, "Beta/Beta.exe"))
        File(games, "steamapps").mkdirs()
        assertNull(AddedGames.guestPath(app, games))

        val result = AddedGames.addFolder(app, games)
        assertEquals(listOf("Alpha", "Beta"), result.added.map { it.name })
        assertEquals(listOf(games.path to "${AddedGames.GUEST_DIR}/Games"), SessionPrefs.gameFolderBinds(app))
        assertEquals("${AddedGames.GUEST_DIR}/Games/Alpha/Alpha.exe", result.added.first().guestExe)

        val again = AddedGames.addFolder(app, games)
        assertEquals(0, again.added.size)
        assertEquals(2, again.already)
        assertEquals(1, SessionPrefs.gameFolderBinds(app).size)
    }

    @Test fun aSecondFolderWithTheSameNameGetsItsOwnGuestPath() {
        noGamesFolders()
        val first = File(tmp.newFolder("a"), "Games").apply { exe(File(this, "One/One.exe")) }
        val second = File(tmp.newFolder("b"), "Games").apply { exe(File(this, "Two/Two.exe")) }
        AddedGames.addFolder(app, first)
        AddedGames.addFolder(app, second)
        val guests = SessionPrefs.gameFolderBinds(app).map { it.second }
        assertEquals(2, guests.toSet().size)
        assertEquals("${AddedGames.GUEST_DIR}/Games", guests.first())
    }

    @Test fun aGuestPathAlreadyTakenIsNeverReused() {
        noGamesFolders()
        val a = File(tmp.newFolder("a"), "Games").apply { mkdirs() }
        val b = File(tmp.newFolder("b"), "Games").apply { mkdirs() }
        val c = File(tmp.newFolder("c"), "Games").apply { exe(File(this, "Three/Three.exe")) }
        val clash = GamesFolderMigration.legacyRoots(listOf(a.path, b.path, c.path)).last().guest
        SessionPrefs.setGameFolderBinds(app, listOf(a.path to "${AddedGames.GUEST_DIR}/Games", b.path to clash))
        AddedGames.addFolder(app, c)
        val guests = SessionPrefs.gameFolderBinds(app).map { it.second }
        assertEquals(3, guests.size)
        assertEquals(3, guests.toSet().size)
        assertEquals("$clash-2", guests.last())
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
