package com.droiddeck.launcher.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StripFitTest {
    @Test fun everythingFitsOnAWideLine() {
        assertArrayEquals(booleanArrayOf(true, true, true), fitByPriority(intArrayOf(10, 20, 30), intArrayOf(0, 1, 2), 5, 100))
    }

    @Test fun theLeastImportantItemsAreLeftOutFirst() {
        // 10 + 5 + 30 = 45 fits, adding the 20-wide item (priority 2) would need 70.
        assertArrayEquals(booleanArrayOf(true, false, true), fitByPriority(intArrayOf(10, 20, 30), intArrayOf(0, 2, 1), 5, 60))
    }

    @Test fun anItemThatDoesNotFitEndsTheLine() {
        // The 50-wide item (priority 1) does not fit, so the small priority-2 item is not squeezed in ahead of it.
        assertArrayEquals(booleanArrayOf(true, false, false), fitByPriority(intArrayOf(10, 50, 5), intArrayOf(0, 1, 2), 2, 40))
    }

    @Test fun equalPrioritiesKeepTheirOrder() {
        assertArrayEquals(booleanArrayOf(true, true, false), fitByPriority(intArrayOf(10, 10, 10), intArrayOf(0, 0, 0), 0, 25))
    }

    @Test fun nothingFitsInNoRoom() {
        assertArrayEquals(booleanArrayOf(false, false), fitByPriority(intArrayOf(10, 10), intArrayOf(0, 1), 0, 5))
    }
}
