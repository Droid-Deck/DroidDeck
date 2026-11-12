package com.droiddeck.launcher

import com.droiddeck.launcher.ui.iconMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeIconsTest {
    @Test fun aChoiceSurvivesEncoding() {
        val choice = HomeIcons.Choice("com.pack", "chrome_alt")
        assertEquals("com.app/.Main#10" to choice, HomeIcons.decode(HomeIcons.encode("com.app/.Main#10", choice)))
    }

    @Test fun aDamagedEntryIsDropped() {
        assertNull(HomeIcons.decode("com.app/.Main"))
        assertNull(HomeIcons.decode("com.app/.Main\t\tchrome"))
    }

    @Test fun iconsNamedLikeTheAppComeFirst() {
        assertEquals(listOf("google_chrome", "alarm", "zoom"), iconMatches(listOf("alarm", "google_chrome", "zoom"), "Chrome", ""))
    }

    @Test fun aSearchIgnoresCaseAndSeparators() {
        assertEquals(listOf("google_chrome"), iconMatches(listOf("alarm", "google_chrome"), "Chrome", "Google Ch"))
    }
}
