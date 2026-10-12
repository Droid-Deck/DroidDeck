package com.droiddeck.launcher.session

import android.content.Context
import android.content.ContextWrapper
import com.droiddeck.launcher.runtime.BwrapSpawner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class QuestExperimentalTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowBuild.setModel("Phone")
        ShadowBuild.setDevice("phone")
    }

    @Test fun otherDevicesAndQuest2KeepTheirSettings() {
        for (model in listOf("Phone", "Quest 2")) {
            ShadowBuild.setModel(model)
            QuestExperimental.initialize(context)
            assertEquals(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.gpuDriverMode(context))
            assertEquals("", SessionPrefs.androidDriver(context))
            assertFalse(SessionPrefs.hdr(context, "steam"))
        }
        assertFalse(QuestExperimental.supported("Quest 2", "hollywood", "Oculus"))
    }

    @Test fun questDefaultsSelectBothCompatibilityPathsAndAreAppliedOnlyOnce() {
        ShadowBuild.setModel("Quest 3")
        val standard = object : ContextWrapper(context) {
            override fun getPackageName() = "com.droiddeck.launcher"
        }
        QuestExperimental.initialize(standard)
        assertEquals(SessionPrefs.GPU_DRIVERS_MANUAL, SessionPrefs.gpuDriverMode(context))
        assertEquals(QuestExperimental.DRIVER, SessionPrefs.androidDriver(context))
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertTrue(SessionPrefs.hdr(context, "steam"))
        assertTrue(context.filesDir.resolve("quest-startup.txt").readText().contains("model=Quest 3"))
        SessionPrefs.setAndroidDriver(context, "chosen-driver")
        SessionPrefs.setHdr(context, "steam", false)
        QuestExperimental.initialize(context)
        assertEquals("chosen-driver", SessionPrefs.androidDriver(context))
        assertFalse(SessionPrefs.hdr(context, "steam"))
    }

    @Test fun quest3sIsRecognizedByModelOrDevice() {
        assertTrue(QuestExperimental.supported("Quest 3S", "unknown", "Oculus"))
        assertTrue(QuestExperimental.supported("unknown", "panther", "Oculus"))
        assertTrue(QuestExperimental.supported("unknown", "eureka", "Meta"))
        assertFalse(QuestExperimental.supported("Pixel 7", "panther", "Google"))
    }

    @Test fun installedPackagesUseSeparateSandboxSockets() {
        val packages = listOf("com.droiddeck.launcher", "com.tencent.ig",
            "com.antutu.benchmark.full", "com.ludashi.benchmark")
        val sockets = packages.map { name ->
            val variant = object : ContextWrapper(context) {
                override fun getPackageName() = name
            }
            BwrapSpawner.socket(variant).also { assertEquals("$name.bwrap", it) }
        }
        assertEquals(packages.size, sockets.toSet().size)
    }
}
