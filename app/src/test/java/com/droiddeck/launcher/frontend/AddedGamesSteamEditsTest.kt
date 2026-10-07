package com.droiddeck.launcher.frontend

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
@Config(sdk = [28], manifest = Config.NONE)
class AddedGamesSteamEditsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var games: File
    private lateinit var folder: File
    private lateinit var main: File
    private lateinit var other: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        games = tmp.newFolder("PC")
        folder = File(games, "Example").apply { mkdirs() }
        main = File(folder, "Example.exe").apply { writeBytes(ByteArray(64)) }
        other = File(folder, "bin/Other.exe").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
        SessionPrefs.setGameStorage(context, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(context, listOf(games.path))
        File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/userdata/123/config").mkdirs()
    }

    private fun record(appId: Long, asked: String, steam: String, rev: Int) {
        val game = JSONObject()
            .put("app", JSONObject().put("Exe", "\"$asked\""))
            .put("steam", JSONObject().put("Exe", "\"$steam\""))
            .put("rev", rev)
        File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/userdata/123/config/.droiddeck-shortcuts.json")
            .writeText(JSONObject().put("games", JSONObject().put(appId.toString(), game)).toString())
    }

    private fun scanned() = AddedGames.scan(context).single { it.folder == folder }

    @Test fun aTargetChangedInSteamBecomesTheAppsChoiceOnce() {
        val first = scanned()
        assertEquals(main, first.exe)
        record(first.appId, "/root/Games/PC/Example/Example.exe", "/root/Games/PC/Example/bin/Other.exe", 1)

        val adopted = scanned()
        assertEquals(other, adopted.exe)
        assertEquals("/root/Games/PC/Example/bin/Other.exe", adopted.guestExe)
        assertEquals(first.appId, adopted.appId)
        assertEquals(other.path, SessionPrefs.addedGameExe(context, folder.path))
        assertEquals(1, SessionPrefs.addedGameExeSeen(context, folder.path))

        SessionPrefs.setAddedGameExe(context, folder.path, main.path)
        val chosen = scanned()
        assertEquals(main, chosen.exe)
        assertEquals(first.appId, chosen.appId)
        assertEquals(main.path, SessionPrefs.addedGameExe(context, folder.path))
    }

    @Test fun theListingTellsTheSessionWhichSteamEditTheAppHasTaken() {
        val first = scanned()
        record(first.appId, "/root/Games/PC/Example/Example.exe", "/root/Games/PC/Example/bin/Other.exe", 3)
        val listing = JSONObject(AddedGames.writeListing(context, listOf(scanned())).readText().removePrefix("[").removeSuffix("]"))
        assertEquals("/root/Games/PC/Example/bin/Other.exe", listing.getString("exe"))
        assertEquals("/root/Games/PC/Example/bin", listing.getString("dir"))
        assertEquals(3, listing.getInt("seen"))
        assertEquals(first.appId, listing.getLong("appid"))
    }

    @Test fun theAppidStaysWithTheFolderWhenItsExeChanges() {
        val first = scanned()
        SessionPrefs.setAddedGameExe(context, folder.path, other.path)
        val changed = scanned()
        assertEquals(other, changed.exe)
        assertEquals(first.appId, changed.appId)
        assertEquals(first.gameId, changed.gameId)
    }

    @Test fun aSteamTargetOutsideTheFolderIsNotTaken() {
        val outside = File(games, "Elsewhere/Game.exe").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(8)) }
        val first = scanned()
        record(first.appId, "/root/Games/PC/Example/Example.exe", "/root/Games/PC/Example/../Elsewhere/Game.exe", 1)
        assertEquals(main, scanned().exe)
        assertEquals("", SessionPrefs.addedGameExe(context, folder.path))
        assertEquals(0, SessionPrefs.addedGameExeSeen(context, folder.path))
        assertTrue(outside.isFile)
    }

    @Test fun steamTargetNeedsANewEditThatStartsFromTheAppsExe() {
        val guest = "/root/Games/PC/Example"
        fun game(asked: String, steam: String, rev: Int) = JSONObject()
            .put("app", JSONObject().put("Exe", "\"$guest/$asked\""))
            .put("steam", JSONObject().put("Exe", "\"$guest/$steam\""))
            .put("rev", rev)
        assertEquals(other, AddedGames.steamTarget(folder, guest, game("Example.exe", "bin/Other.exe", 2), "$guest/Example.exe", 1))
        assertNull(AddedGames.steamTarget(folder, guest, game("Example.exe", "bin/Other.exe", 2), "$guest/Example.exe", 2))
        assertNull(AddedGames.steamTarget(folder, guest, game("Example.exe", "bin/Other.exe", 2), "$guest/bin/Other.exe", 0))
        assertNull(AddedGames.steamTarget(folder, guest, game("Example.exe", "Example.exe", 2), "$guest/Example.exe", 0))
        assertNull(AddedGames.steamTarget(folder, guest, game("Example.exe", "Missing.exe", 2), "$guest/Example.exe", 0))
        assertNull(AddedGames.steamTarget(folder, guest, null, "$guest/Example.exe", 0))
    }
}
