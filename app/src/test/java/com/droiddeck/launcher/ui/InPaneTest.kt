package com.droiddeck.launcher.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InPaneTest {
    @Test fun aRailItemLeftOfThePaneStartsHalfInside() {
        val o = inPane(Origin(Rect(-200f, 300f, -20f, 420f), 14f))
        assertEquals(-90f, o.bounds.left, 0.01f)
        assertEquals(90f, o.bounds.right, 0.01f)
        assertEquals(300f, o.bounds.top, 0.01f)
    }

    @Test fun aControlInThePaneStaysPut() {
        val origin = Origin(Rect(40f, 300f, 200f, 360f), 12f)
        assertSame(origin, inPane(origin))
    }
}
