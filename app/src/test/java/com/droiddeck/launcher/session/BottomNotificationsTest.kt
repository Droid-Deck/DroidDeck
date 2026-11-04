package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNotificationsTest {
    private val own = "com.droiddeck.launcher"

    @Test fun messagesFromOtherAppsAreKept() {
        assertTrue(BottomNotifications.keep(own, "com.discord", ongoing = false, groupSummary = false, title = "Kurt", text = "ready?"))
    }

    @Test fun ownOngoingSummaryAndEmptyNotificationsAreSkipped() {
        assertFalse(BottomNotifications.keep(own, own, ongoing = false, groupSummary = false, title = "Session", text = "running"))
        assertFalse(BottomNotifications.keep(own, "com.spotify", ongoing = true, groupSummary = false, title = "Song", text = "Artist"))
        assertFalse(BottomNotifications.keep(own, "com.discord", ongoing = false, groupSummary = true, title = "2 messages", text = ""))
        assertFalse(BottomNotifications.keep(own, "com.discord", ongoing = false, groupSummary = false, title = " ", text = ""))
    }

    @Test fun anUpdatedNotificationMovesToTheTopOnce() {
        val list = mutableListOf(
            BottomNotification("a", "App", "one", "", 1L),
            BottomNotification("b", "App", "two", "", 2L),
        )
        BottomNotifications.add(list, BottomNotification("b", "App", "two again", "", 3L))
        assertEquals(listOf("b", "a"), list.map { it.key })
        assertEquals("two again", list.first().title)
    }

    @Test fun theListKeepsTheNewestThirty() {
        val list = mutableListOf<BottomNotification>()
        repeat(35) { BottomNotifications.add(list, BottomNotification("k$it", "App", "t$it", "", it.toLong())) }
        assertEquals(30, list.size)
        assertEquals("k34", list.first().key)
    }
}
