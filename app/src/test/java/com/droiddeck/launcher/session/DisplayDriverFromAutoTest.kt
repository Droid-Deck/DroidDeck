package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.gpu.TurnipDriver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DisplayDriverFromAutoTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun noDisplayDriverChoiceIsAuto() {
        SessionPrefs.setAndroidDriver(context, TurnipDriver.AUTO)
        for (mode in listOf(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.GPU_DRIVERS_MANUAL, SessionPrefs.GPU_DRIVERS_MIX)) {
            SessionPrefs.setGpuDriverMode(context, mode)
            assertTrue(mode, SessionPrefs.displayDriverFromAuto(context))
        }
    }

    @Test fun aPairDisplayHalfInstalledByAutoIsAuto() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("DD-Turnip_v0.8.0", "DD-Turnip_v0.8.0-linux"))
        SessionPrefs.setAndroidDriver(context, "DD-Turnip_v0.8.0")
        assertTrue(SessionPrefs.displayDriverFromAuto(context))
    }

    @Test fun aDriverTheUserImportedStaysTheirChoiceUnderAuto() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setGpuAutoInstalled(context, setOf("DD-Turnip_v0.8.0"))
        SessionPrefs.setAndroidDriver(context, "user-import")
        assertFalse(SessionPrefs.displayDriverFromAuto(context))
    }

    @Test fun anAutoInstalledDriverPickedByHandStaysTheUsersChoice() {
        SessionPrefs.setGpuAutoInstalled(context, setOf("DD-Turnip_v0.8.0"))
        SessionPrefs.setAndroidDriver(context, "DD-Turnip_v0.8.0")
        for (mode in listOf(SessionPrefs.GPU_DRIVERS_MANUAL, SessionPrefs.GPU_DRIVERS_MIX)) {
            SessionPrefs.setGpuDriverMode(context, mode)
            assertFalse(mode, SessionPrefs.displayDriverFromAuto(context))
        }
    }
}
