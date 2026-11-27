package com.droiddeck.launcher.input

import org.junit.Assert.*
import org.junit.Test

class InputEditorMenuTest {
    @Test fun theSizeRangeWidensInStepsOfFive() {
        assertEquals(40..250, selectionSizeRange(118))
        assertEquals(35..250, selectionSizeRange(37))
        assertEquals(40..255, selectionSizeRange(253))
    }
}
