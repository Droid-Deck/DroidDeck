package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreGridNavigationTest {
    @Test fun downAndUpPreserveEveryColumnAcrossSeveralRows() {
        for (columns in 1..6) for (index in 0 until columns * 3) {
            val down = storeGridNeighbor(index, columns * 4, columns, 1)!!
            assertEquals(index % columns, down % columns)
            assertEquals(index, storeGridNeighbor(down, columns * 4, columns, -1))
        }
    }

    @Test fun incompleteLastRowDoesNotWrapBackToTheLeft() {
        assertEquals(8, storeGridNeighbor(4, 10, 4, 1))
        assertEquals(9, storeGridNeighbor(5, 10, 4, 1))
        assertNull(storeGridNeighbor(6, 10, 4, 1))
        assertNull(storeGridNeighbor(7, 10, 4, 1))
        assertNull(storeGridNeighbor(9, 10, 4, 1))
    }

    @Test fun firstRowCanLeaveUpwardsAndChangedColumnCountsAreUsed() {
        assertNull(storeGridNeighbor(2, 10, 4, -1))
        assertEquals(5, storeGridNeighbor(2, 10, 3, 1))
        assertEquals(6, storeGridNeighbor(2, 10, 4, 1))
    }

    @Test fun downFromTheInstalledSectionLandsInTheSameColumnBelowTheDivider() {
        assertEquals(6, storeGridNeighbor(0, 12, 4, 1, split = 6).let { storeGridNeighbor(it!!, 12, 4, 1, split = 6) })
        assertEquals(6, storeGridNeighbor(4, 12, 4, 1, split = 6))
        assertEquals(7, storeGridNeighbor(5, 12, 4, 1, split = 6))
        assertEquals(8, storeGridNeighbor(2, 12, 4, 1, split = 6))
    }

    @Test fun upFromBelowTheDividerClampsToTheShortInstalledRow() {
        assertEquals(4, storeGridNeighbor(6, 12, 4, -1, split = 6))
        assertEquals(5, storeGridNeighbor(7, 12, 4, -1, split = 6))
        assertEquals(5, storeGridNeighbor(9, 12, 4, -1, split = 6))
        assertEquals(6, storeGridNeighbor(10, 12, 4, -1, split = 6))
    }

    @Test fun lastSectionStillStopsAtItsIncompleteRow() {
        assertNull(storeGridNeighbor(9, 11, 4, 1, split = 6))
        assertEquals(10, storeGridNeighbor(6, 11, 4, 1, split = 6))
        assertNull(storeGridNeighbor(1, 11, 4, -1, split = 6))
    }
}
