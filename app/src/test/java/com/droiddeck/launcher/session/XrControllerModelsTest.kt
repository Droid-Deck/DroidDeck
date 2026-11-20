package com.droiddeck.launcher.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XrControllerModelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nativeRoot get() = File(context.filesDir, ".wayland-rt/xr-models")
    private val models get() = File(context.filesDir, "linuxfs/usr/local/lib/droiddeck-vr/droiddeck/resources/rendermodels")
    private val attempt = 9001L

    @Before fun setup() {
        SessionState.sessionId = "model-test"
        SessionState.running = true
        SessionState.stopRequested = false
        SessionState.steamVr = true
        nativeRoot.deleteRecursively()
        models.deleteRecursively()
        File(context.filesDir, "addons").deleteRecursively()
        runtime(true)
    }

    @After fun cleanup() {
        XrControllerModels.end(attempt)
        OpenXrRuntime.sessionEnded(0)
        SessionState.running = false
        SessionState.sessionId = null
        SessionState.steamVr = false
    }

    private fun runtime(compatible: Boolean) {
        OpenXrRuntime.invalidate()
        OpenXrRuntime.probe(context, refresh = true, resolve = {
            OpenXrRuntime.Probe(detected = compatible, androidCreateInstance = compatible, openGles = compatible)
        })
    }

    private fun model(root: File, name: String) {
        File(root, name).apply {
            mkdirs()
            File(this, "$name.json").writeText("""{"components":{"body":{"filename":"body.obj"}}}""")
            File(this, "body.obj").writeText("v 0 0 0\n")
        }
    }

    private fun await(): Boolean {
        val deadline = System.nanoTime() + 5_000_000_000
        return XrControllerModels.await(context) { System.nanoTime() < deadline }
    }

    @Test fun incompatibleRuntimeDoesNotDownloadOrWaitForModels() {
        runtime(false)
        XrControllerModels.begin(context, attempt)
        XrControllerModels.controllers(context, attempt, 3, 3, "", "")
        assertFalse(await())
        assertTrue(XrControllerModels.status.value.names.isEmpty())
        assertFalse(File(context.filesDir, "addons/pico-4").exists())
    }

    @Test fun completeNativeModelsAvoidTheProfilePackDownload() {
        val left = "droiddeck_xr_runtime_${"a".repeat(32)}_left"
        val right = "droiddeck_xr_runtime_${"b".repeat(32)}_right"
        model(nativeRoot, left)
        model(nativeRoot, right)
        XrControllerModels.begin(context, attempt)
        XrControllerModels.controllers(context, attempt, 3, 3, left, right)
        assertTrue(await())
        assertEquals(XrControllerModels.Phase.NATIVE, XrControllerModels.status.value.phase)
        assertEquals(listOf("PICO 4"), XrControllerModels.status.value.names)
        assertFalse(File(context.filesDir, "addons/pico-4").exists())
    }

    @Test fun unknownProfilesNeedAVisibleFallbackAndDoNotGuessAPicoModel() {
        model(models, "droiddeck_hand_left")
        model(models, "droiddeck_hand_right")
        XrControllerModels.begin(context, attempt)
        XrControllerModels.controllers(context, attempt, 0, 0, "", "")
        assertTrue(await())
        assertTrue(XrControllerModels.status.value.names.isEmpty())
        assertFalse(File(context.filesDir, "addons/pico-4").exists())
    }

    @Test fun missingFallbackPreventsEnteringWithInvisibleControllers() {
        XrControllerModels.begin(context, attempt)
        XrControllerModels.controllers(context, attempt, 0, 0, "", "")
        assertFalse(await())
        assertEquals(XrControllerModels.Phase.FAILED, XrControllerModels.status.value.phase)
        assertFalse(XrControllerModels.status.value.complete)
    }

    @Test fun endedOrReplacedSessionsRejectOldControllerCallbacks() {
        XrControllerModels.begin(context, attempt)
        SessionState.sessionId = "replacement"
        XrControllerModels.controllers(context, attempt, 3, 3, "", "")
        assertTrue(XrControllerModels.status.value.names.isEmpty())
        XrControllerModels.end(attempt)
        XrControllerModels.controllers(context, attempt, 4, 4, "", "")
        assertNull(XrControllerModels.status.value.session)
        assertFalse(File(context.filesDir, "addons/pico-4u").exists())
    }
}
