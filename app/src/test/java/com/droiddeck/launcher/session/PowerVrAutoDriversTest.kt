package com.droiddeck.launcher.session

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.TurnipDriver
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PowerVrAutoDriversTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setLinuxDriver(context, "imported-linux")
        SessionPrefs.setAndroidDriver(context, "imported-display")
    }

    @Test fun autoRepairsStaleAdrenoSelectionsOnPowerVr() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun nonXclipsePowerVrKeepsManualPair() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }

    @Test fun unsupportedXclipseClearsAnAutoOwnedOldPair() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setLinuxDriver(context, "old-linux")
        SessionPrefs.setAndroidDriver(context, "old-display")
        SessionPrefs.setGpuAutoInstalled(context, setOf("old-display", "old-linux"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun unsupportedXclipseKeepsAManualPair() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setLinuxDriver(context, "old-linux")
        SessionPrefs.setAndroidDriver(context, "old-display")
        SessionPrefs.setGpuAutoInstalled(context, setOf("old-display", "old-linux"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("old-linux", SessionPrefs.linuxDriver(context))
        assertEquals("old-display", SessionPrefs.androidDriver(context))
    }

    @Test fun autoClearsOwnedDisplayButKeepsManualLinux() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-display"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
    }

    @Test fun autoClearsOwnedLinuxButKeepsManualDisplay() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-linux"))
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = true)
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
    }

    @Test fun adrenoAutoChoicesRemain() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = false)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartKeepsStandaloneDriverOnUnsupportedXclipse() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setAndroidDriver(context, "standalone-display")
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-linux"))
        val ownedOnly = SessionPrefs.unsupportedXclipse(true) { "Samsung Xclipse 540" }
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = ownedOnly)
        assertEquals("standalone-display", SessionPrefs.androidDriver(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
    }

    @Test fun sessionStartOwnershipRuleIgnoresOtherGpus() {
        assertEquals(false, SessionPrefs.unsupportedXclipse(false) { "Samsung Xclipse 540" })
        assertEquals(false, SessionPrefs.unsupportedXclipse(true) { "Samsung Xclipse 920" })
    }

    @Test fun sessionStartNamesTheXclipseFromVulkanBeforeTheOwnershipRule() {
        val queried = mapOf("device" to "Samsung Xclipse 920")
        assertEquals("Xclipse", GpuInfo.systemDriverName("Xclipse", null))
        assertEquals("Samsung Xclipse 920", GpuInfo.systemDriverName("Xclipse", queried))
        assertEquals("PowerVR", GpuInfo.systemDriverName("PowerVR", queried))
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("imported-linux"))
        val ownedOnly = SessionPrefs.unsupportedXclipse(true) { GpuInfo.systemDriverName("Xclipse", queried) }
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = true, autoOwnedOnly = ownedOnly)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun sessionStartLeavesTheGpuUnqueriedOffXclipse() {
        var asked = false
        assertEquals(false, SessionPrefs.unsupportedXclipse(false) { asked = true; "Xclipse" })
        assertEquals(false, asked)
    }
}
