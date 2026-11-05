package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoreGameSidecar
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GamesFolderMigrationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun exe(root: File, rel: String) = File(root, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(16)) }

    @Before fun setUp() {
        SessionPrefs.setGameStorage(app, "", "")
        AddedGamesPublisher.now = true
    }

    @Test fun eachGameBecomesAnEntryKeepingItsExeAppidNameAndRemoval() {
        val pc = tmp.newFolder("PC")
        val alpha = File(pc, "Alpha"); exe(pc, "Alpha/Alpha.exe")
        val beta = File(pc, "Beta"); exe(pc, "Beta/Beta.exe"); val pick = exe(pc, "Beta/bin/Beta64.exe")
        val gone = File(pc, "Gone"); exe(pc, "Gone/Gone.exe")
        exe(pc, "steamapps/common/Installed/Installed.exe")
        val store = File(pc, "Store"); exe(pc, "Store/Store.exe")
        StoreGameSidecar.file(store).writeText("""{"store":"gog","id":"1","title":"Store Game","exe":"Store.exe"}""")
        File(pc, "Notes").mkdirs()
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        SessionPrefs.setAddedGameExe(app, beta.path, pick.path)
        SessionPrefs.addedGameAppId(app, alpha.path, 0x8123_4567L)
        SessionPrefs.addedGameAppId(app, beta.path, 0x8765_4321L)
        SessionPrefs.setAddedGameName(app, alpha.path, "Alpha GOTY")
        SessionPrefs.setAddedGameRemoved(app, gone.path, true)

        GamesFolderMigration.run(app)

        assertEquals(emptyList<String>(), SessionPrefs.addedGamesDirs(app))
        assertTrue(SessionPrefs.gamesFoldersMigrated(app))
        val entries = AddedExes.list(app).associateBy { it.folder }
        assertEquals(setOf(alpha.path, beta.path, gone.path), entries.keys)
        assertTrue(entries.getValue(beta.path).picked)
        assertEquals(pick.path, entries.getValue(beta.path).exe)
        assertFalse(entries.getValue(alpha.path).picked)

        val games = AddedGames.scan(app).associateBy { it.folder.path }
        assertEquals(setOf(alpha.path, beta.path), games.keys)
        assertEquals(0x8123_4567L, games.getValue(alpha.path).appId)
        assertEquals(0x8765_4321L, games.getValue(beta.path).appId)
        assertEquals("Alpha GOTY", games.getValue(alpha.path).name)
        assertEquals(pick.path, games.getValue(beta.path).exe.path)
        assertEquals(AddedGames.GUEST_DIR + "/PC/Alpha/Alpha.exe", games.getValue(alpha.path).guestExe)
        assertEquals(AddedGames.GUEST_DIR + "/PC/Beta/bin/Beta64.exe", games.getValue(beta.path).guestExe)
        assertTrue(SessionPrefs.removedAddedGames(app).contains(gone.path))
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
    }

    @Test fun aFolderOfGamesInAnOldGamesFolderBecomesOneEntryPerGame() {
        val pc = tmp.newFolder("PC")
        val rance = File(pc, "VNs/Rance 01"); exe(pc, "VNs/Rance 01/Rance01.exe")
        val albatross = File(pc, "VNs/Albatross"); exe(pc, "VNs/Albatross/Albatross Log.exe")
        File(pc, "VNs/steamapps").mkdirs()
        val solo = File(pc, "Solo"); exe(pc, "Solo/Solo.exe")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        GamesFolderMigration.run(app)
        assertEquals(setOf(albatross.path, rance.path, solo.path), AddedExes.list(app).map { it.folder }.toSet())
        assertEquals(setOf(albatross.path, rance.path, solo.path), AddedGames.scan(app).map { it.folder.path }.toSet())
    }

    @Test fun aRemovedOrAlreadyAddedFolderOfGamesKeepsItsState() {
        val pc = tmp.newFolder("PC")
        val gone = File(pc, "Gone"); exe(pc, "Gone/A/a.exe"); exe(pc, "Gone/B/b.exe")
        val kept = File(pc, "Kept"); val keptExe = exe(pc, "Kept/A/a.exe"); exe(pc, "Kept/B/b.exe")
        val store = File(pc, "Store"); exe(pc, "Store/Content/game.exe"); exe(pc, "Store/Tools/tool.exe")
        StoreGameSidecar.file(store).writeText("""{"store":"gog","id":"2","title":"Store Game","exe":"Content/game.exe"}""")
        val solo = File(pc, "Solo"); exe(pc, "Solo/Extras/viewer.exe"); exe(pc, "Solo/Content/solo.exe")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        SessionPrefs.setAddedGameRemoved(app, gone.path, true)
        AddedExes.put(app, AddedExes.Entry(kept.path, keptExe.path))
        GamesFolderMigration.run(app)
        val entries = AddedExes.list(app).map { it.folder }.toSet()
        assertEquals(setOf(File(gone, "A").path, File(gone, "B").path, kept.path, solo.path), entries)
        assertTrue(SessionPrefs.removedAddedGames(app).containsAll(listOf(File(gone, "A").path, File(gone, "B").path)))
        assertEquals(setOf(kept.path, solo.path), AddedGames.scan(app).map { it.folder.path }.toSet())
    }

    @Test fun aGameSelectedOutsideTheRankedFoldersStaysListed() {
        val pc = tmp.newFolder("PC")
        val vns = File(pc, "VNs")
        exe(pc, "VNs/A/a.exe"); exe(pc, "VNs/B/b.exe")
        val pick = exe(pc, "VNs/C/helper.exe")
        val loose = File(pc, "Loose"); exe(pc, "Loose/A/a.exe"); exe(pc, "Loose/B/b.exe")
        val loosePick = exe(pc, "Loose/setup.exe")
        val lib = File(pc, "Lib"); exe(pc, "Lib/A/a.exe"); exe(pc, "Lib/B/b.exe")
        val libPick = exe(pc, "Lib/steamapps/common/Tool/helper.exe")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        SessionPrefs.setAddedGameExe(app, vns.path, pick.path)
        SessionPrefs.setAddedGameExe(app, loose.path, loosePick.path)
        SessionPrefs.setAddedGameExe(app, lib.path, libPick.path)
        GamesFolderMigration.run(app)
        val entries = AddedExes.list(app).associateBy { it.folder }
        assertEquals(setOf(File(vns, "A").path, File(vns, "B").path, File(vns, "C").path, loose.path, lib.path), entries.keys)
        assertEquals(libPick.path, entries.getValue(lib.path).exe)
        assertEquals(pick.path, entries.getValue(File(vns, "C").path).exe)
        assertTrue(entries.getValue(File(vns, "C").path).picked)
        assertEquals(loosePick.path, entries.getValue(loose.path).exe)
        val games = AddedGames.scan(app).associateBy { it.folder.path }
        assertEquals(pick.path, games.getValue(File(vns, "C").path).exe.path)
        assertEquals(loosePick.path, games.getValue(loose.path).exe.path)
    }

    @Test fun aSplitFolderHandsItsSettingsToTheGameItLaunched() {
        val pc = tmp.newFolder("PC")
        val vns = File(pc, "VNs")
        exe(pc, "VNs/Albatross/Albatross.exe")
        val pick = exe(pc, "VNs/Rance 01/Rance01.exe")
        File(pc, "VNs/Notes").mkdirs()
        val plain = File(pc, "Plain"); exe(pc, "Plain/A/a.exe"); exe(pc, "Plain/B/b.exe")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        SessionPrefs.setAddedGameExe(app, vns.path, pick.path)
        SessionPrefs.addedGameAppId(app, vns.path, 0x8111_2222L)
        SessionPrefs.setAddedGameName(app, vns.path, "Rance")
        SessionPrefs.setAddedGameLaunch(app, vns.path, "-windowed")
        SessionPrefs.setAddedGameName(app, File(vns, "Rance 01").path, "Rance 01 Remake")
        SessionPrefs.addedGameAppId(app, plain.path, 0x8333_4444L)
        GamesFolderMigration.run(app)
        val rance = File(vns, "Rance 01").path
        val games = AddedGames.scan(app).associateBy { it.folder.path }
        assertEquals(0x8111_2222L, games.getValue(rance).appId)
        assertEquals("Rance 01 Remake", games.getValue(rance).name)
        assertEquals("-windowed", games.getValue(rance).launchOptions)
        assertEquals(pick.path, games.getValue(rance).exe.path)
        assertTrue(games.getValue(File(vns, "Albatross").path).appId != 0x8111_2222L)
        assertEquals("", SessionPrefs.addedGameLaunch(app, File(vns, "Albatross").path))
        assertEquals(0x8333_4444L, games.getValue(File(plain, "A").path).appId)
        assertTrue(games.getValue(File(plain, "B").path).appId != 0x8333_4444L)
    }

    @Test fun aSplitFolderKeepsTheExeItLaunchedWithoutAPick() {
        val pc = tmp.newFolder("PC")
        val vns = File(pc, "VNs")
        val launched = exe(pc, "VNs/Rance/VNsPlayer.exe"); exe(pc, "VNs/Rance/Rance.exe")
        val other = exe(pc, "VNs/Other/Other.exe")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        SessionPrefs.addedGameAppId(app, vns.path, 0x8555_6666L)
        GamesFolderMigration.run(app)
        val entries = AddedExes.list(app).associateBy { it.folder }
        assertEquals(launched.path, entries.getValue(File(vns, "Rance").path).exe)
        assertTrue(entries.getValue(File(vns, "Rance").path).picked)
        assertFalse(entries.getValue(File(vns, "Other").path).picked)
        val games = AddedGames.scan(app).associateBy { it.folder.path }
        assertEquals(launched.path, games.getValue(File(vns, "Rance").path).exe.path)
        assertEquals(0x8555_6666L, games.getValue(File(vns, "Rance").path).appId)
        assertEquals(other.path, games.getValue(File(vns, "Other").path).exe.path)
    }

    @Test fun runningAgainChangesNothing() {
        val pc = tmp.newFolder("PC")
        exe(pc, "Alpha/Alpha.exe")
        SessionPrefs.setAddedGamesDirs(app, listOf(pc.path))
        GamesFolderMigration.run(app)
        val entries = AddedExes.list(app).map { it.folder to it.exe }
        val binds = SessionPrefs.gameFolderBinds(app)
        val before = AddedGames.scan(app).map { it.appId to it.guestExe }
        GamesFolderMigration.run(app)
        GamesFolderMigration.run(app)
        assertEquals(entries, AddedExes.list(app).map { it.folder to it.exe })
        assertEquals(binds, SessionPrefs.gameFolderBinds(app))
        assertEquals(before, AddedGames.scan(app).map { it.appId to it.guestExe })
        assertEquals(listOf(pc.path to AddedGames.GUEST_DIR + "/PC"), binds)
    }

    @Test fun twoFoldersOfOneNameKeepTheirOwnGuestPaths() {
        val a = File(tmp.newFolder("a"), "Games").apply { mkdirs() }
        val b = File(tmp.newFolder("b"), "Games").apply { mkdirs() }
        exe(a, "One/One.exe")
        exe(b, "Two/Two.exe")
        SessionPrefs.setAddedGamesDirs(app, listOf(a.path, b.path))
        val guests = GamesFolderMigration.legacyRoots(listOf(a.path, b.path)).map { it.guest }
        val games = AddedGames.scan(app).associateBy { it.name }
        assertEquals(guests[0] + "/One/One.exe", games.getValue("One").guestExe)
        assertEquals(guests[1] + "/Two/Two.exe", games.getValue("Two").guestExe)
        assertTrue(guests[0] != guests[1])
    }
}
