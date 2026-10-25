package com.droiddeck.launcher.runtime

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29], manifest = Config.NONE)
class NetworkStateCompatibilityTest {
    @get:Rule val folder = TemporaryFolder()

    private fun publish(properties: LinkProperties?): JSONObject {
        File(folder.root, "etc").mkdirs()
        val component = LinuxNetworkLinkComponent(RuntimeEnvironment.getApplication(), folder.root)
        ReflectionHelpers.callInstanceMethod<Unit>(component, "writeNetworkState",
            from(LinkProperties::class.java, properties), from(NetworkCapabilities::class.java, null))
        return JSONObject(File(folder.root, "etc/droiddeck-network.json").readText())
    }

    @Test fun aDisconnectedNetworkStillPublishesTheDefaultMtu() {
        val state = publish(null)
        assertEquals(1500, state.getInt("mtu"))
        assertFalse(state.getBoolean("connected"))
    }

    @Test fun anUnknownMtuFallsBackToTheDefault() {
        assertEquals(1500, publish(LinkProperties()).getInt("mtu"))
    }

    @Test fun aCustomMtuIsReadOnlyWhenThePublicApiIsAvailable() {
        val properties = LinkProperties()
        ReflectionHelpers.callInstanceMethod<Unit>(properties, "setMtu", from(Int::class.javaPrimitiveType, 1400))
        assertEquals(if (Build.VERSION.SDK_INT >= 29) 1400 else 1500, publish(properties).getInt("mtu"))
    }
}
