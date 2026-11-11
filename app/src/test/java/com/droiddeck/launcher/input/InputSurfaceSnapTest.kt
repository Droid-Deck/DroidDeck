package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class InputSurfaceSnapTest {
    private val element = InputSurfaceElement(InputControl.A, .395f, .3f, .2f, .2f)
    @Test fun snapsToCanvasCenterWithoutChangingSize() {
        val result = snapSurfaceElement(element, emptyList(), .01f, .01f)
        assertEquals(.4f, result.element.x, .0001f)
        assertEquals(.5f, result.x!!, 0f)
        assertEquals(element.width, result.element.width, 0f)
    }
    @Test fun alignsEdgesWithOtherElementsAndLeavesDistantPositionsFree() {
        val other = element.copy(x = .1f, y = .65f)
        val aligned = snapSurfaceElement(element.copy(x = .105f), listOf(other), .01f, .01f)
        assertEquals(.1f, aligned.element.x, .0001f)
        val free = snapSurfaceElement(element.copy(x = .36f, y = .34f), listOf(other), .01f, .01f)
        assertNull(free.x); assertNull(free.y)
    }
    @Test fun resizingSnapsTheDraggedEdgeAndKeepsTheOppositeEdge() {
        val result = snapSurfaceElement(element.copy(x = .2f, width = .295f), emptyList(), .01f, .01f, 2 or 8)
        assertEquals(.2f, result.element.x, 0f)
        assertEquals(.3f, result.element.width, .0001f)
    }
    @Test fun boundsPreventFalseGuidesAndNegativeSize() {
        val e = element.copy(x = .01f, width = .98f)
        val result = snapSurfaceElement(e, listOf(element.copy(x = .995f)), .02f, .01f, 1 or 4)
        assertTrue(result.element.x >= 0f)
        assertTrue(result.element.x + result.element.width <= 1f)
        assertTrue(result.element.width >= .03f)
    }
}
