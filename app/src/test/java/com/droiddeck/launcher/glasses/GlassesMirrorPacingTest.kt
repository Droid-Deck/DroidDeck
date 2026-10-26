package com.droiddeck.launcher.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplay
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], manifest = Config.NONE, shadows = [GlassesMirrorPacingTest.ModeDisplay::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesMirrorPacingTest {
    private lateinit var activity: SessionActivity
    private val connected = GlassesState(present = true, displayId = 2, width = 1920, height = 1080)
    private val settings = GlassesSettings(gameOnGlasses = false, refreshHz = 60)

    @Before fun setup() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        ReflectionHelpers.setField(activity, "mBase", activity.baseContext.createDisplayContext(display))
        val panel = Shadow.extract<ModeDisplay>(activity.display)
        panel.supported = arrayOf(mode(1, 60f), mode(2, 90f), mode(3, 120f))
        panel.current = panel.supported.last()
        SessionState.performanceMode = false
        activity.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() {
        SessionState.performanceMode = false
        activity.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun connectingChangingRateAndDisconnectingUpdatePhonePacingWithoutSavingPreferences() {
        apply(connected, settings)
        expect(1, 60f, 60)
        apply(connected, settings.copy(refreshHz = 90))
        expect(2, 90f, 90)
        apply(GlassesState(), settings)
        expect(3, 120f, 0)
    }

    @Test fun retiredSavedLimitsDoNotCapThePhoneOrMirroredOutput() {
        activity.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
            .putInt("fpsLimit.steam", 30).putInt("fpsLimit.desktop", 40).commit()
        apply(connected, settings)
        expect(1, 60f, 60)
        apply(connected.copy(hz = 120f), settings)
        expect(1, 60f, 60)
        apply(GlassesState(), settings)
        expect(3, 120f, 0)
    }

    @Test fun performanceModeResumesUnpacedOutputAfterMirrorDisconnects() {
        SessionState.performanceMode = true
        apply(connected, settings)
        expect(1, 60f, 60)
        apply(GlassesState(), settings)
        expect(3, 120f, SessionPrefs.FPS_UNPACED)
    }

    @Test fun pacingFollowsThePanelWhenTheSystemKeepsALowerRateThanRequested() {
        Shadow.extract<ModeDisplay>(activity.display).current = mode(2, 90f)
        apply(GlassesState(), settings)
        expect(3, 90f, 0)
    }

    private fun apply(state: GlassesState, config: GlassesSettings) {
        ReflectionHelpers.getField<MutableState<GlassesState>>(activity, "glasses\$delegate").value = state
        ReflectionHelpers.getField<MutableState<GlassesSettings>>(activity, "glassesSettings\$delegate").value = config
        ReflectionHelpers.callInstanceMethod<Void>(activity, "applyOutputPacing")
    }

    private fun expect(mode: Int, hz: Float, fps: Int) {
        assertEquals(mode, activity.window.attributes.preferredDisplayModeId)
        assertEquals(hz, ReflectionHelpers.callInstanceMethod<Float>(activity, "refreshHz"), 0.01f)
        assertEquals(fps, ReflectionHelpers.callInstanceMethod<Int>(activity, "outputFpsLimit").toInt())
    }

    private fun mode(id: Int, hz: Float): Display.Mode = ShadowDisplayManager.ModeBuilder.modeBuilder(id)
        .setWidth(1080).setHeight(2520).setRefreshRate(hz).build()

    @Implements(Display::class)
    class ModeDisplay : ShadowDisplay() {
        lateinit var current: Display.Mode
        var supported = emptyArray<Display.Mode>()
        @Implementation protected fun getMode(): Display.Mode = current
        @Implementation protected fun getSupportedModes(): Array<Display.Mode> = supported
        @Implementation override fun getRefreshRate(): Float = current.refreshRate
    }
}
