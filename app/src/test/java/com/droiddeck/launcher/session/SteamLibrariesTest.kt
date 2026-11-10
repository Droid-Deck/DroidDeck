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

    @Test fun aGamesFolderIsOfferedWholeUnlessItIsAlreadyALibrary() {
        val games = "/storage/card/GameNative"
        assertTrue(SteamLibraries.offered(emptyList(), listOf(games), games))
        assertFalse(SteamLibraries.offered(emptyList(), listOf(games), "$games/Steam"))
        assertFalse(SteamLibraries.offered(emptyList(), listOf(games), "/storage/card"))
        assertFalse(SteamLibraries.offered(listOf(games), listOf(games), games))
        assertTrue(SteamLibraries.offered(listOf("/storage/card/Other"), listOf(games), "/storage/card/New"))
    }

    @Test fun aGamesFolderRemainsDiscoverableBesideChosenGameStorage() {
        val card = tmp.newFolder("card")
        val games = File(card, "GameNative").path
        val selected = File(card, "Selected").apply { mkdirs() }
        SessionPrefs.setGameStorage(app, selected.path, "Selected")
        val effective = GameStorage.effective(app)!!
        assertTrue(SteamLibraries.offered(listOf(effective.path), listOf(games), games))
        SessionPrefs.setGameStorage(app, card.path, "Card")
        assertFalse(SteamLibraries.offered(listOf(GameStorage.effective(app)!!.path), listOf(games), games))
    }

    private fun library(dir: File): File = dir.apply { File(this, "steamapps").mkdirs(); File(this, "steamapps/appmanifest_220.acf").writeText("x") }

    @Test fun pickingTheFolderAboveALibraryAddsTheLibraryInside() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val app2 = tmp.newFolder("OtherApp")
        library(File(app2, "Steam"))
        val entry = (SteamLibraries.add(app, app2.path) as SteamLibraries.AddResult.Added).entry
        assertEquals(File(app2, "Steam").path, entry.path)
        assertEquals("OtherApp", entry.label)
        val empty = tmp.newFolder("Empty")
        assertEquals(empty.path, (SteamLibraries.add(app, empty.path) as SteamLibraries.AddResult.Added).entry.path)
    }

    @Test fun scanLooksTwoLevelsDownAndSkipsHiddenAndAppFolders() {
        val volume = tmp.newFolder("volume")
        val shallow = library(File(volume, "SteamLibrary"))
        val deep = library(File(volume, "Some App/Steam"))
        library(File(volume, "Android/data/x"))
        library(File(volume, ".hidden/Steam"))
        library(File(volume, "a/b/c"))
        val withGames = SteamLibraries.candidates(volume, 2).filter { SteamLibraries.gameCount(it) > 0 }
        assertEquals(setOf(shallow, deep), withGames.toSet())
    }

    private fun depotInstall(common: File, name: String, complete: Boolean = true): File = File(common, name).apply {
        File(this, ".DepotDownloader").mkdirs()
        File(this, ".DepotDownloader/depot.config").writeText("""{"installedManifestIDs":{"43":99}}""")
        File(this, "$name.exe").writeBytes(ByteArray(4))
        if (complete) File(this, ".download_complete").writeText("")
    }

    @Test fun finishedDownloadsWithoutManifestsCountAsGames() {
        val steam = File(tmp.newFolder("card"), "Other/Steam")
        val common = File(steam, "steamapps/common").apply { mkdirs() }
        assertEquals(0, SteamLibraries.gameCount(steam))
        val done = depotInstall(common, "Done")
        depotInstall(common, "Partial", complete = false)
        depotInstall(common, "Managed")
        File(steam, "steamapps/appmanifest_42.acf").writeText("\"AppState\"\n{\n\t\"installdir\"\t\t\"Managed\"\n}\n")
        val migrated = File(common, "Migrated").apply {
            File(this, ".DepotDownloader/completed").mkdirs()
            File(this, ".DepotDownloader/completed/43_99.manifest").writeBytes(ByteArray(4))
            File(this, ".download_complete").writeText("")
        }
        assertEquals(listOf(done, migrated), SteamLibraries.depotInstalls(common))
        assertEquals(3, SteamLibraries.gameCount(steam))
        assertEquals(steam, SteamLibraries.innerLibrary(steam.parentFile))
    }

    @Test fun addedLibrariesImportFinishedDownloadsWithoutManifests() {
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        val steam = tmp.newFolder("Steam")
        val game = depotInstall(File(steam, "steamapps/common"), "Game")
        val lib = added(steam.path)
        val scanned = AddedGames.scan(app).single()
        assertEquals(game, scanned.folder)
        assertEquals("${lib.guest}/steamapps/common/Game", AddedGames.guestPath(app, scanned.folder))
    }

    @Test fun unreadableOrInvalidEntriesAreDropped() {
        assertTrue(SteamLibraries.parse("{oops").isEmpty())
        assertEquals(listOf("2"), SteamLibraries.parse("""[{"id":"../x","path":"/a"},{"id":"2","path":"/b","label":""}]""").map { it.id })
        assertEquals("b", SteamLibraries.parse("""[{"id":"2","path":"/b","label":""}]""").single().label)
    }
}
