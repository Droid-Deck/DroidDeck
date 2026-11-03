package com.droiddeck.launcher.frontend

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGamesCacheTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun game(name: String = "Example"): Pair<File, File> {
        val folder = tmp.newFolder(name)
        val exe = File(folder, "bin/$name.exe").apply { parentFile!!.mkdirs(); writeText("game") }
        return folder to exe
    }

    private fun store() = File(tmp.root, "scan.json")

    @Test fun aRankingIsReusedAfterARestartWhileItsFolderIsUnchanged() {
        val (folder, exe) = game()
        AddedGamesCache(store()).apply { keepRank(folder, GameExePicker.Ranking(listOf(exe), listOf(72), uncertain = true)); save() }
        val kept = AddedGamesCache(store()).rank(folder)
        assertEquals(listOf(exe), kept?.exes)
        assertEquals(listOf(72), kept?.scores)
        assertEquals(true, kept?.uncertain)
    }

    @Test fun aChangedFolderOrAMissingExecutableIsWalkedAgain() {
        val (folder, exe) = game()
        AddedGamesCache(store()).apply { keepRank(folder, GameExePicker.Ranking(listOf(exe), listOf(72), false)); save() }
        val modified = folder.lastModified()
        folder.setLastModified(modified + 60_000)
        assertNull(AddedGamesCache(store()).rank(folder))
        folder.setLastModified(modified)
        assertNotNull(AddedGamesCache(store()).rank(folder))
        exe.delete()
        assertNull(AddedGamesCache(store()).rank(folder))
    }

    @Test fun aRankingOlderThanAWeekIsWalkedAgain() {
        val (folder, exe) = game()
        val walked = System.currentTimeMillis() - AddedGamesCache.KEEP_MS - 1
        store().writeText(JSONObject().put("ranks", JSONObject().put(folder.path, JSONObject()
            .put("modified", folder.lastModified()).put("at", walked)
            .put("exes", JSONArray(listOf(exe.path))).put("scores", JSONArray(listOf(72))))).toString())
        assertNull(AddedGamesCache(store()).rank(folder))
    }

    @Test fun aFolderWithNoExecutableIsNotKept() {
        val folder = tmp.newFolder("Empty")
        AddedGamesCache(store()).apply { keepRank(folder, GameExePicker.Ranking(emptyList(), emptyList(), false)); save() }
        assertFalse(store().exists())
        assertNull(AddedGamesCache(store()).rank(folder))
    }

    @Test fun anIdentifiedNameIsReusedAfterARestart() {
        AddedGamesCache(store()).apply { keepName("named", "God of War"); keepName("unnamed", ""); save() }
        val kept = AddedGamesCache(store())
        assertEquals("God of War", kept.name("named"))
        assertEquals("", kept.name("unnamed"))
        assertNull(kept.name("unknown"))
    }

    @Test fun aScanKeepsEachGamesRankingForTheNextLaunch() {
        val (folder, exe) = game("Card Game")
        AddedExes.put(app, AddedExes.Entry(folder.path, exe.path, picked = false))
        AddedGames.scan(app)
        assertEquals(listOf(exe.path), AddedGamesCache(File(app.filesDir, AddedGamesCache.FILE)).rank(folder)?.exes?.map { it.path })
    }
}
