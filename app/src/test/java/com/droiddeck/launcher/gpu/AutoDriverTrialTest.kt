package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AutoDriverTrialTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("gpu_auto_trial", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setAndroidDriver(context, "new-display")
        SessionPrefs.setLinuxDriver(context, "new-linux")
        SessionPrefs.setGpuAutoInstalled(context, setOf("old-display", "old-linux", "new-display", "new-linux"))
        AutoDriverTrial.begin(context, "banner@r5", "Banners r5", "new-display", "new-linux", "old-display", "old-linux")
    }

    @Test fun aReadySessionKeepsTheNewDriversAndDropsTheOldOnes() {
        AutoDriverTrial.sessionStarting(context, "new-display", "new-linux")
        AutoDriverTrial.sessionReady()
        assertNull(AutoDriverTrial.current(context))
        assertEquals(setOf("new-display", "new-linux"), SessionPrefs.gpuAutoInstalled(context))
        assertEquals("new-linux", SessionPrefs.linuxDriver(context))
    }

    @Test fun startsThatNeverGetReadyRollBackAndMarkTheReleaseBad() {
        AutoDriverTrial.sessionStarting(context, "new-display", "new-linux")
        assertNull(AutoDriverTrial.rollbackDue(context))
        AutoDriverTrial.sessionStarting(context, "new-display", "new-linux")
        val trial = AutoDriverTrial.rollbackDue(context)!!
        AutoDriverTrial.rollBack(context, trial)
        assertEquals("old-display", SessionPrefs.androidDriver(context))
        assertEquals("old-linux", SessionPrefs.linuxDriver(context))
        assertEquals(setOf("old-display", "old-linux"), SessionPrefs.gpuAutoInstalled(context))
        assertTrue("banner@r5" in AutoDriverTrial.bad(context))
        assertNull(AutoDriverTrial.current(context))
    }

    @Test fun rollingBackKeepsADriverTheUserSelectedSince() {
        SessionPrefs.setGpuAutoInstalled(context, setOf("old-display", "old-linux", "new-display", "new-linux", "chosen-linux"))
        SessionPrefs.setLinuxDriver(context, "chosen-linux")
        AutoDriverTrial.rollBack(context, AutoDriverTrial.current(context)!!)
        assertEquals("chosen-linux", SessionPrefs.linuxDriver(context))
        assertTrue("chosen-linux" in SessionPrefs.gpuAutoInstalled(context))
    }

    @Test fun sessionsOnOtherDriversDoNotCountTowardsTheTrial() {
        AutoDriverTrial.sessionStarting(context, "old-display", "new-linux")
        AutoDriverTrial.sessionStarting(context, "old-display", "new-linux")
        AutoDriverTrial.sessionReady()
        assertNull(AutoDriverTrial.rollbackDue(context))
        assertEquals("banner@r5", AutoDriverTrial.current(context)?.release)
    }
}
