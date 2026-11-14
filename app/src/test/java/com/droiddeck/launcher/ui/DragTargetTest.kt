package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DragTargetTest {
    @Test fun smallMovesStayInPlace() = assertEquals(5, dragTarget(20f, 100f, 5, 0, 9))

    @Test fun halfRowMovesToNextSlot() {
        assertEquals(6, dragTarget(60f, 100f, 5, 0, 9))
        assertEquals(4, dragTarget(-60f, 100f, 5, 0, 9))
    }

    @Test fun longMovesSkipSeveralRows() = assertEquals(8, dragTarget(310f, 100f, 5, 0, 9))

    @Test fun staysInsideTheDisc() {
        assertEquals(9, dragTarget(900f, 100f, 5, 0, 9))
        assertEquals(2, dragTarget(-900f, 100f, 5, 2, 9))
    }
}
