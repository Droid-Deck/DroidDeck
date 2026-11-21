package com.droiddeck.launcher.input

import com.droiddeck.launcher.input.PenCursorPolicy.Overlay
import org.junit.Assert.*
import org.junit.Test

class PenCursorPolicyTest {
    private val shownByMouse = Overlay(visible = true, mouseOwns = true)
    private val shownByTouchpad = Overlay(visible = true, mouseOwns = false)
    private val hidden = Overlay(visible = false, mouseOwns = false)

    @Test fun thePointerRouteFollowsThePenAndRestoresNothing() {
        for (before in listOf(shownByMouse, shownByTouchpad, hidden)) {
            val policy = PenCursorPolicy()
            assertTrue(policy.followsPen(false, before))
            assertTrue(policy.followsPen(false, shownByMouse))
            assertNull(policy.restoreOnLeave())
        }
    }

    @Test fun theTabletRouteHidesWhileNearAndRestoresTheStateFromBeforeTheProximity() {
        for (before in listOf(shownByMouse, shownByTouchpad, hidden)) {
            val policy = PenCursorPolicy()
            assertFalse(policy.followsPen(true, before))
            assertFalse(policy.followsPen(true, hidden))
            assertEquals(before, policy.restoreOnLeave())
            assertNull(policy.restoreOnLeave())
        }
    }

    @Test fun theRouteThePenLeavesFromDecides() {
        val policy = PenCursorPolicy()
        assertTrue(policy.followsPen(false, shownByTouchpad))
        assertFalse(policy.followsPen(true, shownByMouse))
        assertEquals(shownByTouchpad, policy.restoreOnLeave())
        assertFalse(policy.followsPen(true, shownByMouse))
        assertTrue(policy.followsPen(false, hidden))
        assertNull(policy.restoreOnLeave())
    }

    @Test fun aMouseRemovedWhileThePenIsOnThePointerIsNotRestoredAfterTheTablet() {
        val policy = PenCursorPolicy()
        assertTrue(policy.followsPen(false, shownByMouse))
        policy.mouseRemoved()
        assertFalse(policy.followsPen(true, shownByMouse))
        assertEquals(hidden, policy.restoreOnLeave())
    }

    @Test fun otherInputWhileATabletPenIsNearReplacesTheStateToRestore() {
        val policy = PenCursorPolicy()
        policy.changed(shownByMouse)
        policy.mouseRemoved()
        assertNull(policy.restoreOnLeave())
        policy.followsPen(true, shownByMouse)
        policy.mouseRemoved()
        assertEquals(hidden, policy.restoreOnLeave())
        policy.followsPen(true, shownByTouchpad)
        policy.mouseRemoved()
        assertEquals(shownByTouchpad, policy.restoreOnLeave())
        policy.followsPen(true, hidden)
        policy.changed(shownByMouse)
        assertEquals(shownByMouse, policy.restoreOnLeave())
    }
}
