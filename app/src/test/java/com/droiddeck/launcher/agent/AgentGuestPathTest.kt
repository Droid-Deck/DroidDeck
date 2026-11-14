package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGuestPathTest {
    private val dirs = AgentHost.GUEST_PATH.split(':')

    @Test fun includesTheSystemBinariesDpkgNeedsAsRoot() {
        assertTrue(dirs.containsAll(listOf("/usr/local/sbin", "/usr/sbin", "/sbin")))
    }

    @Test fun keepsUserBinariesFirstAndTheStandardDirs() {
        assertEquals("/root/.local/bin", dirs.first())
        assertTrue(dirs.containsAll(listOf("/usr/local/bin", "/usr/bin", "/bin")))
    }
}
