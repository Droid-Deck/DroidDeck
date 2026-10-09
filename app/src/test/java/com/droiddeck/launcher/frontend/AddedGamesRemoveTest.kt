package com.droiddeck.launcher.frontend

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

/** Removing an added game stops listing it and keeps it off after a rescan, until it is added again; nothing on disk goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGamesRemoveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @org.junit.Before fun publishAtOnce() { AddedGamesPublisher.now = true }

    private fun gamesFolder(vararg names: String): File {
        val games = tmp.newFolder("PC")
        for (n in names) File(games, "$n/$n.exe").apply { parentFile!!.mkdirs() }.writeText("game")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        return games
    }

    @Test fun theExclusionListAddsRemovesAndKeepsOrder() {
        assertEquals(emptyList<String>(), SessionPrefs.removedAddedGames(app))
        SessionPrefs.setAddedGameRemoved(app, "/a/One", true)
        SessionPrefs.setAddedGameRemoved(app, "/a/Two", true)
        SessionPrefs.setAddedGameRemoved(app, "/a/One", true)
        assertEquals(listOf("/a/Two", "/a/One"), SessionPrefs.removedAddedGames(app))
        SessionPrefs.setAddedGameRemoved(app, "/a/Two", false)
        assertEquals(listOf("/a/One"), SessionPrefs.removedAddedGames(app))
        SessionPrefs.setAddedGameRemoved(app, "/a/Missing", false)
        assertEquals(listOf("/a/One"), SessionPrefs.removedAddedGames(app))
    }

    @Test fun aScanSkipsRemovedFoldersUntilRestored() {
        val games = gamesFolder("Alpha", "Beta", "Gamma")
        assertEquals(listOf("Alpha", "Beta", "Gamma"), AddedGames.scan(app).map { it.name })

        SessionPrefs.setAddedGameRemoved(app, File(games, "Beta").path, true)
        assertEquals(listOf("Alpha", "Gamma"), AddedGames.scan(app).map { it.name })
        // A rescan does not bring it back.
        assertEquals(listOf("Alpha", "Gamma"), AddedGames.scan(app).map { it.name })

        SessionPrefs.setAddedGameRemoved(app, File(games, "Beta").path, false)
        assertEquals(listOf("Alpha", "Beta", "Gamma"), AddedGames.scan(app).map { it.name })
    }

    @Test fun removeDropsItFromTheListingAndLeavesTheFolder() {
        val games = gamesFolder("Alpha", "Beta")
        val beta = File(games, "Beta")
        AddedGames.remove(app, beta)

        assertEquals(listOf(beta.path), SessionPrefs.removedAddedGames(app))
        assertEquals(listOf("Alpha"), AddedGames.scan(app).map { it.name })
        val listing = File(app.filesDir, "session/added-games.json").readText()
        assertTrue(listing.contains("\"Alpha\""))
        assertTrue(!listing.contains("\"Beta\""))
        assertTrue(File(beta, "Beta.exe").isFile)

        // Added again with the +: it comes back.
        assertTrue(AddedGames.addExe(app, File(beta, "Beta.exe")) is AddedGames.AddResult.Switched)
        assertEquals(emptyList<String>(), SessionPrefs.removedAddedGames(app))
        assertTrue(File(app.filesDir, "session/added-games.json").readText().contains("\"Beta\""))
    }
}
