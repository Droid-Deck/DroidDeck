package com.droiddeck.launcher.lan

import android.app.Application
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Looper
import com.droiddeck.launcher.runtime.LinuxNetworkLinkComponent
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Files
import java.time.Duration
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalLanTest {
    private lateinit var root: File
    private var lan: LanInterface? = null
    private lateinit var component: LinuxNetworkLinkComponent
    private val host = LanInterface("ap0", 23, 1400, listOf(LanAddress(InetAddress.getByName("192.168.73.1"), 24)))

    @Before fun prepare() {
        root = Files.createTempDirectory("lan-test").toFile()
        lan = host
        LocalLan.select(null)
        component = LinuxNetworkLinkComponent(RuntimeEnvironment.getApplication(), root) { lan }
    }

    @After fun cleanUp() {
        component.stop()
        LocalLan.select(null)
        root.deleteRecursively()
    }

    private fun state() = JSONObject(File(root, "etc/droiddeck-network.json").readText())
    private fun link() = File(root, "etc/droiddeck-net").readText()

    @Test fun selectedHotspotPublishesRealAddressAndKernelIndexWithoutPretendingInternetIsAvailable() {
        LocalLan.select(host.name)
        component.publish()
        assertTrue(link().startsWith("if ap0 23 1400\n"))
        assertTrue(link().contains("selected 1\n"))
        assertTrue(link().contains("addr 192.168.73.1 24\n"))
        assertFalse(link().contains("10.0.0.2"))
        assertFalse(link().contains("gw "))
        assertTrue(state().getBoolean("connected"))
        assertTrue(state().getBoolean("localLan"))
        assertFalse(state().getBoolean("validated"))
        assertEquals("ap0", state().getString("interface"))
        assertEquals(0, state().getJSONArray("gateways").length())
    }

    @Test fun disconnectClearsSelectedInterfaceAndNeverInventsAnAddress() {
        LocalLan.select(host.name)
        component.start()
        shadowOf(Looper.getMainLooper()).idle()
        lan = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals("", link())
        assertFalse(state().getBoolean("connected"))
        assertEquals(0, state().getJSONArray("addresses").length())
        assertEquals(host.name, LocalLan.selected.value)
        lan = host.copy(addresses = listOf(LanAddress(InetAddress.getByName("192.168.74.1"), 24)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(link().contains("addr 192.168.74.1 24\n"))
    }

    @Test fun selectingALanHoldsMulticastUntilResetAndReleasesThePreviousLock() {
        val context = RuntimeEnvironment.getApplication()
        val field = LocalLan.javaClass.getDeclaredField("multicast").apply { isAccessible = true }
        LocalLan.select(host.name, context)
        val first = field.get(LocalLan) as WifiManager.MulticastLock
        assertTrue(first.isHeld)
        LocalLan.select("usb0", context)
        assertFalse(first.isHeld)
        val second = field.get(LocalLan) as WifiManager.MulticastLock
        assertTrue(second.isHeld)
        LocalLan.select(null)
        assertFalse(second.isHeld)
    }

    @Test fun returningToDefaultRestoresExistingOfflineBehavior() {
        LocalLan.select(host.name)
        component.publish()
        LocalLan.select(null)
        component.publish()
        assertTrue(link().startsWith("if eth0 2 1500\n"))
        assertTrue(link().contains("addr 10.0.0.2 24\n"))
        assertFalse(link().contains("selected 1"))
        assertFalse(state().getBoolean("localLan"))
    }

    @Test fun selectedLanDoesNotInheritDefaultNetworkAddressesOrValidation() {
        LocalLan.select(host.name)
        val properties = LinkProperties()
        val caps = NetworkCapabilities()
        component.javaClass.getDeclaredMethod("write", LinkProperties::class.java, NetworkCapabilities::class.java).apply {
            isAccessible = true
        }.invoke(component, properties, caps)
        assertEquals("192.168.73.1", state().getJSONArray("addresses").getJSONObject(0).getString("address"))
        assertFalse(state().getBoolean("validated"))
        assertEquals(0, state().getJSONArray("dns").length())
    }

    @Test fun lanCandidatesExcludePublicLoopbackAndInvalidPrefixes() {
        fun usable(ip: String, prefix: Int = 24) = LocalLan.usableAddress(InetAddress.getByName(ip) as Inet4Address, prefix)
        assertTrue(usable("192.168.43.1"))
        assertTrue(usable("10.15.0.1"))
        assertTrue(usable("172.20.0.1"))
        assertTrue(usable("169.254.2.3", 16))
        assertFalse(usable("127.0.0.1"))
        assertFalse(usable("8.8.8.8"))
        assertFalse(usable("192.168.43.1", 0))
        assertFalse(usable("192.168.43.1", 33))
    }
}
