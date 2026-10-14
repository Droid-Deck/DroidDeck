package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PageExitTest {
    @Test fun backToTheSameRailItemDrainsIntoTheControl() {
        assertEquals(PageExit.Drain, pageExit("steam", "steam", PageLeave.Ordinary))
    }

    @Test fun anotherRailItemSinks() {
        assertEquals(PageExit.Sink, pageExit("steam", "games", PageLeave.Ordinary))
    }

    @Test fun anotherPageSinks() {
        assertEquals(PageExit.Sink, pageExit("steam", "components", PageLeave.Ordinary))
    }

    @Test fun aLinkIntoAnotherSectionHops() {
        assertEquals(PageExit.Hop, pageExit("steam", "components", PageLeave.Link))
    }

    @Test fun drainingWinsWhenTheOwnerComesBackAnyway() {
        assertEquals(PageExit.Drain, pageExit("desktop", "desktop", PageLeave.Link))
    }

    @Test fun aPageWithNoOwnerNeverDrains() {
        assertEquals(PageExit.Sink, pageExit(null, "steam", PageLeave.Ordinary))
    }
}
