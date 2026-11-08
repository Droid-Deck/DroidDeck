package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MouseMenuHoldTest {
    private var pending: Runnable? = null
    private var delay = 0L
    private var menus = 0
    private val hold = MouseMenuHold(
        schedule = { runnable, ms -> pending = runnable; delay = ms },
        cancel = { if (pending === it) pending = null },
        onMenu = { menus++ },
    )

    private fun elapse() = pending?.also { pending = null }?.run()

    @Test fun holdingTheMiddleButtonStillOpensTheMenu() {
        hold.onButton(MouseMenuHold.MIDDLE, true)
        assertEquals(MouseMenuHold.HOLD_MS, delay)
        hold.onMove(3, -2)
        elapse()
        assertEquals(1, menus)
    }

    @Test fun aQuickMiddleClickDoesNothing() {
        hold.onButton(MouseMenuHold.MIDDLE, true)
        hold.onButton(MouseMenuHold.MIDDLE, false)
        assertNull(pending)
        assertEquals(0, menus)
    }

    @Test fun draggingWithTheMiddleButtonDoesNothing() {
        hold.onButton(MouseMenuHold.MIDDLE, true)
        hold.onMove(30, 0)
        hold.onMove(0, -30)
        assertNull(pending)
        assertEquals(0, menus)
    }

    @Test fun otherButtonsDoNothing() {
        hold.onButton(0, true)
        hold.onButton(1, true)
        assertNull(pending)
    }

    @Test fun resetCancelsAHold() {
        hold.onButton(MouseMenuHold.MIDDLE, true)
        hold.reset()
        assertNull(pending)
        assertEquals(0, menus)
    }
}
