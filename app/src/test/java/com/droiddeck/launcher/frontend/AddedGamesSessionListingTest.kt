package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AddedGamesSessionListingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @After fun restore() { AddedGamesPublisher.now = false }

    private val stale = """[{"name":"Old","exe":"/root/Games/Old/Old.exe","folder":"/root/Games/Old","dir":"/root/Games/Old","appid":2147483649,"seen":0}]"""

    private fun gamesFolder(vararg names: String) {
        val games = tmp.newFolder("PC")
        for (n in names) File(games, "$n/$n.exe").apply { parentFile!!.mkdirs() }.writeText("game")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
    }

    private fun scanLock(): Any =
        AddedGames::class.java.getDeclaredField("scanning").apply { isAccessible = true }.get(null)!!

    private fun refreshes() = Thread.getAllStackTraces().keys.filter { it.name == "added-games-refresh" }

    @Test fun aSessionStartsFromTheLastListingWhileTheLibraryScanHoldsTheLock() {
        gamesFolder("Alpha")
        val listing = AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText(stale) }
        val done = CountDownLatch(1)
        var returned: File? = null
        var seen = ""
        synchronized(scanLock()) {
            Thread {
                returned = AddedGamesPublisher.sessionListing(app)
                seen = listing.readText()
                done.countDown()
            }.start()
            assertTrue("the session start waited for the library scan", done.await(5, TimeUnit.SECONDS))
        }
        assertEquals(listing.path, returned!!.path)
        assertEquals(stale, seen)
        refreshes().forEach { it.join(30_000) }
        assertTrue(refreshes().isEmpty())
        val fresh = listing.readText()
        assertTrue(fresh.contains("\"Alpha\""))
        assertFalse(fresh.contains("\"Old\""))
        assertFalse(File(listing.parentFile, listing.name + ".part").exists())
    }

    @Test fun aFirstSessionScansBeforeStarting() {
        gamesFolder("Alpha")
        AddedGamesPublisher.now = false
        val listing = AddedGames.listingFile(app)
        listing.delete()
        assertEquals(listing.path, AddedGamesPublisher.sessionListing(app).path)
        assertTrue(listing.readText().contains("\"Alpha\""))
        assertTrue(refreshes().isEmpty())
        assertFalse(File(listing.parentFile, listing.name + ".part").exists())
    }

    @Test fun anUnreadableListingIsRescannedBeforeStarting() {
        gamesFolder("Alpha")
        AddedGamesPublisher.now = false
        val listing = AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText("[{\"name\":") }
        AddedGamesPublisher.sessionListing(app)
        assertTrue(listing.readText().contains("\"Alpha\""))
        assertTrue(refreshes().isEmpty())
    }
}
