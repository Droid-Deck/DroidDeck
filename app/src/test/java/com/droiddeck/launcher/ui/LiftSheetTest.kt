package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiftSheetTest {
    @Test fun theSheetTopIs60dpDownA360dpPane() {
        assertEquals(60f, sheetTop(360f, 56f, 16f, 3f), 0.01f)
        assertEquals(120f, sheetTop(720f, 56f, 16f, 3f), 0.01f)
    }

    @Test fun theButtonKeeps16dpAboveIt() {
        val top = sheetTop(150f, 56f, 16f, 3f)
        assertEquals(41f, top, 0.01f)
        assertEquals(16f, top + 3f - 56f / 2f, 0.01f)
    }
}
