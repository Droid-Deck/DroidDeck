package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryColumnsTest {
    @Test fun everySizeStepChangesTheColumnCountUntilOne() {
        val phone = (0..4).map { libraryColumns(width = 1080, base = 450, gap = 30, level = it) }
        assertEquals(listOf(4, 3, 2, 1, 1), phone)
        val tablet = (0..4).map { libraryColumns(width = 2400, base = 450, gap = 30, level = it) }
        assertEquals(listOf(7, 6, 5, 4, 3), tablet)
    }
}
