package com.droiddeck.launcher.runtime

import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DesktopMaintenanceTest {
    @Test(timeout = 15000) fun reservationBlocksRuntimeChangesAndFollowersSurviveScreenRecreation() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        val operation = LinuxRuntimeInstaller.beginMaintenance(null, "Removing desktop")!!
        assertTrue(LinuxRuntimeInstaller.isBusy())
        assertTrue(LinuxRuntimeInstaller.isMaintaining())
        assertFalse(LinuxRuntimeInstaller.isRemoving())
        assertFalse(LinuxRuntimeInstaller.install(null, null, null))
        assertNull(LinuxRuntimeInstaller.beginMaintenance(null, "other"))
        assertNull(LinuxRuntimeInstaller.beginUninstall(null, java.io.File("unused")))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            assertTrue(operation.run(null) { progress ->
                progress.onProgress("Removing desktop", 30)
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                progress.onProgress("Desktop removed", 100)
                null
            })
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val following = CountDownLatch(1)
            val follower = Thread {
                assertEquals(true, LinuxRuntimeInstaller.attach { stage, _ ->
                    if (stage == "Removing desktop") following.countDown()
                })
            }
            follower.start()
            assertTrue(following.await(5, TimeUnit.SECONDS))
            release.countDown()
            worker.join(5000); follower.join(5000)
            assertFalse(LinuxRuntimeInstaller.isBusy())
        } finally { release.countDown(); worker.join(5000) }
    }

    @Test(timeout = 15000) fun refusesActiveOrStartingSessionsAndReportsFailureWithoutRetainingTheReservation() {
        SessionState.phase = SessionPhase.STARTING_GUEST
        assertNull(LinuxRuntimeInstaller.beginMaintenance(null, "Removing desktop"))
        SessionState.phase = SessionPhase.IDLE
        SessionState.running = true
        assertNull(LinuxRuntimeInstaller.beginMaintenance(null, "Removing desktop"))
        SessionState.running = false
        val operation = LinuxRuntimeInstaller.beginMaintenance(null, "Removing desktop")!!
        assertFalse(operation.run(null) { "cannot unlink" })
        assertEquals("cannot unlink", LinuxRuntimeInstaller.removalError())
        assertFalse(LinuxRuntimeInstaller.isBusy())
        assertTrue(LinuxRuntimeInstaller.beginMaintenance(null, "retry")!!.run(null) { null })
        assertNull(LinuxRuntimeInstaller.removalError())
    }
}
