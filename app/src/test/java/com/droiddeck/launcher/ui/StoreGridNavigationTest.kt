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
}
