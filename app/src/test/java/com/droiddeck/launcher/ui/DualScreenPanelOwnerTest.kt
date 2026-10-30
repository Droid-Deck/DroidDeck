package com.droiddeck.launcher.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DualScreenPanelOwnerTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun clear() = DualScreenPanel.hide()

    @Test fun aLauncherThatStopsLeavesThePanelOfTheOneThatTookItOver() {
        DualScreenPanel.show(app, 2, 10)
        DualScreenPanel.show(app, 2, 11)
        DualScreenPanel.hide(10)
        assertEquals(2, DualScreenPanel.shownOn)
        DualScreenPanel.hide(11)
        assertEquals(-1, DualScreenPanel.shownOn)
    }

    @Test fun theOwnerHidesItsOwnPanel() {
        DualScreenPanel.show(app, 2, 10)
        DualScreenPanel.hide(10)
        assertEquals(-1, DualScreenPanel.shownOn)
    }

    @Test fun aPausedLauncherIsOnlyBroughtBackWhenThePanelTookFocusItself() {
        DualScreenPanel.show(app, 2, 10)
        assertEquals(10, DualScreenPanel.focusTask(fromPanel = false))
        DualScreenPanel.ownerPaused(10, true)
        assertNull(DualScreenPanel.focusTask(fromPanel = false))
        assertEquals(10, DualScreenPanel.focusTask(fromPanel = true))
        DualScreenPanel.ownerPaused(10, false)
        assertEquals(10, DualScreenPanel.focusTask(fromPanel = false))
    }

    @Test fun anAppTheLauncherOpenedKeepsItsScreenEvenAfterABottomScreenTouch() {
        DualScreenPanel.show(app, 2, 10)
        DualScreenPanel.yieldTo(10, true)
        DualScreenPanel.ownerPaused(10, true)
        assertNull(DualScreenPanel.focusTask(fromPanel = true))
        DualScreenPanel.yieldTo(10, false)
        assertEquals(10, DualScreenPanel.focusTask(fromPanel = true))
    }

    @Test fun anotherTasksPauseDoesNotStopThePanelReturningFocus() {
        DualScreenPanel.show(app, 2, 10)
        DualScreenPanel.ownerPaused(11, true)
        assertEquals(10, DualScreenPanel.focusTask(fromPanel = false))
    }

    @Test fun aNewPanelForgetsThatTheLastLauncherWasPaused() {
        DualScreenPanel.show(app, 2, 10)
        DualScreenPanel.ownerPaused(10, true)
        DualScreenPanel.hide()
        DualScreenPanel.show(app, 2, 10)
        assertEquals(10, DualScreenPanel.focusTask(fromPanel = false))
    }

    @Test fun thePanelTookFocusWhenItJustOpenedOrWasJustTouched() {
        assertTrue(DualScreenPanel.tookFocus(fresh = true, touchedAt = Long.MIN_VALUE, now = 5_000L))
        assertTrue(DualScreenPanel.tookFocus(fresh = false, touchedAt = 4_700L, now = 5_000L))
        assertTrue(DualScreenPanel.tookFocus(fresh = false, touchedAt = 5_000L, now = 5_000L))
        assertFalse(DualScreenPanel.tookFocus(fresh = false, touchedAt = 3_500L, now = 5_000L))
        assertFalse(DualScreenPanel.tookFocus(fresh = false, touchedAt = Long.MIN_VALUE, now = 5_000L))
        assertFalse(DualScreenPanel.tookFocus(fresh = false, touchedAt = 6_000L, now = 5_000L))
    }
}
