package com.droiddeck.launcher.session

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryKillTest {
    @Test
    fun recognisesLowMemoryAndTheAndroid17Limiter() {
        assertTrue(SessionArtifacts.memoryKill(ApplicationExitInfo.REASON_LOW_MEMORY, null))
        assertTrue(SessionArtifacts.memoryKill(17, "kill"))
        assertTrue(SessionArtifacts.memoryKill(ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap"))
        assertFalse(SessionArtifacts.memoryKill(ApplicationExitInfo.REASON_OTHER, "unstable content provider"))
        assertFalse(SessionArtifacts.memoryKill(ApplicationExitInfo.REASON_OTHER, null))
        assertFalse(SessionArtifacts.memoryKill(ApplicationExitInfo.REASON_USER_REQUESTED, "MemoryLimiter:AnonSwap"))
    }
}
