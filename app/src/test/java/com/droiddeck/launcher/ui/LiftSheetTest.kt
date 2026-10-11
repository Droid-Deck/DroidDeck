package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiftSheetTest {
    @Test fun theSheetTopIs82dpDownA360dpPane() {
        assertEquals(82f, sheetTop(360f, 56f, 16f, 3f), 0.01f)
        assertEquals(164f, sheetTop(720f, 56f, 16f, 3f), 0.01f)
    }

    @Test fun theButtonKeeps16dpAboveIt() {
        // A short pane: the edge comes down so the button riding it still clears the top by 16.
        val top = sheetTop(150f, 56f, 16f, 3f)
        assertEquals(41f, top, 0.01f)
        assertEquals(16f, top + 3f - 56f / 2f, 0.01f)
    }
}
