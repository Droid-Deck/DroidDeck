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
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

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

    private fun preferenceFile(name: String): File {
        ReflectionHelpers.callStaticMethod<Any?>(Class.forName("android.app.QueuedWork"), "waitToFinish")
        return File(activity.applicationInfo.dataDir, "shared_prefs/$name.xml")
    }

    private fun diskString(name: String, key: String): String? {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(preferenceFile(name)).getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == key }?.textContent
    }

    private fun diskSet(name: String, key: String): Set<String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(preferenceFile(name)).getElementsByTagName("set")
        val node = (0 until nodes.length).map { nodes.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == key }
        return (0 until node.childNodes.length).map { node.childNodes.item(it) }
            .filter { it.nodeName == "string" }.map { it.textContent }.toSet()
    }

    @Test fun aRejectedTrialWriteChangesMemoryButLeavesTheActualFileUnchanged() {
        val before = preferenceFile("gpu_auto_trial").readBytes()
        activity.fail += "gpu_auto_trial"
        recover()
        assertNoHandoff()
        assertNull(AutoDriverTrial.current(activity))
        assertArrayEquals(before, preferenceFile("gpu_auto_trial").readBytes())
        assertEquals("trial@1", diskString("gpu_auto_trial", "release"))
    }

    @Test fun rejectedDriverSelectionsLeaveTheActualSessionFileUnchanged() {
        val before = preferenceFile("session").readBytes()
        activity.fail += "session"
        recover()
        assertNoHandoff()
        assertEquals("old-display", SessionPrefs.androidDriver(activity))
        assertArrayEquals(before, preferenceFile("session").readBytes())
        assertEquals("trial-display", diskString("session", "androidDriver"))
    }

    private fun assertDurableRollback() {
        assertEquals("old-display", diskString("session", "androidDriver"))
        assertEquals("old-linux", diskString("session", "linuxDriver"))
        assertNull(diskString("gpu_auto_trial", "release"))
        assertTrue("trial@1" in diskSet("gpu_auto_trial", "bad"))
    }

    @Test fun aFailedTrialStateCommitBlocksTheRecoveryHandoff() {
        activity.fail += "gpu_auto_trial"
        recover()
        assertNoHandoff()
        assertEquals("trial@1", diskString("gpu_auto_trial", "release"))
    }

    @Test fun repeatedFailuresCannotBypassTheGateAfterTheMemoryStateWasCleared() {
        activity.fail += "gpu_auto_trial"
        repeat(3) {
            recover()
            assertNoHandoff()
        }
        assertNull(AutoDriverTrial.current(activity))
        assertEquals("trial@1", diskString("gpu_auto_trial", "release"))
        activity.fail.clear()
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertDurableRollback()
    }

    @Test fun reloadingAfterARejectedWriteRestoresThePersistedTrialBeforeRetrying() {
        activity.fail += "gpu_auto_trial"
        recover()
        assertNoHandoff()
        assertNull(AutoDriverTrial.current(activity))
        assertEquals("trial@1", diskString("gpu_auto_trial", "release"))
        activity = Robolectric.buildActivity(DiskActivity::class.java).setup().get()
        assertEquals("trial@1", AutoDriverTrial.rollbackDue(activity)?.release)
        recover()
        assertNotNull(shadowOf(activity).nextStartedActivity)
        assertDurableRollback()
        assertFalse(trialDriver.exists())
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
        assertEquals("user-linux", diskString("session", "linuxDriver"))
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
        var failSessionAfterTrialClear = false
        private val files = mutableMapOf<String, SharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val preferences = super.getSharedPreferences(name, mode)
            if (name !in setOf("session", "gpu_auto_trial")) return preferences
            return files.getOrPut(name) {
                DiskPreferences(preferences,
                    rejected = { name in fail || (name == "session" && failSessionAfterTrialClear && AutoDriverTrial.current(this) == null) },
                    committing = { attempts += name })
            }
        }
    }

    private class DiskPreferences(
        private val disk: SharedPreferences,
        private val rejected: () -> Boolean,
        private val committing: () -> Unit,
    ) : SharedPreferences by disk {
        // Keep failed writes visible in memory without scheduling any write to the real file.
        private val memory = disk.all.mapValues { (_, value) -> copy(value) }.toMutableMap()

        override fun getAll(): MutableMap<String, *> = memory.mapValues { (_, value) -> copy(value) }.toMutableMap()
        override fun contains(key: String?): Boolean = memory.containsKey(key)
        override fun getString(key: String?, defValue: String?): String? = memory[key] as String? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (memory[key] as Set<*>?)?.map { it as String }?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = memory[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = memory[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = memory[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = memory[key] as Boolean? ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clear = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                changes[requireNotNull(key)] = copy(value)
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear(): SharedPreferences.Editor { clear = true; return this }

            private fun save(): Boolean {
                if (clear) memory.clear()
                for ((key, value) in changes) {
                    if (value == null) memory.remove(key) else memory[key] = value
                }
                changes.clear()
                clear = false
                if (rejected()) return false
                val editor = disk.edit().clear()
                for ((key, value) in memory) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        else -> error("Unsupported preference value: $value")
                    }
                }
                return editor.commit()
            }

            override fun commit(): Boolean { committing(); return save() }
            override fun apply() { save() }
        }

        companion object {
            private fun copy(value: Any?): Any? = if (value is Set<*>) value.toSet() else value
        }
    }
}
