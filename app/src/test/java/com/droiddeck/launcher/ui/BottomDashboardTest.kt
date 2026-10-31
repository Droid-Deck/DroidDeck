package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomDashboardTest {
    @Test fun theRememberedPageOpensAgain() {
        assertEquals("shots", pickBottomPage("shots", listOf("downloads", "shots")))
    }

    @Test fun aGoneOrMissingPageFallsBackToTheFirst() {
        assertEquals("downloads", pickBottomPage("gone", listOf("downloads", "shots")))
        assertEquals("downloads", pickBottomPage(null, listOf("downloads", "shots")))
        assertNull(pickBottomPage("shots", emptyList()))
    }

    @Test fun tabNamesShowOnlyWhileEveryTabFits() {
        // Retroid Pocket Duo bottom panel: 1280x960 at 320 dpi = 640 dp wide.
        assertTrue(bottomTabsWithLabels(640f, 4))
        assertFalse(bottomTabsWithLabels(640f, 5))
        assertFalse(bottomTabsWithLabels(360f, 3))
    }
}
