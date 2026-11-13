package com.droiddeck.launcher.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesVitureControlsTest {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: MutableStateFlow<GlassesState>
    private lateinit var settings: MutableStateFlow<GlassesSettings>
    private lateinit var stateBefore: GlassesState
    private lateinit var settingsBefore: GlassesSettings
    private lateinit var device: RecordingDevice
    private val fieldsBefore = mutableMapOf<String, Any?>()

    private fun field(name: String) = Glasses::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("glasses", Context.MODE_PRIVATE).edit().clear().commit()
        for (name in listOf("app", "handler", "model", "link", "sink", "nativeHostTracking", "vrNativeDof")) {
            fieldsBefore[name] = field(name).get(null)
        }
        field("app").set(null, context)
        field("handler").set(null, handler)
        field("model").set(null, VitureProtocol.sdkModel("Beast"))
        field("nativeHostTracking").set(null, null)
        field("vrNativeDof").set(null, null)
        field("sink").set(null, null)
        @Suppress("UNCHECKED_CAST")
        state = field("_state").get(null) as MutableStateFlow<GlassesState>
        @Suppress("UNCHECKED_CAST")
        settings = field("_settings").get(null) as MutableStateFlow<GlassesSettings>
        stateBefore = state.value
        settingsBefore = settings.value
        settings.value = GlassesSettings()
        state.value = GlassesState(connected = true, controllable = true,
            displayId = 2, width = 1920, height = 1200, hz = 60f,
            resolutions = listOf(GlassesResolution(1920, 1080), GlassesResolution(1920, 1200)),
            viture = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE,
                nativeMode = 1, nativeDof = 0, displayMode = 0x34))
        device = RecordingDevice()
        field("link").set(null, device)
        (field("modeState").get(null) as GlassesModeState).manualDisplayAccepted()
    }

    @After fun cleanUp() {
        handler.removeCallbacksAndMessages(null)
        (field("modeState").get(null) as GlassesModeState).manualDisplayAccepted()
        for ((name, value) in fieldsBefore) field(name).set(null, value)
        state.value = stateBefore
        settings.value = settingsBefore
    }

    @Test fun failedTrackingPausePublishesStoppedStateAndRestartsHostTracking() {
        prepareFailedPause()

        Glasses.setVitureControl(VitureSdk.CONTROL_NATIVE_DOF, 1f)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(false, true), device.trackingRequests)
        assertFalse(device.trackingDuringRead!!)
        assertFalse(device.trackingBeforeRestart!!)
        assertTrue(device.trackingEnabled)
        assertTrue(state.value.tracking)
        assertFalse(state.value.viture!!.busy)
        assertNotNull(state.value.viture!!.error)
        assertEquals(0, state.value.viture!!.nativeDof)
        assertTrue(device.controls.isEmpty())
    }

    @Test fun failedRestartKeepsTrackingStoppedAndAllowsALaterRetry() {
        prepareFailedPause()
        device.failStart = true

        Glasses.setVitureControl(VitureSdk.CONTROL_NATIVE_DOF, 1f)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(false, true), device.trackingRequests)
        assertFalse(device.trackingEnabled)
        assertFalse(state.value.tracking)
        val error = state.value.viture!!.error
        assertNotNull(error)
        assertFalse(state.value.viture!!.busy)
        assertTrue(device.controls.isEmpty())

        device.failStart = false
        Glasses.setHeadTracking(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(false, true, true), device.trackingRequests)
        assertTrue(device.trackingEnabled)
        assertTrue(state.value.tracking)
        assertEquals(error, state.value.viture!!.error)
    }

    @Test fun nativeRefreshPreservesAdvancedResolutionOverSavedProfiles() {
        for (vr in listOf(false, true)) {
            state.value = state.value.copy(vrActive = vr, viture = state.value.viture!!.copy(displayMode = 0x34))
            settings.value = settings.value.copy(resolution2d = "1920x1080", resolutionVr = "1920x1080")
            device.controls.clear()

            Glasses.setRefreshHz(120)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(VitureSdk.CONTROL_NATIVE_DISPLAY to 0x36.toFloat()), device.controls)
            assertEquals(0x36, state.value.viture!!.displayMode)
            assertEquals("1920x1080", settings.value.resolution2d)
            assertEquals("1920x1080", settings.value.resolutionVr)
        }
    }

    @Test fun refreshPreservesUltrawideShape() {
        state.value = state.value.copy(width = 3840, viture = state.value.viture!!.copy(displayMode = 0x40))
        settings.value = settings.value.copy(resolution2d = "1920x1080")

        Glasses.setRefreshHz(60)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(VitureSdk.CONTROL_NATIVE_DISPLAY to 0x40.toFloat()), device.controls)
        assertTrue(state.value.viture!!.ultrawide)
    }

    @Test fun stereoRefreshPreservesBypassResolution() {
        state.value = state.value.copy(width = 3840, sbs = true,
            viture = state.value.viture!!.copy(nativeMode = 0, displayMode = 0x45))
        settings.value = settings.value.copy(resolution3d = "1920x1080")

        Glasses.setSbsHz(90)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(0x45), device.displays)
        assertEquals(0x45, state.value.viture!!.displayMode)
        assertTrue(device.controls.isEmpty())
    }

    @Test fun explicitVrResolutionStillChangesTheAdvancedDisplayShape() {
        state.value = state.value.copy(vrActive = true)
        settings.value = settings.value.copy(resolutionVr = "1920x1200")

        Glasses.setResolution(GlassesDisplayProfile.VR, "1920x1080")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(VitureSdk.CONTROL_NATIVE_DISPLAY to 0x33.toFloat()), device.controls)
        assertEquals(0x33, state.value.viture!!.displayMode)
        assertEquals("1920x1080", settings.value.resolutionVr)
    }

    @Test fun delayedInitializationRetriesUnknownNativeStateBeforeStartingTracking() {
        prepareUnknownNativeMode()
        finishInitialization()
        assertFalse(state.value.viture!!.nativeTrackingKnown)
        assertTrue(device.trackingRequests.isEmpty())

        device.nativeMode = 1f
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertEquals(2, device.stateReads)
        assertTrue(state.value.viture!!.nativeTrackingKnown)
        assertEquals(listOf(true), device.trackingRequests)
        assertTrue(state.value.tracking)
    }

    @Test fun unavailableNativeStateRetriesAreBoundedAndLeaveTrackingOff() {
        prepareUnknownNativeMode()
        finishInitialization()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(3, device.stateReads)
        assertFalse(state.value.viture!!.nativeTrackingKnown)
        assertTrue(device.trackingRequests.isEmpty())
        assertFalse(state.value.tracking)
    }

    @Test fun delayedInitializationDoesNotReadOrStartADetachedLink() {
        prepareUnknownNativeMode()
        finishInitialization()
        field("link").set(null, null)
        device.nativeMode = 1f
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(1, device.stateReads)
        assertTrue(device.trackingRequests.isEmpty())
        assertFalse(state.value.tracking)
    }

    @Test fun delayedNativeDisplayReadCorrectsStereoClassification() {
        for (code in listOf(0x37, 0x40)) {
            prepareUnknownNativeMode()
            state.value = state.value.copy(width = 3840, sbs = true)
            device.nativeDisplay = code
            finishInitialization()
            assertFalse(state.value.sbs)

            device.nativeMode = 1f
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(code == 0x40, state.value.viture!!.ultrawide)
            assertEquals(code == 0x37, state.value.sbs)
        }
    }

    @Test fun wideOutputRequiresKnownNativeStereoLayout() {
        val unknown = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE)
        assertFalse(Glasses.isStereoDisplay(3840, unknown))
        assertFalse(Glasses.isStereoDisplay(3840, unknown.copy(nativeMode = 1)))
        assertFalse(Glasses.isStereoDisplay(3840, unknown.copy(nativeMode = 1, displayMode = 0x40)))
        assertFalse(Glasses.isStereoDisplay(3840, unknown.copy(nativeMode = 1, displayMode = 0x34)))
        assertTrue(Glasses.isStereoDisplay(3840, unknown.copy(nativeMode = 1, displayMode = 0x37)))
        assertTrue(Glasses.isStereoDisplay(3840, unknown.copy(nativeMode = 0, displayMode = 0x45)))
        assertFalse(Glasses.isStereoDisplay(1920, unknown.copy(nativeMode = 1, displayMode = 0x37)))
        assertTrue(Glasses.isStereoDisplay(3840, null))
        assertTrue(Glasses.isStereoDisplay(3840, VitureDeviceState(displayMode = 0x42)))
    }

    @Test fun delayedDisplayReadRetriesAfterTrackingStateIsKnown() {
        prepareUnknownNativeMode()
        state.value = state.value.copy(width = 3840)
        device.nativeMode = 1f
        device.nativeDisplay = -1
        finishInitialization()
        assertTrue(state.value.viture!!.nativeTrackingKnown)
        assertFalse(state.value.sbs)

        device.nativeDisplay = 0x37
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertEquals(2, device.stateReads)
        assertTrue(state.value.sbs)
    }

    private fun prepareUnknownNativeMode() {
        state.value = state.value.copy(viture = VitureDeviceState(capabilities = VitureSdk.CAP_NATIVE))
        device.nativeMode = -1f
        field("sink").set(null, Glasses.PoseSink { _, _, _, _, _ -> })
    }

    private fun finishInitialization() {
        Glasses::class.java.getDeclaredMethod("finishLinkInitialization", GlassesLink::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(Glasses, device, 3)
    }

    private fun prepareFailedPause() {
        state.value = state.value.copy(tracking = true)
        device.trackingEnabled = true
        device.failStop = true
        field("sink").set(null, Glasses.PoseSink { _, _, _, _, _ -> })
    }

    private class RecordingDevice : VitureDeviceLink {
        override val hasImu = true
        override var trackingEnabled = false
        var failStop = false
        var failStart = false
        var nativeMode = 1f
        var nativeDisplay = 0x34
        var stateReads = 0
        var trackingDuringRead: Boolean? = null
        var trackingBeforeRestart: Boolean? = null
        val trackingRequests = mutableListOf<Boolean>()
        val controls = mutableListOf<Pair<Int, Float>>()
        val displays = mutableListOf<Int>()

        override fun deviceCapabilities(): Int {
            stateReads++
            trackingDuringRead = Glasses.state.value.tracking
            return VitureSdk.CAP_NATIVE
        }

        override fun deviceValue(key: Int): Float = when (key) {
            VitureSdk.VALUE_NATIVE_MODE -> nativeMode
            VitureSdk.VALUE_NATIVE_DOF -> 0f
            VitureSdk.VALUE_NATIVE_DISPLAY -> nativeDisplay.toFloat()
            else -> -4f
        }

        override fun deviceDisplayMode() = 0x41

        override fun deviceControl(command: Int, value: Float, extra: Int): Int {
            controls += command to value
            return 0
        }

        override fun send(msgId: Int, payload: ByteArray): Boolean = when (msgId) {
            VitureProtocol.MSG_IMU -> {
                val enabled = payload[0].toInt() != 0
                trackingRequests += enabled
                if (enabled) trackingBeforeRestart = Glasses.state.value.tracking
                trackingEnabled = enabled && !failStart
                if (enabled) !failStart else !failStop
            }
            VitureProtocol.MSG_DISPLAY -> {
                displays += payload[0].toInt() and 0xff
                true
            }
            else -> true
        }

        override fun close() {}
    }
}
