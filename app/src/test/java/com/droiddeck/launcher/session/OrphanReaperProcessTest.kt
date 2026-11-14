package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanReaperProcessTest {
    @Test fun unavailableProcessListDefersCleanup() {
        assertTrue(OrphanReaper.hasOtherProcesses(null, 100) { false })
    }

    @Test fun theAppAndForeignProcessesDoNotKeepOldRuntimes() {
        assertFalse(OrphanReaper.hasOtherProcesses(listOf(0, 1, 100, 200), 100) { pid ->
            check(pid == 200) { "do not probe self or process groups" }
            false
        })
    }

    @Test fun anyRemainingAppProcessDefersCleanup() {
        assertTrue(OrphanReaper.hasOtherProcesses(listOf(100, 200, 300), 100) { it == 300 })
        assertFalse(OrphanReaper.hasOtherProcesses(emptyList(), 100) { true })
    }
}
