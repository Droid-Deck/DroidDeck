package com.droiddeck.launcher.session

/**
 * Orders launch attempts and at most one pending game link.
 *
 * This is main-thread state: call it from the UI thread. A ticket is an identity,
 * not a counter comparison, so a stale callback can only act on its own request.
 */
internal class LaunchTicket internal constructor(val serial: Long)

internal class LaunchCoordinator<O : Any, R : Any> {
    internal class Link<O : Any, R : Any> internal constructor(
        val ticket: LaunchTicket,
        val owner: O,
        val request: R?,
    )

    private var next = 0L
    private var current: LaunchTicket? = null
    private var pending: Link<O, R>? = null
    private var handling: LaunchTicket? = null

    /** A launch that is allowed to proceed; it supersedes any pending link. */
    fun beginLaunch(): LaunchTicket {
        val ticket = LaunchTicket(++next)
        current = ticket
        pending = null
        handling = null
        return ticket
    }

    /** A game-link intent; it supersedes any earlier launch or link. */
    fun beginLink(owner: O): LaunchTicket {
        val ticket = LaunchTicket(++next)
        current = ticket
        pending = Link(ticket, owner, null)
        handling = null
        return ticket
    }

    /** The asynchronous link read returned; false means a newer request won. */
    fun resolve(ticket: LaunchTicket, request: R?): Boolean {
        val link = pending ?: return false
        if (link.ticket !== ticket) return false
        pending = Link(ticket, link.owner, request)
        return true
    }

    fun beginHandling(ticket: LaunchTicket): Boolean {
        val link = pending ?: return false
        if (link.ticket !== ticket || link.request == null) return false
        handling = ticket
        return true
    }

    fun releaseHandling(ticket: LaunchTicket) {
        if (handling === ticket) handling = null
    }

    fun isHandling(ticket: LaunchTicket): Boolean = handling === ticket
    fun isCurrent(ticket: LaunchTicket): Boolean = current === ticket
    fun hasPending(): Boolean = pending != null
    fun pendingLink(): Link<O, R>? = pending

    fun consume(ticket: LaunchTicket): Boolean {
        val link = pending ?: return false
        if (link.ticket !== ticket) return false
        pending = null
        handling = null
        return true
    }

    /** Drop any waiting link without cancelling an already-running launch ticket. */
    fun dropPending() {
        pending = null
        handling = null
    }

    fun cancelAll() {
        next++
        current = null
        pending = null
        handling = null
    }
}
