package com.droiddeck.launcher.ui

import com.droiddeck.launcher.session.SessionDisplay
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.GamepadGeometry.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadGeometryTest {
    private data class Panel(val name: String, val width: Int, val controlsHeight: Int, val density: Float)

    private val screens = listOf(
        Triple("Fold 8 upright", 1848 to 2448, 2.625f),
        Triple("Fold 8 as a tablet", 2448 to 1848, 2.625f),
        Triple("Fold 7 as a tablet", 2184 to 1968, 2.625f),
        Triple("Pixel Fold upright", 1840 to 2208, 2.625f),
        Triple("Pixel Fold as a tablet", 2208 to 1840, 2.625f),
        Triple("OnePlus Open upright", 2268 to 2440, 2.75f),
        Triple("OnePlus Open as a tablet", 2440 to 2268, 2.75f),
        Triple("Fold 8 upright, large display size", 1848 to 2448, 3.5f),
        Triple("Fold 8 as a tablet, small display size", 2448 to 1848, 2.0f),
    )

    private val panels = screens.flatMap { (name, size, density) ->
        val (w, h) = size
        listOf(
            Panel("$name, unfolded", w, h - SessionDisplay.unfoldedGameSize(w, h, SessionDisplay.FOLDED_16X9).second, density),
            Panel("$name, half folded", w, h / 2, density),
        )
    }

    private fun boxes(panel: Panel, scale: Float = 1f) =
        GamepadGeometry.layout(panel.width.toFloat(), panel.controlsHeight.toFloat(), panel.density, scale)

    @Test fun smallerControlsStayOnThePanelWithoutOverlapping() {
        for (panel in panels) for (percent in SessionPrefs.DECK_CONTROLS_SCALES) {
            val placed = boxes(panel, percent / 100f).entries.toList()
            for ((slot, box) in placed) {
                val where = "${panel.name} at $percent %: $slot $box"
                assertTrue(where, box.left >= 0f && box.top >= 0f && box.right <= panel.width && box.bottom <= panel.controlsHeight)
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertFalse("${panel.name} at $percent %: ${placed[i].key} overlaps ${placed[j].key}",
                    placed[i].value.overlaps(placed[j].value))
            }
        }
    }

    @Test fun everyControlIsPlacedAndPanelsOnlyWhenTall() {
        for (panel in panels) {
            val tall = GamepadGeometry.tall(panel.width.toFloat(), panel.controlsHeight.toFloat())
            val expected = Slot.values().toSet() - if (tall) emptySet() else setOf(Slot.PANELS)
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
            assertTrue(panel.name, left.cx < b.getValue(Slot.LEFT_SECOND).cx)
            assertTrue(panel.name, left.cy > panel.controlsHeight / 2f)
        }
    }

    @Test fun middleButtonsKeepTheDeckOrder() {
        for (panel in panels) {
            val b = boxes(panel)
            fun before(a: Slot, c: Slot) = b.getValue(a).let { x -> b.getValue(c).let { y ->
                x.cx < y.cx - 0.5f || (kotlin.math.abs(x.cx - y.cx) <= 0.5f && x.cy < y.cy) } }
            assertTrue(panel.name, before(Slot.VIEW, Slot.MENU))
            assertTrue(panel.name, before(Slot.STEAM, Slot.QAM))
        }
    }
}
