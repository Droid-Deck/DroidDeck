package com.droiddeck.launcher.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class DroidDeckHudBoundsTest {
    @Test fun parentPaddingKeepsDocksInsideTheRoundedCornerSafeArea() {
        val bounds = droidDeckHudBounds(2_992, 1_344, 45, 45, 45, 45)

        assertEquals(45f, bounds.left)
        assertEquals(2_947f, bounds.right)
        assertEquals(45f, bounds.top)
        assertEquals(1_299f, bounds.bottom)
        assertEquals(45f to 45f, droidDeckHudPosition(bounds, DroidDeckHudDock.LEFT, 300f, 160f, 0f, 0f))
        assertEquals(2_647f to 45f, droidDeckHudPosition(bounds, DroidDeckHudDock.RIGHT, 300f, 160f, 0f, 0f))
        assertEquals(1_200f to 45f, droidDeckHudPosition(bounds, DroidDeckHudDock.TOP, 300f, 160f, 1_200f, 900f))
        assertEquals(1_200f to 1_139f, droidDeckHudPosition(bounds, DroidDeckHudDock.BOTTOM, 300f, 160f, 1_200f, 900f))
    }

    @Test fun oversizedHudClampsAtTheSafeOrigin() {
        val bounds = droidDeckHudBounds(400, 300, 45, 30, 45, 30)

        assertEquals(45f to 30f, droidDeckHudPosition(bounds, DroidDeckHudDock.NONE, 400f, 300f, 200f, 200f))
        assertEquals(45f to 30f, droidDeckHudPosition(bounds, DroidDeckHudDock.RIGHT, 400f, 300f, 0f, 0f))
        assertEquals(45f to 30f, droidDeckHudPosition(bounds, DroidDeckHudDock.BOTTOM, 400f, 300f, 0f, 0f))
    }
}
