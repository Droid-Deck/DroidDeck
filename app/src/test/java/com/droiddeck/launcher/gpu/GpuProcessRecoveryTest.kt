package com.droiddeck.launcher.gpu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 33], manifest = Config.NONE)
class GpuProcessRecoveryTest {
    private lateinit var activity: Activity

    @Before fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        activity.getSharedPreferences("gpu_auto_trial", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun trial(starts: Int) {
        SessionPrefs.setAndroidDriver(activity, "trial-display")
        SessionPrefs.setLinuxDriver(activity, "trial-linux")
        AutoDriverTrial.begin(activity, "trial@1", "Trial", "trial-display", "trial-linux", "old-display", "old-linux")
        repeat(starts) { AutoDriverTrial.displayStarting(activity, "trial-display") }
    }

    @Test fun aHealthyProcessDoesNotRestartOrRollBack() {
        trial(2)
        assertFalse(GpuProcessRecovery.restartIfNeeded(activity, Intent(), lost = false))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("trial-display", SessionPrefs.androidDriver(activity))
    }

    @Test fun lossUsesThePrivateRecoveryActivityAndPreservesTheRequest() {
        val request = Intent(activity, SessionActivity::class.java)
            .setAction("test-start")
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
            .putExtra(SessionService.EXTRA_PROGRAM, "/usr/bin/vkcube")
            .putExtra(SessionService.EXTRA_PROGRAM_ARGS, arrayOf("--wsi", "xcb"))
            .putExtra(SessionActivity.EXTRA_RETURN_HOME, true)
            .putExtra(SessionActivity.EXTRA_LINK_TASK, 72)
        assertTrue(GpuProcessRecovery.restartIfNeeded(activity, request, lost = true))
        val recovery = shadowOf(activity).nextStartedActivity
        assertEquals(SteamVrRestartActivity::class.java.name, recovery.component?.className)
        val restarted = GpuProcessRecovery.sessionRequest(activity, recovery)
        assertEquals(SessionActivity::class.java.name, restarted.component?.className)
        assertEquals("test-start", restarted.action)
        assertEquals(SessionService.MODE_RUN, restarted.getStringExtra(SessionService.EXTRA_MODE))
        assertEquals("/usr/bin/vkcube", restarted.getStringExtra(SessionService.EXTRA_PROGRAM))
        assertArrayEquals(arrayOf("--wsi", "xcb"), restarted.getStringArrayExtra(SessionService.EXTRA_PROGRAM_ARGS))
        assertTrue(restarted.getBooleanExtra(SessionActivity.EXTRA_RETURN_HOME, false))
        assertEquals(72, restarted.getIntExtra(SessionActivity.EXTRA_LINK_TASK, -1))
        assertFalse(restarted.getBooleanExtra(SessionActivity.EXTRA_STEAMVR, false))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK, restarted.flags)
        assertEquals(0, request.flags)
    }

    @Test fun legacySteamVrRecoveryStillStartsSteamVr() {
        val request = GpuProcessRecovery.sessionRequest(activity, Intent())
        assertEquals(SessionService.MODE_STEAM, request.getStringExtra(SessionService.EXTRA_MODE))
        assertTrue(request.getBooleanExtra(SessionActivity.EXTRA_STEAMVR, false))
    }

    @Test fun aFirstUnreadyStartDoesNotPrematurelyRollBack() {
        trial(1)
        GpuProcessRecovery.restartIfNeeded(activity, Intent(), lost = true)
        assertEquals(1, AutoDriverTrial.current(activity)?.starts)
        assertEquals("trial-display", SessionPrefs.androidDriver(activity))
        assertTrue(AutoDriverTrial.bad(activity).isEmpty())
    }

    @Test fun twoUnreadyStartsKeepTheExistingRollbackThreshold() {
        trial(2)
        GpuProcessRecovery.restartIfNeeded(activity, Intent(), lost = true)
        assertEquals("old-display", SessionPrefs.androidDriver(activity))
        assertEquals("old-linux", SessionPrefs.linuxDriver(activity))
        assertNull(AutoDriverTrial.current(activity))
        assertEquals(setOf("trial@1"), AutoDriverTrial.bad(activity))
    }

    @Test fun rollbackPreservesADriverTheUserSelectedSince() {
        trial(2)
        SessionPrefs.setLinuxDriver(activity, "user-linux")
        GpuProcessRecovery.restartIfNeeded(activity, Intent(), lost = true)
        assertEquals("old-display", SessionPrefs.androidDriver(activity))
        assertEquals("user-linux", SessionPrefs.linuxDriver(activity))
    }

    @Test fun manualModeDoesNotRollBackDrivers() {
        trial(2)
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        GpuProcessRecovery.restartIfNeeded(activity, Intent(), lost = true)
        assertEquals("trial-display", SessionPrefs.androidDriver(activity))
        assertEquals("trial-linux", SessionPrefs.linuxDriver(activity))
        assertNotNull(AutoDriverTrial.current(activity))
    }

    @Test fun pendingPreferencesAreCommittedBeforeHandingOffToTheKillingProcess() {
        val recording = Robolectric.buildActivity(PersistenceActivity::class.java).setup().get()
        GpuProcessRecovery.restartIfNeeded(recording, Intent(), lost = true)
        assertEquals(1, recording.commits)
        assertNotNull(shadowOf(recording).nextStartedActivity)
    }

    @Test fun aFailedPreferenceFlushDoesNotHandOffToTheKillingProcess() {
        val recording = Robolectric.buildActivity(PersistenceActivity::class.java).setup().get()
        recording.persist = false
        assertTrue(GpuProcessRecovery.restartIfNeeded(recording, Intent(), lost = true))
        assertEquals(1, recording.commits)
        assertNull(shadowOf(recording).nextStartedActivity)
    }

    class PersistenceActivity : Activity() {
        var persist = true
        var commits = 0
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            if (name != "session") return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun commit(): Boolean {
                            commits++
                            return persist && editor.commit()
                        }
                    }
                }
            }
        }
    }
}
