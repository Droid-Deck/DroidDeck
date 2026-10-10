package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GlideShapeTest {
    @Test fun restingAcrossCoversTheChip() {
        val r = glideShape(10f, 90f, 0f, 40f, pinch = 0f, drop = 6f, vertical = false)
        assertEquals(10f, r.left, 0.01f); assertEquals(90f, r.right, 0.01f)
        assertEquals(0f, r.top, 0.01f); assertEquals(40f, r.bottom, 0.01f)
    }

    @Test fun edgesInEitherOrder() {
        val r = glideShape(90f, 10f, 0f, 40f, pinch = 0f, drop = 6f, vertical = false)
        assertEquals(10f, r.left, 0.01f); assertEquals(90f, r.right, 0.01f)
    }

    @Test fun pinchedIsATwelveDpDropAtTheCentre() {
        val r = glideShape(10f, 90f, 0f, 40f, pinch = 1f, drop = 6f, vertical = false)
        assertEquals(12f, r.width, 0.01f); assertEquals(12f, r.height, 0.01f)
        assertEquals(50f, r.center.x, 0.01f); assertEquals(20f, r.center.y, 0.01f)
    }

    @Test fun verticalRunsTheAxisDown() {
        val r = glideShape(100f, 160f, 4f, 84f, pinch = 0f, drop = 6f, vertical = true)
        assertEquals(4f, r.left, 0.01f); assertEquals(84f, r.right, 0.01f)
        assertEquals(100f, r.top, 0.01f); assertEquals(160f, r.bottom, 0.01f)
    }
}
