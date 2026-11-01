package com.droiddeck.launcher.session

import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
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
class SteamLibrariesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun added(path: String, label: String = File(path).name): SteamLibraries.Entry =
        (SteamLibraries.add(app, path, label) as SteamLibraries.AddResult.Added).entry

    @Test fun addedLibrariesGetStableMountsThatSurviveRenameAndRemoval() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val docs = added(tmp.newFolder("Documents").path, "Docs")
        val usb = added(tmp.newFolder("USB").path)
        assertEquals(listOf("/mnt/droiddeck-lib/1", "/mnt/droiddeck-lib/2"), SteamLibraries.list(app).map { it.guest })
        assertTrue(File(docs.path, "steamapps").isDirectory)

        SteamLibraries.rename(app, docs.id, "My \"Docs\"")
        assertEquals("My Docs", SteamLibraries.list(app).first().label)
        assertEquals("/mnt/droiddeck-lib/1", SteamLibraries.list(app).first().guest)

        SteamLibraries.remove(app, docs.id)
        assertEquals(listOf(usb.id), SteamLibraries.list(app).map { it.id })
        assertTrue(File(docs.path, "steamapps").isDirectory)
        assertEquals("3", added(tmp.newFolder("Again").path).id)
    }

    @Test fun overlappingFoldersAndAFifthLibraryAreRefused() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val games = tmp.newFolder("Games")
        added(games.path)
        assertTrue(SteamLibraries.add(app, games.path) is SteamLibraries.AddResult.Refused)
        assertTrue(SteamLibraries.add(app, File(games, "inner").apply { mkdirs() }.path) is SteamLibraries.AddResult.Refused)
        repeat(SteamLibraries.MAX - 1) { added(tmp.newFolder("L$it").path) }
        assertTrue(SteamLibraries.add(app, tmp.newFolder("Full").path) is SteamLibraries.AddResult.Refused)
    }

    @Test fun addedLibrariesAreListedMappedAndKeepGamesFoldersFromDoubling() {
        val pc = tmp.newFolder("PC")
        File(pc, "steamapps").mkdirs()
        File(pc, "steamapps/appmanifest_220.acf").writeText("x")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        val lib = added(pc.path, "PC")
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
        assertEquals(1, Library.steamLibraries(app, "arm64").count { it.first == pc })
        assertTrue(Library.steamLibraries(app, "x86_64").any { it.first == File(pc, GameStorage.X64_CARD_DIR) })
        assertEquals(File(pc, "steamapps/common"), AddedGames.hostPath(app, "${lib.guest}/steamapps/common"))
        val docs = added(tmp.newFolder("Documents").path)
        assertEquals("${docs.guest}/steamapps", AddedGames.guestPath(app, File(docs.path, "steamapps")))
        val nested = File(pc, "Other").apply { mkdirs() }
        SteamLibraries.remove(app, lib.id)
        assertTrue(SteamLibraries.add(app, nested.path) is SteamLibraries.AddResult.Refused)
    }

    @Test fun unreadableOrInvalidEntriesAreDropped() {
        assertTrue(SteamLibraries.parse("{oops").isEmpty())
        assertEquals(listOf("2"), SteamLibraries.parse("""[{"id":"../x","path":"/a"},{"id":"2","path":"/b","label":""}]""").map { it.id })
        assertEquals("b", SteamLibraries.parse("""[{"id":"2","path":"/b","label":""}]""").single().label)
    }
}
