package com.droiddeck.launcher.glasses

import android.app.Application
import android.os.HandlerThread
import android.view.Display
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.Cardboard
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.JoyConVr
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesTrackingRoutingTest {
    private lateinit var activity: SessionActivity
    private lateinit var restore: () -> Unit
    private val ultra = GlassesState(present = true, connected = true, controllable = true,
        supportsSpatial = true, displayId = Display.DEFAULT_DISPLAY + 1, width = 1920, height = 1080)
    private val saved = GlassesSettings(positionalTracking = true, handControllers = true)

    @Before fun setUp() {
        val running = SessionState.running
        val steamVr = SessionState.steamVr
        val fields = listOf("context", "publisherAttached").associateWith {
            SpatialTracking::class.java.getDeclaredField(it).apply { isAccessible = true }
        }
        val values = fields.mapValues { it.value.get(null) }
        fields.getValue("context").set(null, null)
        fields.getValue("publisherAttached").set(null, false)
        restore = {
            fields.forEach { (name, field) -> field.set(null, values[name]) }
            SessionState.running = running
            SessionState.steamVr = steamVr
        }
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        ReflectionHelpers.setField(activity, "loading", LoadingState(activity).apply { visible = false })
        ReflectionHelpers.setField(activity, "started", true)
        ReflectionHelpers.setField(activity, "activeCardboard", Cardboard.OFF)
        state("glasses", ultra)
        state("glassesSettings", saved)
        state("steamVrStatus", SteamVr.Status.RUNNING)
        SessionState.running = true
        SessionState.steamVr = true
        SpatialTracking.setOutputUsesGlasses(true)
        SpatialTracking.connect(0, 81, null, true, 1.6f, true)
    }

    @After fun tearDown() {
        SpatialTracking.useLegacyTracking()
        SpatialTracking.disconnect()
        SpatialTracking.setOutputUsesGlasses(true)
        restore()
    }

    @Test fun usbLossKeepsSpatialOwnershipAndShowsRecoveryOnTheGlassesDisplay() {
        SpatialTracking.disconnect()
        state("glasses", ultra.copy(connected = false, supportsSpatial = false))
        updateRouting()

        assertEquals(3, modes())
        assertTrue(SpatialTracking.selected)
        assertEquals(TrackingRecovery.DISCONNECTED, recovery())
    }

    @Test fun phoneHandoffClearsPublishedModesWithoutErasingTheGlassesSelection() {
        for (joyCons in listOf(false, true)) {
            val selected = saved.copy(handControllers = !joyCons, joyConTracking = joyCons)
            state("glassesSettings", selected)
            SpatialTracking.connect(0, if (joyCons) 83 else 82, null, true, 1.6f, !joyCons, joyCons)
            SpatialTracking.disconnect()
            state("glasses", GlassesState())
            updateRouting()

            assertEquals(0, modes())
            assertTrue(SpatialTracking.selected)
            assertEquals(selected, stateValue<GlassesSettings>("glassesSettings"))
            assertNull(recovery())
            SpatialTracking.setControllers(!joyCons, joyCons)
            assertEquals("Later controller updates must not reclaim the phone output", 0, modes())

            SpatialTracking.connect(0, if (joyCons) 85 else 84, null, true, 1.6f, !joyCons, joyCons)
            assertEquals("Reconnecting waits for the output handoff", 0, modes())
            state("glasses", ultra)
            updateRouting()
            assertEquals(if (joyCons) 5 else 3, modes())
        }
    }

    @Test fun endingTheSessionReleasesSpatialInputEvenWhileTheGlassesRemainConnected() {
        ReflectionHelpers.setField(activity, "ended", true)
        updateRouting()

        assertEquals(0, modes())
        assertTrue(SpatialTracking.selected)
        assertEquals(saved, stateValue<GlassesSettings>("glassesSettings"))
        assertNull(recovery())
    }

    @Test @Config(sdk = [31]) fun displayLossReleasesSpatialControllerOverridesWhileUsbRemainsConnected() {
        val previousJoyConVr = ControllerPrefs.joyConVr(activity)
        ControllerPrefs.setJoyConVr(activity, false)
        val joyCon = JoyConVr(activity, File(activity.cacheDir, "routing-joycons")) { _, _ -> }
        ReflectionHelpers.setField(activity, "joyConVr", joyCon)
        val worker = ReflectionHelpers.getField<HandlerThread>(joyCon, "thread")
        fun updateInputs(): Boolean {
            ReflectionHelpers.callInstanceMethod<Void>(activity, "updateJoyConTracking")
            shadowOf(worker.looper).idle()
            return ReflectionHelpers.getField(joyCon, "wanted")
        }
        try {
            for (joyCons in listOf(false, true)) {
                val selected = saved.copy(handControllers = !joyCons, joyConTracking = joyCons)
                state("glassesSettings", selected)
                for (displayLost in listOf(ultra.copy(noSignal = true), ultra.copy(displayId = Display.INVALID_DISPLAY))) {
                    state("glasses", ultra)
                    assertTrue(ReflectionHelpers.callInstanceMethod(activity, "usesSpatialControllers"))
                    assertEquals(joyCons, updateInputs())

                    state("glasses", displayLost)
                    updateRouting()
                    assertTrue(displayLost.spatialConnected)
                    assertEquals(0, modes())
                    assertFalse("The phone camera must not be suppressed by a missing display",
                        ReflectionHelpers.callInstanceMethod(activity, "usesSpatialControllers"))
                    assertFalse("Saved glasses Joy-Cons must not override the phone preference", updateInputs())
                    assertEquals(selected, stateValue<GlassesSettings>("glassesSettings"))
                    ControllerPrefs.setJoyConVr(activity, true)
                    assertTrue("The phone Joy-Con preference must still apply", updateInputs())
                    ControllerPrefs.setJoyConVr(activity, false)

                    state("glasses", ultra)
                    updateRouting()
                    assertTrue(ReflectionHelpers.callInstanceMethod(activity, "usesSpatialControllers"))
                    assertEquals(joyCons, updateInputs())
                }
            }
        } finally {
            ReflectionHelpers.setField(activity, "joyConVr", null)
            joyCon.close()
            ControllerPrefs.setJoyConVr(activity, previousJoyConVr)
        }
    }

    @Test fun anotherGlassesModelDoesNotActivateSavedUltraTracking() {
        SpatialTracking.disconnect()
        SpatialTracking.useLegacyTracking()
        state("glasses", ultra.copy(model = "XREAL", supportsSpatial = false))
        updateRouting()

        assertEquals(0, modes())
        assertFalse(SpatialTracking.selected)
        assertEquals(saved, stateValue<GlassesSettings>("glassesSettings"))
        assertNull(recovery())
    }

    private fun updateRouting() {
        ReflectionHelpers.callInstanceMethod<Void>(activity, "updateHeadTracker")
        ReflectionHelpers.callInstanceMethod<Void>(activity, "updateTrackingRecovery")
    }

    private fun modes(): Int {
        val field = SpatialTracking::class.java.getDeclaredField("buffer").apply { isAccessible = true }
        return (field.get(null) as ByteBuffer).duplicate().order(ByteOrder.LITTLE_ENDIAN).getInt(32)
    }

    private fun recovery(): TrackingRecovery? = stateValue("trackingRecovery")

    private fun <T> state(name: String, value: T) {
        ReflectionHelpers.getField<MutableState<T>>(activity, name + "\$delegate").value = value
    }

    private fun <T> stateValue(name: String): T =
        ReflectionHelpers.getField<MutableState<T>>(activity, name + "\$delegate").value
}
