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

/** Games folders from earlier builds become entries, every game keeping what it had. */
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
        // What earlier scans had kept, by folder.
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
        // The same path inside the session as before: the shortcut keeps its target.
        assertEquals(AddedGames.GUEST_DIR + "/PC/Alpha/Alpha.exe", games.getValue(alpha.path).guestExe)
        assertEquals(AddedGames.GUEST_DIR + "/PC/Beta/bin/Beta64.exe", games.getValue(beta.path).guestExe)
        assertTrue(SessionPrefs.removedAddedGames(app).contains(gone.path))
        // Not a library any more.
        assertTrue(GameStorage.gamesFolderLibraries(app).isEmpty())
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
