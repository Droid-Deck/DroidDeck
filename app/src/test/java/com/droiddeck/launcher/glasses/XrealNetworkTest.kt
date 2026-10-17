package com.droiddeck.launcher.glasses

import android.net.IpPrefix
import android.net.LinkProperties
import android.net.RouteInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class XrealNetworkTest {
    private val target = InetAddress.getByName(XrealProtocol.HOST)
    private fun properties(address: String, prefix: Int, gateway: String? = null) = LinkProperties().apply {
        interfaceName = "eth0"
        val route = ReflectionHelpers.callConstructor<RouteInfo>(RouteInfo::class.java,
            from(IpPrefix::class.java, IpPrefix(InetAddress.getByName(address), prefix)),
            from(InetAddress::class.java, InetAddress.getByName(gateway ?: "0.0.0.0")),
            from(String::class.java, "eth0"))
        addRoute(route)
    }
    @Test fun onlyAnOnLinkGlassesRouteQualifies() {
        val direct = properties("169.254.2.0", 24)
        val default = properties("0.0.0.0", 0)
        val gateway = properties("169.254.2.0", 24, "192.168.1.1")
        val unrelated = properties("192.168.1.0", 24)
        assertTrue(AndroidXrealConnector.hasDirectRoute(direct, target))
        assertFalse(AndroidXrealConnector.hasDirectRoute(default, target))
        assertFalse(AndroidXrealConnector.hasDirectRoute(gateway, target))
        assertFalse(AndroidXrealConnector.hasDirectRoute(unrelated, target))
    }
}
