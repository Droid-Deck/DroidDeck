package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Test

class PipPolicyTest {
    @Test fun automaticEntryNeverOpensStartingFailedOrSuspendedSessions() {
        for (phase in SessionPhase.entries) {
            assertEquals(phase == SessionPhase.READY,
                PipPolicy.eligible(true, true, phase, phase == SessionPhase.SUSPENDED, true))
        }
        assertFalse(PipPolicy.eligible(true, false, SessionPhase.READY, false, true))
        assertFalse(PipPolicy.eligible(false, true, SessionPhase.READY, false, true))
    }

    @Test fun manualEntryAllowsAVisibleSuspendedPicture() {
        assertTrue(PipPolicy.eligible(true, true, SessionPhase.SUSPENDED, true, false))
        assertFalse(PipPolicy.eligible(true, false, SessionPhase.SUSPENDED, true, false))
    }

    @Test fun aspectRatiosStayWithinAndroidLimitsWithoutChangingNormalContent() {
        assertEquals(1920 to 1080, PipPolicy.aspect(1920, 1080))
        assertEquals(239 to 100, PipPolicy.aspect(5760, 1080))
        assertEquals(100 to 239, PipPolicy.aspect(1080, 5760))
        assertEquals(16 to 9, PipPolicy.aspect(0, 0))
    }

    @Test fun pipOutputFitsTheResizedSurfaceWithoutChangingAspectRatio() {
        assertEquals(640 to 360, PipPolicy.outputSize(640, 480, 1920, 1080))
        assertEquals(480 to 270, PipPolicy.outputSize(480, 270, 1920, 1080))
        assertNull(PipPolicy.outputSize(0, 270, 1920, 1080))
    }
}
