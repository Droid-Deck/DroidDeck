package com.droiddeck.launcher.sync

import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket

data class SyncthingPorts(val sync: Int, val discovery: Int)

object SyncthingPortPicker {
    // Syncthing's defaults, plus the alternates Syncthing-Fork's debug build uses.
    internal val RESERVED = setOf(8384, 8385, 22000, 22001, 21027, 21028)
    internal const val SYNC = 22100
    internal const val DISCOVERY = 21127
    private const val SPAN = 50

    fun pick(): SyncthingPorts = pick(::syncFree, ::udpFree)

    internal fun pick(syncFree: (Int) -> Boolean, discoveryFree: (Int) -> Boolean) =
        SyncthingPorts(first(SYNC, syncFree), first(DISCOVERY, discoveryFree))

    private fun first(base: Int, free: (Int) -> Boolean): Int =
        (base until base + SPAN).firstOrNull { it !in RESERVED && free(it) } ?: error("no free port in $base..${base + SPAN - 1}")

    private fun tcpFree(port: Int): Boolean = runCatching {
        ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress(port)) }
    }.isSuccess

    private fun udpFree(port: Int) = runCatching { DatagramSocket(port).close() }.isSuccess
    private fun syncFree(port: Int) = tcpFree(port) && udpFree(port)
}
