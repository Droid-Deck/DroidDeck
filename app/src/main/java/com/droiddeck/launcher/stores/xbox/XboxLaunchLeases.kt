package com.droiddeck.launcher.stores.xbox

import java.io.Closeable
import java.util.UUID

internal class XboxLaunchLeases(private val now: () -> Long) {
    private data class Lease(val id: String, val account: String, val session: String, var expires: Long, var child: Closeable? = null)
    private val leases = HashMap<String, Lease>()

    /** A lease only blocks another launch while it is still renewing; see [begin]. */
    @Synchronized fun active(id: String) = leases.values.any { it.id == id && it.expires > now() }
    fun begin(id: String, account: String, session: String): String {
        val token = UUID.randomUUID().toString()
        val expired = ArrayList<Closeable>()
        synchronized(this) {
            check(!active(id)) { "This Xbox game is already starting or running." }
            // A launch whose renewals stopped leaves its lease behind, and the expiry thread
            // only runs while a lease is active, so nothing else would ever drop it. Reaping it
            // here keeps a dead lease from turning the Play button into a permanent
            // "already starting or running" with no game in sight.
            val dead = leases.filterValues { it.id == id && it.expires <= now() }.keys
            dead.forEach { leases.remove(it)?.child?.let(expired::add) }
            leases[token] = Lease(id, account, session, now() + 180_000)
        }
        expired.forEach { runCatching { it.close() } }
        return token
    }
    @Synchronized fun attach(token: String, child: Closeable): Boolean {
        val lease = leases[token] ?: return false
        lease.child = child
        return true
    }
    @Synchronized fun renew(token: String, id: String, account: String?, session: String?): Boolean {
        val lease = leases[token] ?: return false
        if (lease.id != id || lease.account != account || lease.session != session || lease.expires <= now()) return false
        lease.expires = now() + 60_000
        return true
    }
    @Synchronized fun release(token: String): Closeable? = leases.remove(token)?.child
    @Synchronized fun stale(account: String?, session: String?, suspended: Boolean): List<Closeable> {
        if (suspended) leases.values.filter { it.account == account && it.session == session }
            .forEach { it.expires = maxOf(it.expires, now() + 60_000) }
        val expired = leases.filterValues { it.account != account || it.session != session || (!suspended && it.expires <= now()) }.keys
        return expired.mapNotNull { leases.remove(it)?.child }
    }
    @Synchronized fun clear(): List<Closeable> {
        return leases.values.mapNotNull { it.child }.also { leases.clear() }
    }
}
