package com.droiddeck.launcher.stores.xbox

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class XboxLaunchLeasesTest {
    private var time = 0L
    private val leases = XboxLaunchLeases { time }
    private var closed = 0
    private fun launch(account: String = "account", session: String = "session"): String {
        return leases.begin("game", account, session).also { leases.attach(it, Closeable { closed++ }) }
    }
    private fun reap(account: String? = "account", session: String? = "session", suspended: Boolean = false) {
        leases.stale(account, session, suspended).forEach { it.close() }
    }

    @Test fun steamLaunchCanCreateASessionBoundLease() {
        val token = leases.begin("game", "account", "session")
        assertTrue(leases.active("game"))
        assertFalse(leases.renew(token, "game", "account", "other"))
        assertThrows(IllegalStateException::class.java) { leases.begin("game", "account", "session") }
    }
    @Test fun releaseIsIdempotentAndCannotDecrementANewLaunch() {
        val old = launch()
        leases.release(old)?.close()
        leases.release(old)?.close()
        assertEquals(1, closed)
        assertFalse(leases.active("game"))
        val current = launch()
        leases.release(old)?.close()
        assertTrue(leases.active("game"))
        leases.release(current)?.close()
        assertEquals(2, closed)
    }
    @Test fun renewalCannotCrossAccountSessionOrGameBoundaries() {
        val token = launch()
        assertFalse(leases.renew(token, "other", "account", "session"))
        assertFalse(leases.renew(token, "game", "other", "session"))
        assertFalse(leases.renew(token, "game", "account", "other"))
        assertTrue(leases.renew(token, "game", "account", "session"))
        time = 60_000
        assertFalse(leases.renew(token, "game", "account", "session"))
        reap()
        assertEquals(1, closed)
        assertFalse(leases.active("game"))
    }
    @Test fun abandonedPreparationAndSessionEndCleanUp() {
        launch()
        time = 180_000
        reap()
        assertEquals(1, closed)
        launch()
        reap(session = null)
        assertEquals(2, closed)
    }
    @Test fun accountSwitchInvalidatesOldLaunchWithoutAffectingItsReplacement() {
        val old = launch()
        reap(account = "next")
        launch("next")
        leases.release(old)?.close()
        assertEquals(1, closed)
        assertTrue(leases.active("game"))
    }
    @Test fun stopRemovesLeaseAndRejectsLateAttachment() {
        val token = leases.begin("game", "account", "session")
        leases.clear().forEach { it.close() }
        assertFalse(leases.attach(token, Closeable { closed++ }))
        assertFalse(leases.active("game"))
        val next = leases.begin("game", "account", "new-session")
        assertTrue(leases.active("game"))
        assertFalse(leases.attach(token, Closeable { closed++ }))
        assertTrue(leases.attach(next, Closeable { closed++ }))
    }
    @Test fun suspensionKeepsLiveLeasesUntilResume() {
        val token = launch()
        time = 200_000
        reap(suspended = true)
        time += 20_000
        assertTrue(leases.renew(token, "game", "account", "session"))
        reap()
        assertEquals(0, closed)
    }
    @Test fun activityQueriesDoNotWaitForBackendClose() {
        val token = launch()
        val detached = leases.release(token)
        val gate = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { gate.await(); detached?.close() }.apply { start() }
        try { assertFalse(leases.active("game")); launch("next") } finally { gate.countDown(); worker.join(1000) }
    }
}
