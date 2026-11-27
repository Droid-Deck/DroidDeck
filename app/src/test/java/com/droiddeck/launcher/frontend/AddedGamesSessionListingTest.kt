package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.stores.SteamLiveShortcuts
import java.io.File
import java.util.Collections
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

    @After fun restore() {
        AddedGamesPublisher.now = false
        AddedGamesPublisher.live = SteamLiveShortcuts::sync
        SessionState.running = false
        SessionState.stopRequested = false
        SessionState.sessionId = null
        SessionState.phase = SessionPhase.IDLE
    }

    private val stale = """[{"name":"Old","exe":"/root/Games/Old/Old.exe","folder":"/root/Games/Old","dir":"/root/Games/Old","appid":2147483649,"seen":0}]"""

    private fun gamesFolder(vararg names: String): File {
        val games = tmp.newFolder("PC")
        for (n in names) File(games, "$n/$n.exe").apply { parentFile!!.mkdirs() }.writeText("game")
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, listOf(games.path))
        return games
    }

    private fun session(phase: SessionPhase) {
        SessionState.sessionId = "session-test"
        SessionState.stopRequested = false
        SessionState.phase = phase
        SessionState.running = true
    }

    private fun until(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 30_000
        while (!check()) {
            assertTrue(what, System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
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

    @Test fun aListingThatCannotBeReplacedKeepsTheOldOne() {
        val listing = AddedGames.listingFile(app).apply { parentFile?.mkdirs() }
        listing.writeText("[]")
        val blocked = File(listing.parentFile, listing.name + ".part").apply { mkdirs() }
        gamesFolder("Alpha")
        AddedGames.writeListing(app, AddedGames.scan(app))
        assertEquals("[]", listing.readText())
        assertFalse(blocked.exists())
    }

    @Test fun anUnreadableListingIsRescannedBeforeStarting() {
        gamesFolder("Alpha")
        AddedGamesPublisher.now = false
        val listing = AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText("[{\"name\":") }
        AddedGamesPublisher.sessionListing(app)
        assertTrue(listing.readText().contains("\"Alpha\""))
        assertTrue(refreshes().isEmpty())
    }

    @Test fun aGameRemovedWhileTheClientStartsIsNotAddedToIt() {
        val games = gamesFolder("Alpha", "Beta")
        val listing = AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText(stale) }
        val delivered = Collections.synchronizedList(ArrayList<String>())
        AddedGamesPublisher.live = { _, found, include -> delivered.addAll(found.filter(include).map { it.name }); true }
        session(SessionPhase.STARTING_STEAM)
        AddedGamesPublisher.sessionListing(app)
        until("the refresh did not write the listing") { listing.readText().contains("\"Beta\"") }
        SessionPrefs.setAddedGameRemoved(app, File(games, "Beta").path, true)
        SessionState.phase = SessionPhase.READY
        refreshes().forEach { it.join(30_000) }
        assertTrue(refreshes().isEmpty())
        assertEquals(listOf("Alpha"), delivered.toList())
    }

    @Test fun aDeliveryTheClientRefusesIsRetriedUntilItIsTaken() {
        gamesFolder("Alpha")
        AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText(stale) }
        val attempts = Collections.synchronizedList(ArrayList<List<String>>())
        AddedGamesPublisher.live = { _, found, include -> attempts.add(found.filter(include).map { it.name }); attempts.size > 1 }
        session(SessionPhase.READY)
        AddedGamesPublisher.sessionListing(app)
        refreshes().forEach { it.join(30_000) }
        assertTrue(refreshes().isEmpty())
        assertEquals(listOf(listOf("Alpha"), listOf("Alpha")), attempts.toList())
    }

    @Test fun anEndedSessionStopsTheRetries() {
        gamesFolder("Alpha")
        AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText(stale) }
        val attempts = Collections.synchronizedList(ArrayList<String>())
        AddedGamesPublisher.live = { _, found, _ -> attempts.addAll(found.map { it.name }); SessionState.running = false; false }
        session(SessionPhase.READY)
        AddedGamesPublisher.sessionListing(app)
        refreshes().forEach { it.join(30_000) }
        assertTrue(refreshes().isEmpty())
        assertEquals(listOf("Alpha"), attempts.toList())
    }

    @Test fun aDeliveryQueuedForAnEndedSessionIsNotSentToTheNextOne() {
        gamesFolder("Alpha")
        AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText(stale) }
        val delivered = Collections.synchronizedList(ArrayList<String>())
        AddedGamesPublisher.live = { _, found, include -> delivered.addAll(found.filter(include).map { it.name }); true }
        session(SessionPhase.READY)
        val first = CountDownLatch(1)
        val firstHeld = CountDownLatch(1)
        val second = CountDownLatch(1)
        val secondHeld = CountDownLatch(1)
        AddedGamesPublisher.later(app) { firstHeld.countDown(); first.await() }
        try {
            assertTrue(firstHeld.await(30, TimeUnit.SECONDS))
            AddedGamesPublisher.sessionListing(app)
            val refresh = refreshes().single()
            until("the refresh did not wait for its scan") { refresh.state == Thread.State.WAITING }
            AddedGamesPublisher.later(app) { secondHeld.countDown(); second.await() }
            first.countDown()
            assertTrue(secondHeld.await(30, TimeUnit.SECONDS))
            until("the refresh did not queue its delivery") {
                refresh.state == Thread.State.WAITING && refresh.stackTrace.any { it.methodName == "deliver" }
            }
            SessionState.sessionId = "session-next"
        } finally {
            first.countDown()
            second.countDown()
        }
        refreshes().forEach { it.join(30_000) }
        assertTrue(refreshes().isEmpty())
        assertTrue(delivered.isEmpty())
    }

    @Test fun theRefreshWaitsForEarlierPublicationsBeforeWriting() {
        gamesFolder("Alpha")
        val listing = AddedGames.listingFile(app).apply { parentFile!!.mkdirs(); writeText(stale) }
        val hold = CountDownLatch(1)
        val holding = CountDownLatch(1)
        AddedGamesPublisher.later(app) { holding.countDown(); hold.await() }
        try {
            assertTrue(holding.await(30, TimeUnit.SECONDS))
            AddedGamesPublisher.sessionListing(app)
            val refresh = refreshes().single()
            until("the refresh neither finished nor waited") { refresh.state == Thread.State.WAITING || !refresh.isAlive }
            assertTrue("the refresh wrote while an earlier publication was running", refresh.isAlive)
            assertEquals(stale, listing.readText())
        } finally {
            hold.countDown()
        }
        refreshes().forEach { it.join(30_000) }
        assertTrue(refreshes().isEmpty())
        assertTrue(listing.readText().contains("\"Alpha\""))
    }
}
