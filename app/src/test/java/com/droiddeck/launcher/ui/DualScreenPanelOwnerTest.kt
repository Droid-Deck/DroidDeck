package com.droiddeck.launcher.ui

import org.junit.After
import org.junit.Assert.assertEquals
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
}
