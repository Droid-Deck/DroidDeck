package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchCoordinatorTest {
    @Test
    fun aNewLaunchSupersedesAnOlderLaunch() {
        val coordinator = LaunchCoordinator<String>()
        val first = coordinator.beginLaunch()
        val second = coordinator.beginLaunch()
        assertFalse(coordinator.isCurrent(first))
        assertTrue(coordinator.isCurrent(second))
    }

    @Test
    fun anAllowedLaunchDropsThePendingLink() {
        val coordinator = LaunchCoordinator<String>()
        coordinator.beginLink("link")
        coordinator.beginLaunch()
        assertFalse(coordinator.hasPending())
    }

    @Test
    fun aLinkReadResolvesAndIsConsumed() {
        val coordinator = LaunchCoordinator<String>()
        val ticket = coordinator.beginLink("link")
        assertTrue(coordinator.resolve(ticket, "game"))
        assertEquals("game", coordinator.pendingLink()?.id)
        assertTrue(coordinator.consume(ticket))
        assertFalse(coordinator.hasPending())
    }

    @Test
    fun aStaleLinkReadCannotReplaceTheNewLink() {
        val coordinator = LaunchCoordinator<String>()
        val first = coordinator.beginLink("first")
        val second = coordinator.beginLink("second")
        assertFalse(coordinator.resolve(first, "old"))
        assertTrue(coordinator.resolve(second, "new"))
        assertEquals("new", coordinator.pendingLink()?.id)
    }

    @Test
    fun handlingNeedsAResolvedLink() {
        val coordinator = LaunchCoordinator<String>()
        val ticket = coordinator.beginLink("link")
        assertFalse(coordinator.beginHandling(ticket))
        coordinator.resolve(ticket, "game")
        assertTrue(coordinator.beginHandling(ticket))
        assertTrue(coordinator.isHandling(ticket))
    }

    @Test
    fun aStaleCompletionCannotConsumeTheNewLink() {
        val coordinator = LaunchCoordinator<String>()
        val first = coordinator.beginLink("first")
        val second = coordinator.beginLink("second")
        coordinator.resolve(second, "new")
        assertFalse(coordinator.consume(first))
        assertTrue(coordinator.hasPending())
    }

    @Test
    fun aUiLaunchDropsThePendingLinkBeforeItLaunches() {
        val coordinator = LaunchCoordinator<String>()
        coordinator.beginLink("link")
        val launch = coordinator.beginLaunch()
        assertNull(coordinator.pendingLink())
        assertTrue(coordinator.isCurrent(launch))
    }

    @Test
    fun aSecondLinkReplacesTheFirst() {
        val coordinator = LaunchCoordinator<String>()
        val first = coordinator.beginLink("first")
        val second = coordinator.beginLink("second")
        assertFalse(coordinator.isCurrent(first))
        assertTrue(coordinator.isCurrent(second))
        assertEquals("second", coordinator.pendingLink()?.owner)
    }

    @Test
    fun aLinkKeepsItsOwnerForIntentCleanup() {
        val coordinator = LaunchCoordinator<String>()
        val ticket = coordinator.beginLink("intent-a")
        coordinator.resolve(ticket, "game")
        assertSame("intent-a", coordinator.pendingLink()?.owner)
    }

    @Test
    fun dropPendingLeavesTheCurrentTicketAlone() {
        val coordinator = LaunchCoordinator<String>()
        val ticket = coordinator.beginLink("link")
        coordinator.dropPending()
        assertFalse(coordinator.hasPending())
        assertTrue(coordinator.isCurrent(ticket))
    }

    @Test
    fun cancelAllInvalidatesEveryOutstandingTicket() {
        val coordinator = LaunchCoordinator<String>()
        val launch = coordinator.beginLaunch()
        coordinator.beginLink("link")
        coordinator.cancelAll()
        assertFalse(coordinator.isCurrent(launch))
        assertFalse(coordinator.hasPending())
        assertNull(coordinator.pendingLink())
    }
}
