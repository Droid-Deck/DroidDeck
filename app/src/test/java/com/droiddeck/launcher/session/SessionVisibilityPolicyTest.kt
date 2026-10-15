package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVisibilityPolicyTest {
    @Test fun handsetScreenLockHidesActivityOnDefaultDisplay() {
        assertFalse(SessionVisibilityPolicy.isHidden(true, true, true, true))
        assertTrue(SessionVisibilityPolicy.isHidden(true, false, true, true))
    }

    @Test fun handsetScreenOffDoesNotHideActivityOnPoweredOnDexDisplay() {
        assertFalse(SessionVisibilityPolicy.isHidden(true, false, false, true))
    }

    @Test fun externalDisplayOffHidesActivityEvenWhenHandsetIsOn() {
        assertTrue(SessionVisibilityPolicy.isHidden(true, true, false, false))
    }

    @Test fun disconnectedExternalDisplayIsHidden() {
        assertTrue(SessionVisibilityPolicy.isHidden(true, false, false, false))
    }

    @Test fun hiddenExternalActivityIsHiddenWhileItsDisplayIsOn() {
        assertTrue(SessionVisibilityPolicy.isHidden(false, false, false, true))
    }

    @Test fun startedButUnfocusedMultiwindowActivityRemainsVisible() {
        assertFalse(SessionVisibilityPolicy.isHidden(true, true, true, true))
    }

    @Test fun defaultDisplayScreenOnResumesAfterScreenOff() {
        val wasHidden = SessionVisibilityPolicy.isHidden(true, false, true, true)
        val isHidden = SessionVisibilityPolicy.isHidden(true, true, true, true)
        assertTrue(SessionVisibilityPolicy.shouldResumeOnVisibleTransition(
            previouslyHidden = wasHidden,
            currentlyHidden = isHidden,
            suspendPolicy = SessionPrefs.SUSPEND_AUTO,
        ))
    }

    @Test fun externalDisplayOnResumesAfterOffOrDisconnect() {
        val wasHidden = SessionVisibilityPolicy.isHidden(true, false, false, false)
        val isHidden = SessionVisibilityPolicy.isHidden(true, false, false, true)
        assertTrue(SessionVisibilityPolicy.shouldResumeOnVisibleTransition(
            previouslyHidden = wasHidden,
            currentlyHidden = isHidden,
            suspendPolicy = SessionPrefs.SUSPEND_NATIVE,
        ))
    }

    @Test fun startedActivityResumesAfterBeingStopped() {
        val wasHidden = SessionVisibilityPolicy.isHidden(false, false, false, true)
        val isHidden = SessionVisibilityPolicy.isHidden(true, false, false, true)
        assertTrue(SessionVisibilityPolicy.shouldResumeOnVisibleTransition(
            previouslyHidden = wasHidden,
            currentlyHidden = isHidden,
            suspendPolicy = SessionPrefs.SUSPEND_AUTO,
        ))
    }

    @Test fun onToOnDisplayPropertyChangeDoesNotResume() {
        val wasHidden = SessionVisibilityPolicy.isHidden(true, true, false, true)
        val isHidden = SessionVisibilityPolicy.isHidden(true, true, false, true)
        assertFalse(SessionVisibilityPolicy.shouldResumeOnVisibleTransition(
            previouslyHidden = wasHidden,
            currentlyHidden = isHidden,
            suspendPolicy = SessionPrefs.SUSPEND_AUTO,
        ))
        assertFalse(SessionVisibilityPolicy.shouldResumeOnVisibleTransition(
            previouslyHidden = null,
            currentlyHidden = isHidden,
            suspendPolicy = SessionPrefs.SUSPEND_AUTO,
        ))
    }

    @Test fun handsetScreenOnDoesNotResumeVisibleExternalDisplay() {
        val wasHidden = SessionVisibilityPolicy.isHidden(true, false, false, true)
        val isHidden = SessionVisibilityPolicy.isHidden(true, true, false, true)
        assertFalse(SessionVisibilityPolicy.shouldResumeOnVisibleTransition(
            previouslyHidden = wasHidden,
            currentlyHidden = isHidden,
            suspendPolicy = SessionPrefs.SUSPEND_AUTO,
        ))
    }

    @Test fun manualPolicyDoesNotResumeOnVisibleTransition() {
        val wasHidden = SessionVisibilityPolicy.isHidden(true, false, true, true)
        val isHidden = SessionVisibilityPolicy.isHidden(true, true, true, true)
        assertFalse(SessionVisibilityPolicy.shouldResumeOnVisibleTransition(
            previouslyHidden = wasHidden,
            currentlyHidden = isHidden,
            suspendPolicy = SessionPrefs.SUSPEND_MANUAL,
        ))
    }
}
