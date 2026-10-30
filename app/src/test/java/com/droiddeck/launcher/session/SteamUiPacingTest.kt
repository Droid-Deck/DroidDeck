package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamUiPacingTest {
    @Test fun aSixtyOrNinetyHertzScreenIsLeftUncapped() {
        assertEquals(0, SessionPrefs.steamUiFpsLimit(60f))
        assertEquals(0, SessionPrefs.steamUiFpsLimit(59.94f))
        assertEquals(0, SessionPrefs.steamUiFpsLimit(90f))
    }

    @Test fun fasterScreensGetAnEvenDivisorOfTheirRefresh() {
        assertEquals(60, SessionPrefs.steamUiFpsLimit(120f))
        assertEquals(60, SessionPrefs.steamUiFpsLimit(119.99f))
        assertEquals(60, SessionPrefs.steamUiFpsLimit(120.00001f))
        assertEquals(72, SessionPrefs.steamUiFpsLimit(144.00002f))
        assertEquals(82, SessionPrefs.steamUiFpsLimit(165f))
        assertEquals(61, SessionPrefs.steamUiFpsLimit(185.00002f))
        assertEquals(60, SessionPrefs.steamUiFpsLimit(240f))
    }
}
