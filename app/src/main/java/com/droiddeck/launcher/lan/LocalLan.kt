package com.droiddeck.launcher.lan

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

data class LanAddress(val address: InetAddress, val prefixLength: Int)

data class LanInterface(
    val name: String,
    val index: Int,
    val mtu: Int,
    val addresses: List<LanAddress>,
)

data class HotspotState(val starting: Boolean = false, val ssid: String? = null, val password: String? = null, val error: Int? = null)

object LocalLan {
    private val selection = MutableStateFlow<String?>(null)
    val selected = selection.asStateFlow()
    internal val hotspotState = MutableStateFlow(HotspotState())
    val hotspot = hotspotState.asStateFlow()
    private var multicast: WifiManager.MulticastLock? = null

    @Synchronized fun select(name: String?, context: Context? = null) {
        multicast?.let { if (it.isHeld) it.release() }
        multicast = null
        if (name != null && context != null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicast = wifi?.createMulticastLock("droiddeck-lan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        selection.value = name
    }

    fun interfaces(): List<LanInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { network ->
            val addresses = network.interfaceAddresses.filter {
                it.address is Inet4Address && usableAddress(it.address as Inet4Address, it.networkPrefixLength.toInt())
            }.map { LanAddress(it.address, it.networkPrefixLength.toInt()) }
            if (!network.isUp || network.isLoopback || network.isPointToPoint || addresses.isEmpty() || network.index <= 1) null
            else LanInterface(network.name, network.index, network.mtu.coerceAtLeast(576), addresses)
        }.sortedBy { it.name }
    } catch (_: SocketException) {
        emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    internal fun usableAddress(address: Inet4Address, prefix: Int) =
        (address.isSiteLocalAddress || address.isLinkLocalAddress) && prefix in 1..32

    internal fun selectedInterface() = selected.value?.let { name -> interfaces().firstOrNull { it.name == name } }
}
