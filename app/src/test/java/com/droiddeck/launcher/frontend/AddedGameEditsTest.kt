package com.droiddeck.launcher.frontend

import android.os.Environment
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File

/** "Add all games in this folder", the editor's writes to the shortcut listing, art choices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGameEditsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun exe(rel: String, size: Int = 16): File =
        File(Environment.getExternalStorageDirectory(), rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }

    private fun listing(): List<JSONObject> = JSONArray(File(app.filesDir, "session/added-games.json").readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Before fun noGamesFolders() {
        AddedGamesPublisher.now = true
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
    }

    @Test fun addingAFolderAddsEachGameWithItsBestExeAndSkipsThoseListed() {
        exe("Games/Alpha/Alpha.exe")
        exe("Games/Alpha/UnityCrashHandler64.exe")
        exe("Games/Beta/x86/Run.exe")
        exe("Games/Beta/x64/Thing.exe")
        File(Environment.getExternalStorageDirectory(), "Games/Manual").mkdirs()
        val listed = exe("Games/Celeste/Celeste.exe")
        assertTrue(AddedGames.addExe(app, listed) is AddedGames.AddResult.Added)

        val dir = File(Environment.getExternalStorageDirectory(), "Games")
        val result = AddedGames.addFolder(app, dir)
        assertEquals(listOf("Alpha", "Beta"), result.added.map { it.name })
        assertEquals(1, result.already)
        val alpha = result.added.first { it.name == "Alpha" }
        assertEquals("Alpha.exe", alpha.exe.name)
        assertFalse(alpha.exeUncertain)
        // Two lookalikes: the best guess, marked.
        assertTrue(result.added.first { it.name == "Beta" }.exeUncertain)
        // Entries, not a Games folder.
        assertEquals(emptyList<String>(), SessionPrefs.addedGamesDirs(app))
        assertEquals(3, AddedExes.list(app).size)
        assertTrue(listing().map { it.getString("name") }.containsAll(listOf("Alpha", "Beta", "Celeste")))

        // Again: nothing new, all three there already.
        val again = AddedGames.addFolder(app, dir)
        assertEquals(emptyList<String>(), again.added.map { it.name })
        assertEquals(3, again.already)
    }

    @Test fun editsReachTheShortcutListingOnTheSameAppid() {
        val pick = exe("Games/Hades/Hades.exe")
        val other = exe("Games/Hades/x64/Hades.exe")
        val game = (AddedGames.addExe(app, pick) as AddedGames.AddResult.Added).game
        val folder = game.folder.path

        AddedGameEdits.setName(app, folder, "Hades: Deluxe")
        AddedGameEdits.setLaunchOptions(app, folder, "-dx11")
        assertTrue(AddedGameEdits.setStartIn(app, folder, File(game.folder, "x64").path))
        assertTrue(AddedGameEdits.setExe(app, folder, other))

        val entry = listing().single()
        assertEquals("Hades: Deluxe", entry.getString("name"))
        assertEquals("-dx11", entry.getString("launch"))
        assertEquals("/root/Storage/Games/Hades/x64/Hades.exe", entry.getString("exe"))
        assertEquals("/root/Storage/Games/Hades/x64", entry.getString("dir"))
        assertEquals(game.appId, entry.getLong("appid"))
        assertEquals(game.appId, AddedGameEdits.game(app, folder)!!.appId)

        // Back to automatic.
        AddedGameEdits.setName(app, folder, "")
        assertTrue(AddedGameEdits.setStartIn(app, folder, ""))
        val auto = listing().single()
        assertEquals("Hades", auto.getString("name"))
        assertEquals("/root/Storage/Games/Hades/x64", auto.getString("dir"))
        assertEquals(game.appId, auto.getLong("appid"))
    }

    @Test fun aStartInTheSessionCannotSeeIsRefused() {
        val game = (AddedGames.addExe(app, exe("Games/Tunic/Tunic.exe")) as AddedGames.AddResult.Added).game
        assertFalse(AddedGameEdits.setStartIn(app, game.folder.path, tmp.newFolder("elsewhere").path))
        assertEquals("", SessionPrefs.addedGameStartIn(app, game.folder.path))
    }

    @Test fun editsMadeInSteamAreTakenOnceAndAnAppEditAfterThemStays() {
        val folder = File("/x/Game")
        val record = JSONObject()
            .put("app", JSONObject().put("AppName", "Game").put("StartDir", "\"/g/Game\"").put("LaunchOptions", "").put("Exe", "\"/g/Game/Game.exe\""))
            .put("steam", JSONObject().put("AppName", "Game GOTY").put("StartDir", "\"/g/Game/data\"").put("LaunchOptions", "-safe").put("Exe", "\"/g/Game/Game.exe\""))
        AddedGames.adoptSteamEdits(app, folder, record)
        assertEquals("Game GOTY", SessionPrefs.addedGameName(app, folder.path))
        assertEquals("/g/Game/data", SessionPrefs.addedGameStartIn(app, folder.path))
        assertEquals("-safe", SessionPrefs.addedGameLaunch(app, folder.path))

        // The user renames it in the app before Steam's shortcut is written again: the same Steam
        // value is not taken a second time.
        SessionPrefs.setAddedGameName(app, folder.path, "Mine")
        AddedGames.adoptSteamEdits(app, folder, record)
        assertEquals("Mine", SessionPrefs.addedGameName(app, folder.path))
    }

    @Test fun aStartInSteamMovedWithTheExeIsAutomatic() {
        val folder = File("/x/Other")
        val record = JSONObject()
            .put("app", JSONObject().put("StartDir", "\"/g/Other\"").put("Exe", "\"/g/Other/Other.exe\""))
            .put("steam", JSONObject().put("StartDir", "\"/g/Other/bin\"").put("Exe", "\"/g/Other/bin/Other.exe\""))
        AddedGames.adoptSteamEdits(app, folder, record)
        assertEquals("", SessionPrefs.addedGameStartIn(app, folder.path))
    }

    @Test fun anArtChoiceIsKeptAndResetGoesBackToAutomatic() {
        val game = (AddedGames.addExe(app, exe("Games/Celeste/Celeste.exe")) as AddedGames.AddResult.Added).game
        val image = File(game.folder, "cover-alt.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val option = AddedGameArt.folderImages(game.folder).map { AddedGameArt.Option(AddedGameArt.FOLDER, it.path, it.path) }.single()
        assertEquals(image.path, option.full)

        assertTrue(AddedGameEdits.setArt(app, game.folder.path, AddedGameArt.Slot.COVER, option))
        val cover = AddedGameArt.describe(app, game).first { it.slot == AddedGameArt.Slot.COVER }
        assertTrue(cover.chosen)
        assertEquals(AddedGameArt.FOLDER, cover.source)
        assertEquals(AddedGameArt.FOLDER to image.path, AddedGameArt.chosenSource(app, game, AddedGameArt.Slot.COVER))
        assertEquals(cover.file, AddedGameArt.resolve(app, game).portrait)
        assertEquals(cover.file!!.absolutePath, listing().single().getJSONObject("art").getString("p"))

        AddedGameEdits.resetArt(app, game.folder.path, AddedGameArt.Slot.COVER)
        val auto = AddedGameArt.describe(app, game).first { it.slot == AddedGameArt.Slot.COVER }
        assertFalse(auto.chosen)
        assertNull(AddedGameArt.resolve(app, game).portrait)
        assertEquals(AddedGameArt.AUTO, auto.source)
    }
}
