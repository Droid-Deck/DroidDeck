package com.droiddeck.launcher.ui

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomChromeTest {
    @After fun clear() {
        BottomChrome.reset()
    }

    @Test fun aFullScreenPanelHidesTheBarAndThePullTabBringsItBack() {
        assertTrue(BottomChrome.barShown)
        BottomChrome.request(true)
        assertFalse(BottomChrome.barShown)
        BottomChrome.show(true)
        assertTrue(BottomChrome.barShown)
        BottomChrome.request(false)
        assertTrue(BottomChrome.barShown)
    }

    @Test fun hidingByHandLastsUntilAPanelChangesItsRequest() {
        BottomChrome.show(false)
        assertFalse(BottomChrome.barShown)
        BottomChrome.request(true)
        BottomChrome.request(false)
        assertTrue(BottomChrome.barShown)
    }

    @Test fun moreReleasesThanRequestsNeverHideTheBarForGood() {
        BottomChrome.request(false)
        BottomChrome.request(false)
        assertTrue(BottomChrome.barShown)
    }
}
