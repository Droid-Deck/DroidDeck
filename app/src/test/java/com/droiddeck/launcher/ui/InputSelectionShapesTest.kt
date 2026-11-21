package com.droiddeck.launcher.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class InputSelectionShapesTest {
    @Test fun aGroupOutlineKeepsASquareControlInItsCornerInside() {
        val square = OutlineShape().set(20f, 20f, 40f, 40f, 0f)
        val round = OutlineShape().set(80f, 80f, 160f, 160f, 40f)
        val outline = OutlineShape().enclose(listOf(square, round), 2, 10f)
        assertTrue(outline.contains(20f, 20f))
        assertTrue(outline.contains(160f, 160f))
    }
}
