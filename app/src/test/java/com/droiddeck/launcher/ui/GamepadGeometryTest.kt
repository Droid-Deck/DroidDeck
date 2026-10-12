package com.droiddeck.launcher.ui

import com.droiddeck.launcher.session.SessionDisplay
import com.droiddeck.launcher.ui.GamepadGeometry.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadGeometryTest {
    /** A foldable's unfolded panel (px) and density, as the gamepad gets it under a 16:9 game. */
    private data class Panel(val name: String, val width: Int, val height: Int, val density: Float) {
        val controlsHeight get() = height - SessionDisplay.unfoldedGameHeight(width, height)
    }

    private val panels = listOf(
        Panel("Fold 8 upright", 1848, 2448, 2.625f),
        Panel("Fold 8 as a tablet", 2448, 1848, 2.625f),
        Panel("Pixel Fold upright", 1840, 2208, 2.625f),
        Panel("Pixel Fold as a tablet", 2208, 1840, 2.625f),
        Panel("OnePlus Open upright", 2268, 2440, 2.75f),
        Panel("OnePlus Open as a tablet", 2440, 2268, 2.75f),
        Panel("Fold 8 upright, large display size", 1848, 2448, 3.5f),
        Panel("Fold 8 as a tablet, small display size", 2448, 1848, 2.0f),
    )

    private fun boxes(panel: Panel) =
        GamepadGeometry.layout(panel.width.toFloat(), panel.controlsHeight.toFloat(), panel.density)

    @Test fun everyControlIsPlacedAndPanelsOnlyWhenUpright() {
        for (panel in panels) {
            val upright = panel.height > panel.width
            val expected = Slot.values().toSet() - if (upright) emptySet() else setOf(Slot.PANELS)
            assertEquals(panel.name, expected, boxes(panel).keys)
        }
    }

    @Test fun everyControlStaysOnThePanel() {
        for (panel in panels) for ((slot, box) in boxes(panel)) {
            val where = "${panel.name}: $slot $box"
            assertTrue(where, box.left >= 0f && box.top >= 0f)
            assertTrue(where, box.right <= panel.width && box.bottom <= panel.controlsHeight)
        }
    }

    @Test fun noControlsOverlap() {
        for (panel in panels) {
            val placed = boxes(panel).entries.toList()
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertFalse("${panel.name}: ${placed[i].key} overlaps ${placed[j].key}",
                    placed[i].value.overlaps(placed[j].value))
            }
        }
    }

    @Test fun everyControlIsBigEnoughForAThumb() {
        for (panel in panels) for ((slot, box) in boxes(panel)) {
            val minPx = 40 * panel.density
            assertTrue("${panel.name}: $slot is ${box.width}x${box.height}, under 40dp",
                box.width >= minPx - 1 && box.height >= minPx - 1)
        }
    }

    @Test fun mainControlsSitUnderTheThumbsAndMirror() {
        for (panel in panels) {
            val b = boxes(panel)
            val left = b.getValue(Slot.LEFT_MAIN)
            val right = b.getValue(Slot.RIGHT_MAIN)
            assertEquals(panel.name, left.cy, right.cy, 0.5f)
            assertEquals(panel.name, left.cx, panel.width - right.cx, 0.5f)
            // In the bottom half of their side, nearer the corner than the second controls.
            assertTrue(panel.name, left.cx < b.getValue(Slot.LEFT_SECOND).cx)
            assertTrue(panel.name, left.cy > panel.controlsHeight / 2f)
        }
    }

    @Test fun middleButtonsKeepTheDeckOrder() {
        for (panel in panels) {
            val b = boxes(panel)
            // View before Menu and Steam before QAM: left of it in a row or two by two, above it
            // in a single column.
            fun before(a: Slot, c: Slot) = b.getValue(a).let { x -> b.getValue(c).let { y ->
                x.cx < y.cx - 0.5f || (kotlin.math.abs(x.cx - y.cx) <= 0.5f && x.cy < y.cy) } }
            assertTrue(panel.name, before(Slot.VIEW, Slot.MENU))
            assertTrue(panel.name, before(Slot.STEAM, Slot.QAM))
        }
    }
}
