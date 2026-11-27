package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class InputControlGroupsTest {
    private fun round(c: InputControl, x: Float, y: Float, size: Float = .06f) = InputSurfaceElement(c, x, y, size, size)
    private val layout = listOf(
        round(InputControl.UP, .10f, .50f), round(InputControl.RIGHT, .16f, .56f),
        round(InputControl.DOWN, .10f, .62f), round(InputControl.LEFT, .04f, .56f),
        round(InputControl.A, .80f, .62f), round(InputControl.B, .86f, .56f),
        round(InputControl.X, .74f, .56f), round(InputControl.Y, .80f, .50f),
        InputSurfaceElement(InputControl.LB, .05f, .05f, .12f, .06f), InputSurfaceElement(InputControl.LT, .05f, .12f, .12f, .06f),
        InputSurfaceElement(InputControl.RB, .83f, .05f, .12f, .06f), InputSurfaceElement(InputControl.RT, .83f, .12f, .12f, .06f),
        round(InputControl.LS, .25f, .40f, .2f), round(InputControl.START, .55f, .10f),
    )
    private val dpad = listOf("legacy:up", "legacy:right", "legacy:down", "legacy:left")

    @Test fun stockControlsFormTheirGroups() {
        assertEquals(dpad, layout.groupOf("legacy:right"))
        assertEquals(listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y"), layout.groupOf("legacy:y"))
        assertEquals(listOf("legacy:lb", "legacy:lt"), layout.groupOf("legacy:lt"))
        assertEquals(listOf("legacy:rb", "legacy:rt"), layout.groupOf("legacy:rb"))
        assertEquals(listOf("legacy:ls"), layout.groupOf("legacy:ls"))
        assertEquals(listOf("legacy:start"), layout.groupOf("legacy:start"))
    }

    @Test fun addedControlsStayOutOfGroups() {
        val extra = InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f, id = "custom")
        assertEquals(listOf("custom"), (layout + extra).groupOf("custom"))
        assertEquals(listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y"), (layout + extra).groupOf("legacy:a"))
    }

    @Test fun firstTapSelectsTheGroupAndTheSecondTheButton() {
        val first = layout.tapSelection(emptyList(), "legacy:up")
        assertEquals(dpad, first)
        assertEquals(InputControlGroup.DPAD, layout.selectedGroup(first))
        val second = layout.tapSelection(first, "legacy:left")
        assertEquals(listOf("legacy:left"), second)
        assertNull(layout.selectedGroup(second))
        assertEquals(listOf("legacy:down"), layout.tapSelection(second, "legacy:down"))
        assertEquals(listOf("legacy:lb", "legacy:lt"), layout.tapSelection(second, "legacy:lt"))
        assertEquals(emptyList<String>(), layout.tapSelection(second, null))
    }

    @Test fun tappingTheSelectedControlAgainReachesTheOneUnderIt() {
        val stacked = layout + InputSurfaceElement(InputControl.A, .10f, .50f, .06f, .06f, id = "added")
        val under = listOf("added", "legacy:up")
        assertEquals(listOf("added"), stacked.tapSelection(emptyList(), "added", under))
        assertEquals(dpad, stacked.tapSelection(listOf("added"), "added", under))
        assertEquals(listOf("legacy:up"), stacked.tapSelection(dpad, "legacy:up", under))
        assertEquals(listOf("added"), stacked.tapSelection(listOf("legacy:up"), "legacy:up", under))
        assertEquals(listOf("added"), stacked.tapSelection(listOf("added"), "added", listOf("added")))
    }

    @Test fun controlsPushedToTheEdgeStayValidForSaving() {
        val pushed = listOf(InputSurfaceElement(InputControl.A, .9f, .9f, .15f, .2f)).keptIn(SurfaceBounds(0f, 0f, 1f, 1f))
        assertEquals(pushed.map { it.bounded() }, pushed)
    }

    @Test fun pressingASelectedGroupKeepsItSoItCanBeDragged() {
        assertEquals(dpad, layout.pressSelection(dpad, "legacy:up"))
    }

    @Test fun anAddedSetMovesAsOneUntilAMemberIsTapped() {
        val set = listOf("added-1", "added-2")
        val added = layout + set.map { InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f, id = it) }
        assertEquals(set, added.pressSelection(set, "added-2"))
        assertEquals(listOf("added-2"), added.tapSelection(set, "added-2"))
        assertEquals(dpad, added.pressSelection(set, "legacy:up"))
    }

    @Test fun anAddedSetWithAGroupKeyIsAGroupOfItsOwn() {
        fun set(key: String) = (0..3).map { i -> InputSurfaceElement(InputControl.A, .3f + i * .05f, .3f, .04f, .04f, id = "$key-$i", group = "DPAD:$key") }
        val first = set("one")
        val second = set("two")
        val loose = InputSurfaceElement(InputControl.UP, .6f, .6f, .04f, .04f, id = "loose")
        val all = layout + first + second + loose
        val ids = first.map { it.id }
        assertEquals(ids, all.groupOf("one-2"))
        assertEquals(second.map { it.id }, all.groupOf("two-0"))
        assertEquals(dpad, all.groupOf("legacy:up"))
        assertEquals(listOf("loose"), all.groupOf("loose"))
        val tapped = all.tapSelection(emptyList(), "one-1")
        assertEquals(ids, tapped)
        assertEquals(InputControlGroup.DPAD, all.selectedGroup(tapped))
        assertEquals(ids, all.pressSelection(tapped, "one-3"))
        assertEquals(listOf("one-3"), all.tapSelection(tapped, "one-3"))
        assertEquals(second.map { it.id }, all.tapSelection(listOf("one-3"), "two-1"))
        val survivors = all.filterNot { it.id == "one-0" }
        assertEquals(ids - "one-0", survivors.groupOf("one-1"))
        val lone = all.filter { it.id !in ids || it.id == "one-2" }
        assertEquals(listOf("one-2"), lone.groupOf("one-2"))
        assertNull(lone.selectedGroup(lone.tapSelection(emptyList(), "one-2")))
    }

    @Test fun keepingTheStockDpadOutOfTheCutoutLeavesAnAddedDpadSetInPlace() {
        val set = (0..3).map { i -> InputSurfaceElement(InputControl.A, .5f + i * .05f, .3f, .04f, .04f, id = "s-$i", group = "DPAD:s") }
        val all = layout.moveSelection(dpad, -1f, 0f) + set
        val kept = all.keptIn(SurfaceBounds(.05f, 0f, 1f, 1f))
        assertEquals(set, kept.filter { it.id in set.map { e -> e.id } })
        assertEquals(.05f, kept.boundsOf(dpad)!!.left, 1e-4f)
    }

    @Test fun aControlWiderThanTheSafeAreaShrinksToFitIt() {
        val area = SurfaceBounds(.05f, 0f, 1f, 1f)
        val wide = InputSurfaceElement(InputControl.A, 0f, .4f, 1f, .2f, id = "wide")
        val small = InputSurfaceElement(InputControl.B, 0f, .1f, .1f, .1f, id = "small")
        val (fitted, moved) = listOf(wide, small).keptIn(area)
        assertEquals(.05f, fitted.x, 1e-4f)
        assertEquals(1f, fitted.x + fitted.width, 1e-4f)
        assertEquals(.2f, fitted.height, 1e-4f)
        assertEquals(small.copy(x = .05f), moved)
    }

    @Test fun aBlankOrUnknownKeyLeavesAControlOnItsOwn() {
        for (key in listOf("", "  ", "k", "NOPE:k", "DPAD:")) {
            val pair = listOf("p1", "p2").map { InputSurfaceElement(InputControl.A, .4f, .4f, .1f, .1f, id = it, group = key) }
            assertEquals(key, listOf("p1"), (layout + pair).groupOf("p1"))
        }
        assertEquals(dpad, (layout.map { if (it.id == "legacy:up") it.copy(group = "FACE:x") else it }).groupOf("legacy:up"))
    }

    @Test fun movingAGroupKeepsItsShapeAndStopsAtTheEdge() {
        val moved = layout.moveSelection(dpad, -.5f, .1f)
        val before = layout.boundsOf(dpad)!!
        val after = moved.boundsOf(dpad)!!
        assertEquals(0f, after.left, 1e-5f)
        assertEquals(before.top + .1f, after.top, 1e-5f)
        assertEquals(before.width, after.width, 1e-5f)
        assertEquals(layout.filter { it.id !in dpad }, moved.filter { it.id !in dpad })
    }

    @Test fun cornerResizeStretchesButtonsFreely() {
        val ids = listOf("legacy:lb", "legacy:lt")
        val b = layout.boundsOf(ids)!!
        val resized = layout.resizeSelection(ids, 2 or 8, SurfaceBounds(b.left, b.top, b.left + b.width * 2f, b.bottom))
        val lb = resized.first { it.id == "legacy:lb" }
        assertEquals(.24f, lb.width, 1e-5f)
        assertEquals(.06f, lb.height, 1e-5f)
    }

    @Test fun sticksKeepTheirProportionsWhenResized() {
        val ids = listOf("legacy:ls")
        val b = layout.boundsOf(ids)!!
        val stick = layout.resizeSelection(ids, 2 or 8, SurfaceBounds(b.left, b.top, b.right + .1f, b.bottom)).first { it.id == "legacy:ls" }
        assertEquals(.3f, stick.width, 1e-5f)
        assertEquals(.3f, stick.height, 1e-5f)
        assertEquals(b.left, stick.x, 1e-5f)
    }

    @Test fun resizingFromTheTopLeftKeepsTheOppositeCorner() {
        val ids = listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val b = layout.boundsOf(ids)!!
        val after = layout.resizeSelection(ids, 1 or 4, SurfaceBounds(b.left - .1f, b.top - .05f, b.right, b.bottom)).boundsOf(ids)!!
        assertEquals(b.right, after.right, 1e-5f)
        assertEquals(b.bottom, after.bottom, 1e-5f)
        assertEquals(b.left - .1f, after.left, 1e-5f)
    }

    @Test fun shrinkingStopsAtTheMinimumButtonSize() {
        val ids = listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        val b = layout.boundsOf(ids)!!
        val after = layout.resizeSelection(ids, 2 or 8, SurfaceBounds(b.left, b.top, b.left + .001f, b.top + .001f))
        assertTrue(after.filter { it.id in ids }.all { it.width >= .03f - 1e-5f && it.height >= .03f - 1e-5f })
    }

    @Test fun sizeSliderScalesAboutTheCentreAndReportsThePercent() {
        val reference = layout.associateBy { it.id }
        val before = layout.boundsOf(dpad)!!
        assertEquals(100f, layout.sizePercent(dpad, reference), 1e-3f)
        val bigger = layout.scaleSelection(dpad, 1.5f)
        val after = bigger.boundsOf(dpad)!!
        assertEquals(150f, bigger.sizePercent(dpad, reference), 1e-2f)
        assertEquals(before.centerY, after.centerY, 1e-5f)
        assertTrue(after.left >= 0f)
        val up = bigger.first { it.id == "legacy:up" }
        assertEquals(.09f, up.width, 1e-5f)
    }

    @Test fun spreadingAGroupOutDoesNotChangeItsSize() {
        val reference = layout.associateBy { it.id }
        val spread = layout.moveSelection(listOf("legacy:up"), 0f, -.1f)
        assertEquals(100f, spread.sizePercent(dpad, reference), 1e-3f)
    }

    @Test fun scalingNeverLeavesTheScreen() {
        val after = layout.scaleSelection(dpad, 20f).boundsOf(dpad)!!
        assertTrue(after.left >= 0f && after.top >= 0f && after.right <= 1.00001f && after.bottom <= 1.00001f)
    }

    private val leftCutout = SurfaceBounds(.05f, 0f, 1f, 1f)
    private val rightCutout = SurfaceBounds(0f, 0f, .95f, 1f)

    @Test fun draggingStopsAtTheCutoutEdge() {
        assertEquals(.05f, layout.moveSelection(dpad, -.5f, 0f, leftCutout).boundsOf(dpad)!!.left, 1e-5f)
        val face = listOf("legacy:a", "legacy:b", "legacy:x", "legacy:y")
        assertEquals(.95f, layout.moveSelection(face, .5f, 0f, rightCutout).boundsOf(face)!!.right, 1e-5f)
    }

    @Test fun resizingTowardTheCutoutStopsAtItsEdge() {
        val ids = listOf("legacy:lb", "legacy:lt")
        val b = layout.boundsOf(ids)!!
        val after = layout.resizeSelection(ids, 1 or 4, SurfaceBounds(-.2f, b.top, b.right, b.bottom), SurfaceBounds(.02f, 0f, 1f, 1f)).boundsOf(ids)!!
        assertEquals(.02f, after.left, 1e-5f)
        assertEquals(b.right, after.right, 1e-5f)
    }

    @Test fun scalingStaysOutOfTheCutout() {
        val after = layout.scaleSelection(dpad, 20f, leftCutout).boundsOf(dpad)!!
        assertTrue(after.left >= .05f - 1e-5f && after.right <= 1.00001f)
    }
}
