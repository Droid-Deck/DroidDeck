package com.droiddeck.launcher.input

import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.PenStream.Companion.PEN_IN
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TIP
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.nio.file.Files
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionCancelledTouchTest.CompositorShadow::class])
class SessionCancelledTouchTest {
    private lateinit var activity: SessionActivity
    private val previousSize = SessionState.outputSize
    private val previousMode = SessionState.mode
    private val previousSteamUi = SessionState.steamUi
    private val finger = MotionEvent.TOOL_TYPE_FINGER
    private val pen = MotionEvent.TOOL_TYPE_STYLUS

    @Before fun setUp() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        activity.window.decorView
        activity.getSharedPreferences("session", 0).edit().clear().commit()
        field("surfaceView", SurfaceView(activity).apply { layout(0, 0, 1000, 600) })
        SessionState.outputSize = 1000 to 600
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.steamUi = null
        field("padMouseCheckedAt", android.os.SystemClock.uptimeMillis())
        field("threeFingerTap", ThreeFingerTap(8f))
        field("directTouchHold", DirectTouchHold(8f, object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) {}
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {}
            override fun onWheel(steps: Int) {}
            override fun onLongPress() {}
        }, ReflectionHelpers.getField(activity, "directTouchSink")).also {
            it.configure(TouchGestureConfig(directHoldBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_NONE))
        })
        field("routedTouchMode", SessionPrefs.TOUCH_DIRECT)
        CompositorShadow.clear()
    }

    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        SessionState.outputSize = previousSize
        SessionState.mode = previousMode
        SessionState.steamUi = previousSteamUi
    }

    @Test fun aCancelledSecondFingerEndsTheDirectStream() {
        touch(MotionEvent.ACTION_DOWN, 10, 100f)
        touch(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 20, 100f, 300f)
        touch(pointer(MotionEvent.ACTION_POINTER_UP, 1), 30, 100f, 300f, flags = MotionEvent.FLAG_CANCELED)
        touch(MotionEvent.ACTION_MOVE, 40, 150f)
        touch(MotionEvent.ACTION_UP, 50, 150f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(listOf(0 to 0, 0 to 1, 3 to -1), CompositorShadow.touches)
        touch(MotionEvent.ACTION_DOWN, 60, 200f)
        assertEquals(0 to 0, CompositorShadow.touches.last())
    }

    @Test fun aCancelledUpIsNotADirectTap() {
        touch(MotionEvent.ACTION_DOWN, 10, 100f)
        touch(MotionEvent.ACTION_UP, 20, 100f, flags = MotionEvent.FLAG_CANCELED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(listOf(0, 3), CompositorShadow.touches.map { it.first })
    }

    @Test fun aCancelledFingerBesideThePenLeavesTheStrokeAlone() {
        val pointers = arrayOf(finger to 100f, finger to 200f, pen to 500f)
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, finger to 100f))
        activity.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), 15, finger to 100f, finger to 200f))
        activity.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 2), 20, *pointers))
        activity.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), 30, *pointers, flags = MotionEvent.FLAG_CANCELED))
        assertEquals(3 to -1, CompositorShadow.touches.last())
        val touches = CompositorShadow.touches.size
        activity.onTouchEvent(event(MotionEvent.ACTION_MOVE, 40, finger to 120f, pen to 520f, ids = intArrayOf(0, 2)))
        assertEquals(touches, CompositorShadow.touches.size)
        assertEquals(List(3) { PEN_IN or PEN_TIP }, CompositorShadow.pens.map { it.first })
        assertEquals(InputSpace.fixedX(520f, 1000), CompositorShadow.pens.last().second)
    }

    @Test fun aCancelledSteamKeyboardElementDoesNothing() {
        val directory = Files.createTempDirectory("cancelled-touch").toFile()
        SteamTouchDevice.prepare(directory)
        try {
            var keyboards = 0
            val steam = SteamTouchControls(activity, SteamTouchDevice.current!!, {}, { keyboards++ })
            ReflectionHelpers.setField(steam, "elements", listOf(SteamTouchConfig.Element(SteamTouchConfig.KEYBOARD, true, 0.5f, 0.5f)))
            steam.visibility = View.VISIBLE
            steam.layout(0, 0, 1000, 600)
            fun send(action: Int, flags: Int = 0) {
                val e = event(action, 10, finger to 500f, flags = flags)
                try { steam.onTouchEvent(e) } finally { e.recycle() }
            }
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_UP, MotionEvent.FLAG_CANCELED)
            assertEquals(0, keyboards)
            send(MotionEvent.ACTION_DOWN)
            send(MotionEvent.ACTION_UP)
            assertEquals(1, keyboards)
        } finally {
            SteamTouchDevice.release()
            directory.deleteRecursively()
        }
    }

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun touch(action: Int, time: Long, vararg xs: Float, flags: Int = 0) {
        val e = event(action, time, *xs.map { finger to it }.toTypedArray(), flags = flags)
        try { assertTrue(activity.onTouchEvent(e)) } finally { e.recycle() }
    }

    private fun event(action: Int, time: Long, vararg pointers: Pair<Int, Float>, flags: Int = 0, ids: IntArray? = null): MotionEvent {
        val properties = Array(pointers.size) { MotionEvent.PointerProperties().apply { id = ids?.get(it) ?: it; toolType = pointers[it].first } }
        val coords = Array(pointers.size) { MotionEvent.PointerCoords().apply { x = pointers[it].second; y = 300f; pressure = 0.5f } }
        val source = if (pointers.any { it.first == pen }) InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(0, time, action, pointers.size, properties, coords, 0, 0, 1f, 1f, 6, 0, source, flags)
    }

    private fun field(name: String, value: Any) = ReflectionHelpers.setField(activity, name, value)

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val touches = mutableListOf<Pair<Int, Int>>()
            val pens = mutableListOf<Pair<Int, Int>>()
            fun clear() { touches.clear(); pens.clear() }
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendTouch(action: Int, id: Int, x: Int, y: Int) { touches += action to id }
            @JvmStatic @Implementation fun nativeSendPen(flags: Int, x: Int, y: Int) { pens += flags to x }
            @JvmStatic @Implementation fun nativeSendPointer(action: Int, x: Int, y: Int) {}
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) {}
            @JvmStatic @Implementation fun nativeSendKey(code: Int, value: Int) {}
            @JvmStatic @Implementation fun nativeFocusTakesTouch(): Boolean = false
        }
    }
}
