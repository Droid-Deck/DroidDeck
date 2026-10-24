package com.droiddeck.launcher.sync

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

data class SyncthingPorts(val gui: Int, val sync: Int, val discovery: Int)

object SyncthingPortPicker {
    // Syncthing's defaults, plus the alternates Syncthing-Fork's debug build uses.
    internal val RESERVED = setOf(8384, 8385, 22000, 22001, 21027, 21028)
    internal const val GUI = 18384
    internal const val SYNC = 22100
    internal const val DISCOVERY = 21127
    private const val SPAN = 50

    fun pick(): SyncthingPorts = pick(::tcpFree, ::syncFree, ::udpFree)

    internal fun pick(guiFree: (Int) -> Boolean, syncFree: (Int) -> Boolean, discoveryFree: (Int) -> Boolean) =
        SyncthingPorts(first(GUI, guiFree), first(SYNC, syncFree), first(DISCOVERY, discoveryFree))

    private fun first(base: Int, free: (Int) -> Boolean): Int =
        (base until base + SPAN).firstOrNull { it !in RESERVED && free(it) } ?: error("no free port in $base..${base + SPAN - 1}")

    private fun tcpFree(port: Int, address: InetAddress?): Boolean = runCatching {
        ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress(address, port)) }
    }.isSuccess

    private fun tcpFree(port: Int) = tcpFree(port, InetAddress.getLoopbackAddress())
    private fun udpFree(port: Int) = runCatching { DatagramSocket(port).close() }.isSuccess
    private fun syncFree(port: Int) = tcpFree(port, null) && udpFree(port)
}
