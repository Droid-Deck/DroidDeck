package com.droiddeck.launcher.input

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [33],
    shadows = [SessionSteamOverlayTest.CompositorShadow::class, SessionKeyboardTest.PadShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionSteamOverlayTest {
    private lateinit var activity: SessionActivity
    private lateinit var controls: OnScreenControls

    @Before fun setUp() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.suspended = false
        controls = OnScreenControls(activity, PadBridge(activity.cacheDir))
        ReflectionHelpers.setField(activity, "onScreenControls", controls)
        ReflectionHelpers.setField(activity, "surfaceView", SurfaceView(activity).apply { layout(0, 0, 1000, 600) })
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_ALWAYS)
        SessionPrefs.setTouchMode(activity, SessionPrefs.TOUCH_GESTURES)
        CompositorShadow.touches.clear()
        overlay(false)
    }

    @After fun tearDown() {
        AgentGuest.stop()
        shadowOf(Looper.getMainLooper()).idle()
        SessionState.mode = SessionService.MODE_STEAM
    }

    @Test fun controlsHideDuringOverlayAndReturnToTheExistingPreset() {
        overlay(true)
        refreshControls()
        assertEquals(View.GONE, controls.visibility)
        overlay(false)
        refreshControls()
        assertEquals(View.VISIBLE, controls.visibility)
        assertEquals(SessionPrefs.OSC_ALWAYS, SessionPrefs.oscMode(activity))
    }

    @Test fun closingOverlayKeepsUserHiddenControlsHidden() {
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_NEVER)
        overlay(true)
        refreshControls()
        overlay(false)
        refreshControls()
        assertEquals(View.GONE, controls.visibility)
    }

    @Test fun overlayTemporarilyUsesDirectTouchWithoutChangingGamePreferences() {
        overlay(true)
        assertEquals(SessionPrefs.TOUCH_DIRECT, effectiveTouchMode())
        overlay(false)
        assertEquals(SessionPrefs.TOUCH_GESTURES, effectiveTouchMode())
        assertEquals(SessionPrefs.TOUCH_GESTURES, SessionPrefs.touchMode(activity))
    }

    @Test fun overlayReceivesFingerDownAndUpEvenWhenGameTouchIsDisabled() {
        overlay(true)
        ReflectionHelpers.setField(activity, "routedTouchMode", SessionPrefs.TOUCH_OFF)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, 20f, 500f, 0)
            try { assertTrue(activity.onTouchEvent(event)) } finally { event.recycle() }
        }
        assertEquals(listOf(3, 0, 2), CompositorShadow.touches)
    }

    @Test fun nonSteamSessionIgnoresOldSteamOverlayState() {
        overlay(true)
        SessionState.mode = SessionService.MODE_RUN
        refreshControls()
        assertEquals(View.VISIBLE, controls.visibility)
        assertEquals(SessionPrefs.TOUCH_GESTURES, effectiveTouchMode())
    }

    @Test fun openingInputCancelsPendingGameLongPress() {
        val buttons = mutableListOf<Boolean>()
        val hold = DirectTouchHold(8f, object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) {}
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) { buttons += pressed }
            override fun onWheel(steps: Int) {}
            override fun onLongPress() {}
        }) {}
        ReflectionHelpers.setField(activity, "directTouchHold", hold)
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 500f, 0)
        try { hold.onTouch(event) } finally { event.recycle() }
        overlay(true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelTouchGestures")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertTrue(buttons.isEmpty())
    }

    private fun overlay(visible: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(AgentGuest, "setSteamOverlayVisible",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, visible))
    }

    private fun effectiveTouchMode(): String = ReflectionHelpers.callInstanceMethod(activity, "effectiveTouchMode")
    private fun refreshControls() = ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateOnScreenPad")

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val touches = mutableListOf<Int>()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendTouch(action: Int, id: Int, x: Int, y: Int) { touches += action }
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) {}
        }
    }
}
