package com.droiddeck.launcher.input

import android.os.Looper
import android.os.SystemClock
import android.content.res.Configuration
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionGestureRoutingTest.CompositorShadow::class])
class SessionGestureRoutingTest {
    private lateinit var activity: SessionActivity
    private lateinit var gestures: TouchscreenGestures
    private lateinit var steamTap: ThreeFingerTap
    private lateinit var launcherScroll: ScrollTouchGestures
    private lateinit var directHold: DirectTouchHold
    private val keys = mutableListOf<Pair<Int, Boolean>>()
    private val directButtons = mutableListOf<Pair<Int, Boolean>>()
    private val scrollPositions = mutableListOf<Pair<Float, Float>>()
    private val scrollDeltas = mutableListOf<Pair<Float, Float>>()
    private var keyboards = 0

    @After fun tearDown() {
        if (::gestures.isInitialized) gestures.cancel()
        if (::launcherScroll.isInitialized) launcherScroll.cancel()
        if (::directHold.isInitialized) directHold.cancel()
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.steamUi = null
        SessionState.suspended = false
    }

    @Test fun configuredThreeFingerActionTakesPrecedenceOverSteamShortcut() {
        prepare(SessionPrefs.TOUCH_GESTURES, TouchGestureConfig(threeFingerTapAction = "key_E"))
        tapThreeFingers(expectSteamGesture = false)
        assertEquals(listOf(18 to true, 18 to false), keys)
        assertEquals(0, keyboards)
    }

    @Test fun disablingThreeFingerTapDoesNotFallBackToSteamShortcut() {
        prepare(SessionPrefs.TOUCH_GESTURES, TouchGestureConfig(threeFingerTapEnabled = false))
        tapThreeFingers(expectSteamGesture = false)
        assertTrue(keys.isEmpty())
        assertEquals(0, keyboards)
    }

    @Test fun defaultThreeFingerActionOpensPcKeyboardOnce() {
        prepare(SessionPrefs.TOUCH_GESTURES)
        tapThreeFingers(expectSteamGesture = false)
        assertEquals(1, keyboards)
        assertTrue(keys.isEmpty())
    }

    @Test fun gestureModeKeepsItsBindingsWhileLauncherMouseIsAvailable() {
        prepare(SessionPrefs.TOUCH_GESTURES, TouchGestureConfig(threeFingerTapAction = "key_E"))
        setField("padMouseOn", true)
        tapThreeFingers(expectSteamGesture = false)
        assertEquals(listOf(18 to true, 18 to false), keys)
        assertTrue(scrollPositions.isEmpty())
        assertTrue(scrollDeltas.isEmpty())
    }

    @Test fun directModeStillRoutesLauncherTouchesToScrolling() {
        prepare(SessionPrefs.TOUCH_DIRECT)
        setField("padMouseOn", true)
        val down = SystemClock.uptimeMillis()
        for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).withIndex()) {
            val event = MotionEvent.obtain(down, down + index * 32L, action, 400f, if (index == 0) 300f else 200f, 0)
            try { assertTrue(activity.onTouchEvent(event)) } finally { event.recycle() }
        }
        assertEquals(listOf(400f to 300f), scrollPositions)
        assertTrue(scrollDeltas.isNotEmpty())
        assertTrue(keys.isEmpty())
        assertFalse(steamTap.active)
    }

    @Test fun upstreamSteamShortcutStillReceivesTouchesInLegacyModes() {
        for (mode in listOf(SessionPrefs.TOUCH_DIRECT, SessionPrefs.TOUCH_PAD)) {
            prepare(mode)
            tapThreeFingers(expectSteamGesture = true)
            assertTrue(keys.isEmpty())
            assertEquals(0, keyboards)
        }
    }

    @Test fun configurationChangeReleasesHeldGestureAndResetsPointerAnchor() {
        startLongPress()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(listOf(18 to true), keys)
        activity.onConfigurationChanged(Configuration(activity.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        })
        assertEquals(listOf(18 to true, 18 to false), keys)
        assertFalse(SessionActivity::class.java.getDeclaredField("gesturePointerReady").apply { isAccessible = true }.getBoolean(activity))
    }

    @Test fun surfaceResizeCancelsPendingHoldAndResetsPointerAnchor() {
        startLongPress()
        setField("surfaceW", 1000)
        setField("surfaceH", 600)
        val window = android.view.Surface(android.graphics.SurfaceTexture(0))
        var activeHolder: android.view.SurfaceHolder? = null
        val surface = object : SurfaceView(RuntimeEnvironment.getApplication()) {
            override fun getHolder() = activeHolder ?: super.getHolder()
        }.apply { layout(0, 0, 1000, 600) }
        activeHolder = object : android.view.SurfaceHolder by surface.holder {
            override fun getSurface() = window
        }
        setField("surfaceView", surface)
        try { activity.surfaceChanged(surface.holder, 0, 800, 600) } finally { window.release() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertTrue(keys.isEmpty())
        assertFalse(SessionActivity::class.java.getDeclaredField("gesturePointerReady").apply { isAccessible = true }.getBoolean(activity))
    }

    @Test fun unchangedSurfaceDimensionsDoNotInterruptPendingHold() {
        startLongPress()
        setField("surfaceW", 1000)
        setField("surfaceH", 600)
        val surface = SessionActivity::class.java.getDeclaredField("surfaceView").apply { isAccessible = true }.get(activity) as SurfaceView
        activity.surfaceChanged(surface.holder, 0, 1000, 600)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertEquals(listOf(18 to true), keys)
    }

    @Test fun inactiveSurfaceDestructionDoesNotInterruptCurrentGesture() {
        startLongPress()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        val staleSurface = SurfaceView(RuntimeEnvironment.getApplication())
        activity.surfaceDestroyed(staleSurface.holder)
        assertEquals(listOf(18 to true), keys)
        assertTrue(SessionActivity::class.java.getDeclaredField("gesturePointerReady").apply { isAccessible = true }.getBoolean(activity))
    }

    @Test fun pausingCancelsPendingHoldsInBothTouchModes() {
        for (mode in listOf(SessionPrefs.TOUCH_GESTURES, SessionPrefs.TOUCH_DIRECT)) {
            prepare(mode, TouchGestureConfig(longPressEnabled = true, longPressAction = "key_E"))
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 400f, 300f, 0)
            try { assertTrue(activity.onTouchEvent(event)) } finally { event.recycle() }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onPause")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertTrue(keys.isEmpty())
            assertTrue(directButtons.isEmpty())
        }
    }

    @Test fun pausingReleasesActiveHoldsInBothTouchModes() {
        for (mode in listOf(SessionPrefs.TOUCH_GESTURES, SessionPrefs.TOUCH_DIRECT)) {
            keys.clear()
            directButtons.clear()
            prepare(mode, TouchGestureConfig(longPressEnabled = true, longPressAction = "key_E"))
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 400f, 300f, 0)
            try { assertTrue(activity.onTouchEvent(event)) } finally { event.recycle() }
            val gestureMode = mode == SessionPrefs.TOUCH_GESTURES
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(if (gestureMode) 350 else 320))
            assertEquals(if (gestureMode) listOf(18 to true) else emptyList(), keys)
            assertEquals(if (gestureMode) emptyList() else listOf(PointerGestures.BTN_RIGHT to true), directButtons)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "onPause")
            assertEquals(if (gestureMode) listOf(18 to true, 18 to false) else emptyList(), keys)
            assertEquals(if (gestureMode) emptyList() else listOf(PointerGestures.BTN_RIGHT to true, PointerGestures.BTN_RIGHT to false), directButtons)
        }
    }

    private fun startLongPress() {
        prepare(SessionPrefs.TOUCH_GESTURES, TouchGestureConfig(longPressEnabled = true, longPressAction = "key_E"))
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 400f, 300f, 0).also {
            try { assertTrue(activity.onTouchEvent(it)) } finally { it.recycle() }
        }
        setField("gesturePointerReady", true)
    }

    private fun prepare(mode: String, config: TouchGestureConfig = TouchGestureConfig()) {
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.steamUi = "bigpicture"
        SessionState.suspended = false
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        steamTap = ThreeFingerTap(8f)
        gestures = TouchscreenGestures(8f, object : TouchscreenGestures.Output {
            override fun moveTo(x: Float, y: Float) {}
            override fun moveBy(dx: Float, dy: Float, relative: Boolean) {}
            override fun button(code: Int, down: Boolean) { fail("Unexpected mouse button: $code $down") }
            override fun key(code: Int, down: Boolean) { keys += code to down }
            override fun wheel(steps: Int) { fail("Unexpected wheel input") }
            override fun showKeyboard() { keyboards++ }
        }).also { it.configure(config) }
        setField("routedTouchMode", mode)
        setField("padMouseCheckedAt", SystemClock.uptimeMillis())
        setField("threeFingerTap", steamTap)
        setField("touchscreenGestures", gestures)
        setField("surfaceView", SurfaceView(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 1000, 600) })
        setField("touchpad", TouchpadGestures(8f, object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) {}
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {}
            override fun onWheel(steps: Int) {}
            override fun onLongPress() {}
        }))
        directHold = DirectTouchHold(8f, object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) {}
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) { directButtons += button to pressed }
            override fun onWheel(steps: Int) {}
            override fun onLongPress() {}
        }) {}.also { it.configure(config) }
        setField("directTouchHold", directHold)
        launcherScroll = ScrollTouchGestures(8f, 50f, object : ScrollTouchGestures.Listener {
            override fun onMove(x: Float, y: Float) { scrollPositions += x to y }
            override fun onButton(button: Int, pressed: Boolean) {}
            override fun onScroll(dx: Float, dy: Float) { scrollDeltas += dx to dy }
            override fun onLongPress() {}
        })
        setField("scrollTouch", launcherScroll)
    }

    private fun tapThreeFingers(expectSteamGesture: Boolean) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, index: Int, count: Int) {
            val properties = Array(count) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { MotionEvent.PointerCoords().apply { x = 300f + it * 100; y = 300f; pressure = 1f } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(activity.onTouchEvent(event)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0, 1)
        send(MotionEvent.ACTION_POINTER_DOWN, 1, 2)
        send(MotionEvent.ACTION_POINTER_DOWN, 2, 3)
        assertEquals(expectSteamGesture, steamTap.active)
        send(MotionEvent.ACTION_POINTER_UP, 2, 3)
        send(MotionEvent.ACTION_POINTER_UP, 1, 2)
        send(MotionEvent.ACTION_UP, 0, 1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(steamTap.active)
    }

    private fun setField(name: String, value: Any) {
        SessionActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendTouch(action: Int, id: Int, x: Int, y: Int) {}
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) {}
        }
    }
}
