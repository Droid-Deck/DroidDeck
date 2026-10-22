package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVisibilityPolicyTest {
    @Test fun handsetScreenStateControlsActivityOnDefaultDisplay() {
        assertFalse(SessionVisibilityPolicy.isHidden(true, true, true, false))
        assertTrue(SessionVisibilityPolicy.isHidden(true, false, true, true))
    }

    @Test fun externalDisplayStateControlsActivityIndependentlyOfHandset() {
        assertFalse(SessionVisibilityPolicy.isHidden(true, false, false, true))
        assertTrue(SessionVisibilityPolicy.isHidden(true, true, false, false))
    }

    @Test fun missingActivityDisplayIsHiddenUntilActivityMoves() {
        assertTrue(SessionVisibilityPolicy.isHidden(true, true, false, false))
        assertFalse(SessionVisibilityPolicy.isHidden(true, true, true, false))
    }

    @Test fun stoppedActivityIsHiddenButVisibleXrKeepsSessionVisible() {
        assertTrue(SessionVisibilityPolicy.isHidden(false, true, true, true))
        assertFalse(SessionVisibilityPolicy.isHidden(false, false, true, false, xrActivityVisible = true))
    }
}
