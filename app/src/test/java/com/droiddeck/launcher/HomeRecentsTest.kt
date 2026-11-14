package com.droiddeck.launcher

import com.droiddeck.launcher.HomeApp.RecentsStep
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecentsTest {
    @Test fun theLauncherHidesAVisibleTaskAndTakesItOver() {
        assertEquals(RecentsStep.HIDE, HomeApp.recentsStep(hidden = true, owned = false, excluded = false))
    }

    @Test fun aTaskSomeoneElseExcludedIsNeverClaimedOrShown() {
        assertEquals(RecentsStep.NONE, HomeApp.recentsStep(hidden = true, owned = false, excluded = true))
        assertEquals(RecentsStep.NONE, HomeApp.recentsStep(hidden = false, owned = false, excluded = true))
    }

    @Test fun onlyATaskTheLauncherHidItselfIsShownAgain() {
        assertEquals(RecentsStep.SHOW, HomeApp.recentsStep(hidden = false, owned = true, excluded = true))
        assertEquals(RecentsStep.FORGET, HomeApp.recentsStep(hidden = false, owned = true, excluded = false))
        assertEquals(RecentsStep.NONE, HomeApp.recentsStep(hidden = true, owned = true, excluded = true))
    }
}
