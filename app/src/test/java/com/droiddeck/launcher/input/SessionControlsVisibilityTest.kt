package com.droiddeck.launcher.input

import android.view.View
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionControlsVisibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val saved get() = context.getSharedPreferences("session", 0)

    @Before fun setUp() {
        saved.edit().clear().commit()
        SessionState.mode = SessionService.MODE_STEAM
    }

    @After fun tearDown() {
        SteamTouchDevice.release()
        SessionState.deckPad = false
        SessionState.mode = SessionService.MODE_STEAM
    }

    @Test fun firstLoginAndLaterSteamFramesStartWithoutControls() {
        val (activity, controls) = session()
        update(activity)
        assertEquals(View.GONE, controls.visibility)
        finishLoading(activity)
        assertEquals(View.GONE, controls.visibility)
        assertEquals(SessionPrefs.OSC_NEVER, SessionPrefs.oscMode(context))
        assertFalse(saved.contains("osc"))
    }

    @Test fun visiblePresetsWaitUntilSteamFinishesStarting() {
        for (mode in listOf(SessionPrefs.OSC_ALWAYS, SessionPrefs.OSC_AUTO, SessionPrefs.OSC_STEAM_QAM)) {
            SessionPrefs.setOscMode(context, mode)
            val (activity, controls) = session()
            update(activity)
            assertEquals(mode, View.GONE, controls.visibility)
            finishLoading(activity)
            assertEquals(mode, View.VISIBLE, controls.visibility)
            assertEquals(mode, SessionPrefs.oscMode(context))
        }
    }

    @Test fun lastShowOrHideChoiceSurvivesNewSessions() {
        for (mode in listOf(SessionPrefs.OSC_ALWAYS, SessionPrefs.OSC_NEVER)) {
            SessionPrefs.setOscMode(context, mode)
            repeat(2) {
                val (activity, controls) = session()
                update(activity)
                assertEquals(View.GONE, controls.visibility)
                finishLoading(activity)
                assertEquals(if (mode == SessionPrefs.OSC_ALWAYS) View.VISIBLE else View.GONE, controls.visibility)
                assertEquals(mode, SessionPrefs.oscMode(context))
            }
        }
    }

    @Test fun hidingDuringStartupIsRememberedWhenSteamAppears() {
        SessionPrefs.setOscMode(context, SessionPrefs.OSC_ALWAYS)
        val (activity, controls) = session()
        update(activity)
        SessionPrefs.setOscMode(context, SessionPrefs.OSC_NEVER)
        finishLoading(activity)
        assertEquals(View.GONE, controls.visibility)
        assertEquals(SessionPrefs.OSC_NEVER, SessionPrefs.oscMode(context))
    }

    @Test fun steamTouchStaysConnectedWhileItsControlsWaitForSteam() {
        val directory = Files.createTempDirectory("startup-controls").toFile()
        try {
            SteamTouchDevice.prepare(directory)
            val (activity, controls) = session()
            val steam = SteamTouchControls(activity, SteamTouchDevice.current!!, {}, {})
            ReflectionHelpers.setField(activity, "steamTouchControls", steam)
            SessionPrefs.setOscMode(context, SessionPrefs.OSC_STEAM_TOUCH)
            update(activity)
            assertEquals(View.GONE, controls.visibility)
            assertEquals(View.GONE, steam.visibility)
            assertTrue(SteamTouchDevice.current!!.plugged)
            finishLoading(activity)
            assertEquals(View.VISIBLE, steam.visibility)
            assertEquals(SessionPrefs.OSC_STEAM_TOUCH, SessionPrefs.oscMode(context))
        } finally {
            SteamTouchDevice.release()
            directory.deleteRecursively()
        }
    }

    @Test fun desktopLoadingKeepsItsSavedControlChoice() {
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionPrefs.setOscMode(context, SessionPrefs.OSC_ALWAYS)
        val (activity, controls) = session()
        update(activity)
        assertEquals(View.VISIBLE, controls.visibility)
    }

    @Test
    @Config(qualifiers = "sw700dp-w1000dp-h800dp")
    fun unfoldedControlsWaitForSteamWithoutChangingTheirPreference() {
        val (activity, _) = session()
        shadowOf(activity.packageManager).setSystemFeature("android.hardware.sensor.hinge_angle", true)
        val surface = SurfaceView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val root = FrameLayout(activity).apply { addView(surface); layout(0, 0, 1000, 800) }
        ReflectionHelpers.setField(activity, "surfaceView", surface)
        ReflectionHelpers.setField(activity, "sessionRoot", root)
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "unfoldedControls\$delegate").value = true
        SessionPrefs.setUnfoldedControls(context, true)
        SessionState.deckPad = true
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshControls")
        assertNull(ReflectionHelpers.getField<View?>(activity, "unfoldedPanel"))
        assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, surface.layoutParams.height)
        finishLoading(activity)
        assertNotNull(ReflectionHelpers.getField<View?>(activity, "unfoldedPanel"))
        assertTrue(SessionPrefs.unfoldedControls(context))
    }

    private fun session(): Pair<SessionActivity, OnScreenControls> {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val controls = OnScreenControls(activity, null)
        ReflectionHelpers.setField(activity, "onScreenControls", controls)
        ReflectionHelpers.setField(activity, "loading", LoadingState(activity))
        return activity to controls
    }

    private fun finishLoading(activity: SessionActivity) {
        ReflectionHelpers.getField<LoadingState>(activity, "loading").visible = false
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshControls")
    }

    private fun update(activity: SessionActivity) =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateOnScreenControls")
}
