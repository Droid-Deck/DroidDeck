package com.droiddeck.launcher.stores.steam

import com.droiddeck.launcher.session.SessionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamClientSignInTest {
    @Test fun syncsAfterTheNewClientSessionEnds() {
        assertTrue(SteamClientSignIn.shouldSync("previous-session", null, "steam-login-session"))
    }

    @Test fun doesNotSyncIfTheRequestedSessionNeverStarted() {
        assertFalse(SteamClientSignIn.shouldSync("previous-session", null, "previous-session"))
    }

    @Test fun syncsAfterReturningFromAnAlreadyRunningSteamSession() {
        assertTrue(SteamClientSignIn.shouldSync(null, "active-steam-session", "active-steam-session"))
    }

    @Test fun doesNotSyncIfTheActiveSessionChangedBeforeReturning() {
        assertFalse(SteamClientSignIn.shouldSync(null, "requested-session", "different-session"))
    }

    @Test fun waitsUntilTheRequestedSessionHasEnded() {
        assertFalse(SteamClientSignIn.readyToFinish(true, true, SessionPhase.READY))
        assertFalse(SteamClientSignIn.readyToFinish(true, false, SessionPhase.STOPPING))
        assertTrue(SteamClientSignIn.readyToFinish(true, false, SessionPhase.IDLE))
        assertFalse(SteamClientSignIn.readyToFinish(false, false, SessionPhase.IDLE))
    }
}
