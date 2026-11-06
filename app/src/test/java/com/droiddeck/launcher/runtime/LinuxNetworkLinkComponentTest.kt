package com.droiddeck.launcher.runtime

import android.content.Context
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.RouteInfo
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE)
class LinuxNetworkLinkComponentTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun capabilities(vpn: Boolean = true, internet: Boolean = true) = NetworkCapabilities().apply {
        ReflectionHelpers.callInstanceMethod<NetworkCapabilities>(this, "addTransportType",
            from(Int::class.javaPrimitiveType, if (vpn) NetworkCapabilities.TRANSPORT_VPN else NetworkCapabilities.TRANSPORT_WIFI))
        if (internet) ReflectionHelpers.callInstanceMethod<NetworkCapabilities>(this, "addCapability",
            from(Int::class.javaPrimitiveType, NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }

    private fun properties(vararg addresses: String) = LinkProperties().apply {
        interfaceName = "tun0"
        addresses.forEach {
            val address = ReflectionHelpers.callConstructor<LinkAddress>(LinkAddress::class.java,
                from(InetAddress::class.java, InetAddress.getByName(it.substringBefore('/'))),
                from(Int::class.javaPrimitiveType, it.substringAfter('/').toInt()))
            ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress", from(LinkAddress::class.java, address))
        }
    }

    private fun LinkProperties.route(destination: String, prefix: Int, gateway: String = "0.0.0.0",
                                     type: Int = RouteInfo.RTN_UNICAST) = apply {
        addRoute(ReflectionHelpers.callConstructor<RouteInfo>(RouteInfo::class.java,
            from(IpPrefix::class.java, IpPrefix(InetAddress.getByName(destination), prefix)),
            from(InetAddress::class.java, InetAddress.getByName(gateway)),
            from(String::class.java, interfaceName),
            from(Int::class.javaPrimitiveType, type)))
    }

    private fun gateways(properties: LinkProperties?, capabilities: NetworkCapabilities?) =
        LinuxNetworkLinkComponent.gateways(properties, capabilities).map { it.hostAddress }

    @Test fun vpnWithOnLinkDefaultRouteHasNonzeroCompatibilityGateway() {
        val link = properties("10.0.0.1/24").route("0.0.0.0", 0)
        assertEquals(listOf("10.0.0.1"), gateways(link, capabilities()))
    }

    @Test fun vpnWithSplitRoutesHasCompatibilityGatewayWithoutDefaultRoute() {
        val link = properties("10.0.0.1/24").route("1.0.0.0", 8).route("128.0.0.0", 1)
        assertEquals(listOf("10.0.0.1"), gateways(link, capabilities()))
        assertTrue(link.routes.none { it.isDefaultRoute })
        assertTrue(link.routes.all { it.gateway!!.isAnyLocalAddress })
    }

    @Test fun realDefaultGatewayIsPreservedEvenForVpn() {
        val link = properties("10.0.0.2/24").route("0.0.0.0", 0, "10.0.0.1")
        assertEquals(listOf("10.0.0.1"), gateways(link, capabilities()))
    }

    @Test fun wifiDefaultGatewayIsUnchanged() {
        val link = properties("192.168.1.2/24").route("0.0.0.0", 0, "192.168.1.1")
        assertEquals(listOf("192.168.1.1"), gateways(link, capabilities(vpn = false)))
    }

    @Test fun nonVpnLinkWithoutGatewayDoesNotBecomeInternetConnected() {
        val link = properties("192.168.1.2/24").route("0.0.0.0", 0)
        assertTrue(gateways(link, capabilities(vpn = false)).isEmpty())
    }

    @Test fun vpnWithoutInternetCapabilityDoesNotGetGateway() {
        val link = properties("10.0.0.1/24").route("0.0.0.0", 0)
        assertTrue(gateways(link, capabilities(internet = false)).isEmpty())
    }

    @Test fun missingNetworkOrCapabilitiesDoesNotGetGateway() {
        val link = properties("10.0.0.1/24").route("0.0.0.0", 0)
        assertTrue(gateways(null, capabilities()).isEmpty())
        assertTrue(gateways(link, null).isEmpty())
    }

    @Test fun vpnWithoutRoutesDoesNotGetGateway() {
        assertTrue(gateways(properties("10.0.0.1/24"), capabilities()).isEmpty())
    }

    @Test fun vpnWithoutIpv4AddressDoesNotGetIpv4Gateway() {
        val link = properties("fd00::2/64").route("0.0.0.0", 0)
        assertTrue(gateways(link, capabilities()).isEmpty())
    }

    @Test fun vpnWithoutIpv4RoutesDoesNotGetIpv4Gateway() {
        val link = properties("10.0.0.1/24").route("::", 0, "::")
        assertTrue(gateways(link, capabilities()).isEmpty())
    }

    @Test fun reconnectingVpnWithRetainedAddressDoesNotGetGateway() {
        val link = properties("10.0.0.1/24").apply { interfaceName = null }
            .route("0.0.0.0", 0, type = RouteInfo.RTN_UNREACHABLE)
            .route("128.0.0.0", 1, type = RouteInfo.RTN_UNREACHABLE)
        assertTrue(gateways(link, capabilities()).isEmpty())
        val description = LinuxNetworkLinkComponent(ApplicationProvider.getApplicationContext<Context>(), temporary.root)
            .describe(link, capabilities())
        assertTrue(description.contains("addr 10.0.0.1 24\n"))
        assertFalse(description.contains("gw "))
    }

    @Config(sdk = [34])
    @Test fun vpnWithoutUnicastIpv4RouteDoesNotGetGateway() {
        val link = properties("10.0.0.1/24")
            .route("0.0.0.0", 0, type = RouteInfo.RTN_UNREACHABLE)
            .route("10.0.0.0", 8, type = RouteInfo.RTN_THROW)
        assertTrue(gateways(link, capabilities()).isEmpty())
    }

    @Test fun unspecifiedLoopbackAndLinkLocalAddressesAreNotGateways() {
        val link = properties("0.0.0.0/32", "127.0.0.1/8", "169.254.1.2/16").route("0.0.0.0", 0)
        assertTrue(gateways(link, capabilities()).isEmpty())
    }

    @Test fun ipv6DefaultGatewayIsPreservedAlongsideVpnIpv4Gateway() {
        val link = properties("10.0.0.1/24", "fd00::2/64")
            .route("0.0.0.0", 0).route("::", 0, "fe80::1")
        assertEquals(listOf(InetAddress.getByName("10.0.0.1"), InetAddress.getByName("fe80::1")),
            LinuxNetworkLinkComponent.gateways(link, capabilities()))
    }

    @Test fun publishedLinkContainsGatewayAndKeepsVpnInterfaceAndAddress() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = LinuxNetworkLinkComponent(context, temporary.root)
        val link = properties("10.0.0.1/24").route("1.0.0.0", 8).route("128.0.0.0", 1)
        val before = ReflectionHelpers.callConstructor<LinkProperties>(LinkProperties::class.java, from(LinkProperties::class.java, link))
        val description = component.describe(link, capabilities())
        assertTrue(description.contains("if tun0 2 1500\n"))
        assertTrue(description.contains("addr 10.0.0.1 24\n"))
        assertTrue(description.contains("gw 10.0.0.1\n"))
        ReflectionHelpers.callInstanceMethod<Unit>(component, "write",
            from(LinkProperties::class.java, link), from(NetworkCapabilities::class.java, capabilities()))
        assertEquals(description, temporary.root.resolve("etc/droiddeck-net").readText())
        val state = org.json.JSONObject(temporary.root.resolve("etc/droiddeck-network.json").readText())
        assertEquals("tun0", state.getString("interface"))
        assertEquals("vpn", state.getString("transport"))
        assertEquals(listOf("10.0.0.1"), (0 until state.getJSONArray("gateways").length()).map {
            state.getJSONArray("gateways").getString(it)
        })
        assertEquals(before, link)
    }
}
