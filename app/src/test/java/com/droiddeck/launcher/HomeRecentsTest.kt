package com.droiddeck.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecentsTest {
    @Test fun onlyTasksTheLauncherHidOnItsOwnAreShownAgain() {
        val hidden = mutableSetOf<Int>()
        assertFalse(HomeApp.recentsChange(false, 7, hidden))
        assertTrue(HomeApp.recentsChange(true, 7, hidden))
        assertFalse(HomeApp.recentsChange(true, 7, hidden))
        assertTrue(HomeApp.recentsChange(false, 7, hidden))
        assertFalse(HomeApp.recentsChange(false, 7, hidden))
    }
}
