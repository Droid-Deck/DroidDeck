package com.droiddeck.launcher.session

import android.content.Context
import org.robolectric.RuntimeEnvironment
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

    @Test fun adrenoAutoChoicesRemain() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensurePowerVrAutoDrivers(context, powerVr = false)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }
}
