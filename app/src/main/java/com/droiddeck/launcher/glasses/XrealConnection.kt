package com.droiddeck.launcher.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal interface XrealChannels : Closeable {
    val controlInput: InputStream
    val controlOutput: OutputStream
    val reports: InputStream
}

internal interface XrealConnector : Closeable {
    fun connect(): XrealChannels
}

internal class XrealNetworkMissing : IOException("XREAL USB Ethernet network is not available")

/** Bind individual sockets, never the process: Steam must retain its Internet network. */
internal class AndroidXrealConnector(context: Context) : XrealConnector {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val address = InetAddress.getByName(XrealProtocol.HOST)
    private val lock = Any()
    private val sockets = mutableSetOf<Socket>()
    private var closed = false

    companion object {
        internal fun hasDirectRoute(properties: LinkProperties, address: InetAddress): Boolean =
            properties.routes.any { !it.hasGateway() && it.destination.prefixLength > 0 && it.matches(address) }
    }

    override fun connect(): XrealChannels {
        val network = manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true && manager.getLinkProperties(network)?.let { hasDirectRoute(it, address) } == true
        } ?: throw XrealNetworkMissing()
        fun open(port: Int): Socket {
            val socket = Socket()
            synchronized(lock) {
                if (closed) { socket.close(); throw IOException("XREAL connection closed") }
                sockets.add(socket)
            }
            try {
                network.bindSocket(socket)
                socket.soTimeout = 2000
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), 1500)
                return socket
            } catch (e: Exception) {
                release(socket)
                throw e
            }
        }
        val control = open(XrealProtocol.CONTROL_PORT)
        val reportSocket = try { open(XrealProtocol.REPORT_PORT) } catch (e: Exception) {
            release(control)
            throw e
        }
        return object : XrealChannels {
            override val controlInput = control.getInputStream()
            override val controlOutput = control.getOutputStream()
            override val reports = reportSocket.getInputStream()
            override fun close() { release(control); release(reportSocket) }
        }
    }

    private fun release(socket: Socket) = synchronized(lock) {
        runCatching { socket.close() }
        sockets.remove(socket)
        Unit
    }

    override fun close() = synchronized(lock) {
        closed = true
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }
}
