package com.droiddeck.launcher.gpu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.DriverMenus
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import java.io.File
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
class RollbackPersistenceTest {
    private lateinit var activity: DiskActivity
    private lateinit var trialDriver: File
    private val owned = setOf("trial-display", "trial-linux", "old-display", "old-linux")

    @Before fun setup() {
        activity = Robolectric.buildActivity(DiskActivity::class.java).setup().get()
        activity.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        activity.getSharedPreferences("gpu_auto_trial", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setAndroidDriver(activity, "trial-display")
        SessionPrefs.setLinuxDriver(activity, "trial-linux")
        SessionPrefs.setGpuAutoInstalled(activity, owned)
        AutoDriverTrial.begin(activity, "trial@1", "Trial", "trial-display", "trial-linux", "old-display", "old-linux")
        repeat(2) { AutoDriverTrial.displayStarting(activity, "trial-display") }
        assertTrue(SessionPrefs.sessionPrefs(activity).edit().commit())
        trialDriver = File(activity.filesDir, "graphics_driver/trial-display/payload")
        trialDriver.parentFile!!.mkdirs()
        trialDriver.writeText("driver retained until rollback is durable")
        activity.attempts.clear()
    }

    private fun recover() {
        assertTrue(GpuProcessRecovery.restartIfNeeded(activity, Intent(), lost = true))
    }

    private fun assertNoHandoff() {
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun assertDurableRollback() {
        assertEquals("old-display", activity.durable["session"]?.get("androidDriver"))
        assertEquals("old-linux", activity.durable["session"]?.get("linuxDriver"))
        assertNull(activity.durable["gpu_auto_trial"]?.get("release"))
        assertTrue("trial@1" in (activity.durable["gpu_auto_trial"]?.get("bad") as Set<*>))
    }

    @Test fun aFailedTrialStateCommitBlocksTheRecoveryHandoff() {
        activity.fail += "gpu_auto_trial"
        recover()
        assertNoHandoff()
        assertEquals("trial@1", activity.durable["gpu_auto_trial"]?.get("release"))
    }

    @Test fun repeatedFailuresCannotBypassTheGateAfterTheMemoryStateWasCleared() {
        activity.fail += "gpu_auto_trial"
        repeat(3) {
            recover()
            assertNoHandoff()
        }
        assertNull(AutoDriverTrial.current(activity))
        assertEquals("trial@1", activity.durable["gpu_auto_trial"]?.get("release"))
        activity.fail.clear()
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertDurableRollback()
    }

    @Test fun aFailedSelectionFlushRetainsTheTrialAndItsDriverFiles() {
        activity.fail += "session"
        recover()
        assertNoHandoff()
        assertEquals("trial@1", AutoDriverTrial.current(activity)?.release)
        assertEquals(owned, SessionPrefs.gpuAutoInstalled(activity))
        assertTrue(trialDriver.isFile)
        activity.fail.clear()
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertDurableRollback()
    }

    @Test fun theFinalSelectionFlushIsStillRequiredAfterClearingTheTrial() {
        activity.failSessionAfterTrialClear = true
        recover()
        assertNoHandoff()
        activity.failSessionAfterTrialClear = false
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertDurableRollback()
    }

    @Test fun failedTrialStatePersistenceDoesNotPruneInstalledDrivers() {
        activity.fail += "gpu_auto_trial"
        recover()
        assertEquals(owned, SessionPrefs.gpuAutoInstalled(activity))
        assertTrue(trialDriver.isFile)
    }

    @Test fun aSuccessfulRollbackPersistsBothFilesBeforeHandoff() {
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertDurableRollback()
        assertEquals(setOf("old-display", "old-linux"), SessionPrefs.gpuAutoInstalled(activity))
        assertFalse(trialDriver.exists())
    }

    @Test fun retryingAFailedRollbackPreservesADriverTheUserSelected() {
        SessionPrefs.setLinuxDriver(activity, "user-linux")
        activity.fail += "gpu_auto_trial"
        recover()
        assertNoHandoff()
        activity.fail.clear()
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals("user-linux", SessionPrefs.linuxDriver(activity))
        assertEquals("user-linux", activity.durable["session"]?.get("linuxDriver"))
    }

    @Test fun manualRecoveryDoesNotWriteUnrelatedTrialState() {
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        activity.fail += "gpu_auto_trial"
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertFalse("gpu_auto_trial" in activity.attempts)
        assertEquals("trial-display", SessionPrefs.androidDriver(activity))
        assertEquals(owned, SessionPrefs.gpuAutoInstalled(activity))
    }

    @Test fun aHealthyProcessDoesNotWriteOrRestart() {
        activity.fail += setOf("session", "gpu_auto_trial")
        assertFalse(GpuProcessRecovery.restartIfNeeded(activity, Intent(), lost = false))
        assertNoHandoff()
        assertTrue(activity.attempts.isEmpty())
    }

    @Test fun theDriverMenuDoesNotReportAFailedRollbackAsSuccessful() {
        activity.fail += "gpu_auto_trial"
        SessionState.running = false
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.ensureAuto(force = false)
        assertEquals(activity.getString(R.string.game_env_error), menus.autoStatus)
        assertEquals(owned, SessionPrefs.gpuAutoInstalled(activity))
        assertTrue(trialDriver.isFile)
    }

    class DiskActivity : Activity() {
        val fail = mutableSetOf<String>()
        val attempts = mutableListOf<String>()
        val durable = mutableMapOf<String, Map<String, Any?>>()
        var failSessionAfterTrialClear = false

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            if (name !in setOf("session", "gpu_auto_trial")) return preferences
            return object : SharedPreferences by preferences {
                override fun edit(): SharedPreferences.Editor {
                    val editor = preferences.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { editor.putStringSet(key, values); return this }
                        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                        override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                        override fun commit(): Boolean {
                            attempts += name
                            val rejected = name in fail || (name == "session" && failSessionAfterTrialClear && AutoDriverTrial.current(this@DiskActivity) == null)
                            // Android updates memory before reporting a disk-write failure.
                            if (rejected) { editor.apply(); return false }
                            if (!editor.commit()) return false
                            durable[name] = preferences.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
                            return true
                        }
                    }
                }
            }
        }
    }
}
