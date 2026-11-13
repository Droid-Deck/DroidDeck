package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeFlingTest {
    private val density = 2f

    @Test fun aQuickFlickGoesTheWayItWasThrownFromAnywhere() {
        assertFalse(EdgeFling.toRight(centerX = 1800f, span = 1920f, vx = -1500f, density = density))
        assertTrue(EdgeFling.toRight(centerX = 100f, span = 1920f, vx = 1500f, density = density))
    }

    @Test fun aSlowReleaseGoesWhereItWouldCoast() {
        assertTrue(EdgeFling.toRight(centerX = 1000f, span = 1920f, vx = 0f, density = density))
        assertFalse(EdgeFling.toRight(centerX = 900f, span = 1920f, vx = 0f, density = density))
        assertFalse(EdgeFling.toRight(centerX = 1100f, span = 1920f, vx = -1100f, density = density))
    }

    @Test fun theHeightCarriesOnWithTheThrowAndStaysOnScreen() {
        assertEquals(436f, EdgeFling.restY(400f, 200f, 0f, 1000f), 0.01f)
        assertEquals(1000f, EdgeFling.restY(900f, 5000f, 0f, 1000f), 0.01f)
        assertEquals(24f, EdgeFling.restY(100f, -5000f, 24f, 1000f), 0.01f)
    }
}
