package com.droiddeck.launcher.input

import android.hardware.input.InputManager
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.PenStream.Companion.PEN_IN
import com.droiddeck.launcher.input.PenStream.Companion.PEN_MIDDLE
import com.droiddeck.launcher.input.PenStream.Companion.PEN_RIGHT
import com.droiddeck.launcher.input.PenStream.Companion.PEN_TIP
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
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
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.shadows.ShadowInputDevice
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionPenRoutingTest.CompositorShadow::class])
class SessionPenRoutingTest {
    private lateinit var activity: SessionActivity
    private val previousSize = SessionState.outputSize
    private val previousMode = SessionState.mode
    private val previousSteamUi = SessionState.steamUi
    private val pen = MotionEvent.TOOL_TYPE_STYLUS
    private val finger = MotionEvent.TOOL_TYPE_FINGER
    private val stylusScreen = InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS

    @Before fun setUp() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        activity.window.decorView
        activity.getSharedPreferences("session", 0).edit().clear().commit()
        field("surfaceView", SurfaceView(activity).apply { layout(0, 0, 1000, 600) })
        SessionState.outputSize = 1000 to 600
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionState.steamUi = null
        CompositorShadow.clear()
    }

    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        SessionState.outputSize = previousSize
        SessionState.mode = previousMode
        SessionState.steamUi = previousSteamUi
    }

    @Test fun penHoverMovesThePointerAndTheCursorOnTheDesktopAndInSteam() {
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertEquals(listOf(Pen(PEN_IN, InputSpace.fixedX(500f, 1000), InputSpace.fixedY(300f, 600))), CompositorShadow.pens)
        assertTrue(state<Boolean>("cursorVisible").value)
        assertEquals(Offset(500f, 300f), state<Offset>("cursorPos").value)
        steamDirect(SessionPrefs.PEN_MOUSE)
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 600f)))
        assertEquals(Pen(PEN_IN, InputSpace.fixedX(600f, 1000), InputSpace.fixedY(300f, 600)), CompositorShadow.pens.last())
        assertEquals(Offset(600f, 300f), state<Offset>("cursorPos").value)
        assertTrue(CompositorShadow.pointers.isEmpty())
        assertTrue(CompositorShadow.touches.isEmpty())
    }

    @Test fun theSideButtonSettingDecidesWhatThePrimaryButtonSends() {
        val cases = listOf(SessionPrefs.PEN_BUTTON_RIGHT to PEN_RIGHT, SessionPrefs.PEN_BUTTON_MIDDLE to PEN_MIDDLE,
            SessionPrefs.PEN_BUTTON_OFF to 0)
        for ((step, case) in cases.withIndex()) {
            SessionPrefs.setPenSetting(activity, "penButton", case.first)
            invoke("readPrefs")
            CompositorShadow.pens.clear()
            assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10L + 10 * step, pen to 500f,
                buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)))
            assertEquals(case.first, listOf(PEN_IN or case.second), CompositorShadow.pens.map { it.flags })
        }
    }

    @Test fun penContactInMouseModeHoldsTheTipUntilItLifts() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, pen to 500f)))
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_MOVE, 20, pen to 520f)))
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_UP, 30, pen to 520f)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
        assertEquals(listOf(PEN_IN or PEN_TIP, PEN_IN or PEN_TIP, PEN_IN, 0), CompositorShadow.pens.map { it.flags })
        assertTrue(CompositorShadow.touches.isEmpty())
    }

    @Test fun anExternalTabletTipReachesThePenInsteadOfTheMouse() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        val source = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, pen to 500f, source = source)))
        assertEquals(listOf(PEN_IN or PEN_TIP), CompositorShadow.pens.map { it.flags })
        assertTrue(CompositorShadow.pointers.isEmpty())
    }

    @Test fun penContactInTouchModeTakesTheFingerRouteAsBefore() {
        steamDirect(SessionPrefs.PEN_TOUCH)
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, pen to 500f)))
        assertEquals(listOf(Touch(0, 0, InputSpace.x(500f, 1000), InputSpace.y(300f, 600))), CompositorShadow.touches)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 600f))
        assertTrue(CompositorShadow.pens.isEmpty())
        assertTrue(CompositorShadow.pointers.isEmpty())
    }

    @Test fun anExternalTabletStaysOnTheMousePathInTouchMode() {
        steamDirect(SessionPrefs.PEN_TOUCH)
        val source = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS
        assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, pen to 500f, source = source)))
        assertTrue(CompositorShadow.touches.isEmpty())
        assertTrue(CompositorShadow.pens.isEmpty())
    }

    @Test fun fingersKeepTheirRouteWhateverThePenMode() {
        for (mode in listOf(SessionPrefs.PEN_MOUSE, SessionPrefs.PEN_TOUCH)) {
            steamDirect(mode)
            CompositorShadow.clear()
            assertTrue(activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, finger to 500f, source = InputDevice.SOURCE_TOUCHSCREEN)))
            assertEquals(listOf(Touch(0, 0, InputSpace.x(500f, 1000), InputSpace.y(300f, 600))), CompositorShadow.touches)
            assertTrue(CompositorShadow.pens.isEmpty())
            activity.onTouchEvent(event(MotionEvent.ACTION_CANCEL, 20, finger to 500f, source = InputDevice.SOURCE_TOUCHSCREEN))
        }
    }

    @Test fun aFingerAndAPenInOneEventAreSplitByToolType() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10, finger to 100f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, finger to 110f, pen to 500f))
        assertEquals(listOf(PEN_IN or PEN_TIP), CompositorShadow.pens.map { it.flags })
        assertEquals(InputSpace.fixedX(500f, 1000), CompositorShadow.pens.last().x)
        assertTrue(CompositorShadow.touches.isNotEmpty())
        assertTrue(CompositorShadow.touches.all { it.id == 0 && it.x < InputSpace.x(200f, 1000) })
    }

    @Test fun theLowerScreenGameTakesPenHoverAndContact() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        fun hover(event: MotionEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onBottomGameHover",
            ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, event))
        fun touch(event: MotionEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onBottomGameTouch",
            ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, event))
        assertTrue(hover(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(touch(event(MotionEvent.ACTION_DOWN, 20, pen to 500f, source = InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS)))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_TIP), CompositorShadow.pens.map { it.flags })
        SessionPrefs.setPenSetting(activity, "pen", SessionPrefs.PEN_TOUCH)
        invoke("readPrefs")
        assertFalse(hover(event(MotionEvent.ACTION_HOVER_MOVE, 30, pen to 500f)))
        assertFalse(hover(event(MotionEvent.ACTION_HOVER_MOVE, 40, finger to 500f, source = InputDevice.SOURCE_MOUSE)))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_TIP, 0), CompositorShadow.pens.map { it.flags })
    }

    @Test fun glassesAProgramPointerLockAndAppUiDropPenHover() {
        state<Boolean>("glassesOutputActive").value = true
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f))
        state<Boolean>("glassesOutputActive").value = false
        SessionState.mode = SessionService.MODE_STEAM
        field("pointerLocked", true)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 500f))
        field("pointerLocked", false)
        state<Boolean>("achievementsOpen").value = true
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 30, pen to 500f))
        assertTrue(CompositorShadow.pens.isEmpty())
        assertTrue(CompositorShadow.pointers.isEmpty())
    }

    @Config(sdk = [34])
    @Test fun aPenNearTheDesktopReleasesTheMouseGrabUntilItLeaves() {
        shadowOf(activity.getSystemService(InputManager::class.java)).addInputDevice(
            InputDeviceBuilder.newBuilder().setId(11).setName("mouse").setSources(InputDevice.SOURCE_MOUSE).build())
        field("desktopMousePlaced", true)
        field("pointerLocked", true)
        val start = android.os.SystemClock.uptimeMillis()
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, start, pen to 500f)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(RIGHT_CTRL to 1, RIGHT_CTRL to 0), CompositorShadow.keys)
        field("pointerLocked", false)
        invoke("grabDesktopMouse")
        assertEquals(2, CompositorShadow.keys.size)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, start + 1_000, pen to 500f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PenStream.EXIT_GRACE_MS))
        invoke("grabDesktopMouse")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(RIGHT_CTRL to 1, RIGHT_CTRL to 0, RIGHT_CTRL to 1, RIGHT_CTRL to 0), CompositorShadow.keys)
    }

    @Test fun openingAppUiEndsAHoveringPen() {
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f))
        state<Boolean>("achievementsOpen").value = true
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 510f))
        assertEquals(listOf(PEN_IN, 0), CompositorShadow.pens.map { it.flags })
    }

    @Test fun touchModeAndRouteChangesDeviceRemovalAndPauseEndThePen() {
        invoke("readPrefs")
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        SessionPrefs.setTouchMode(activity, SessionPrefs.TOUCH_DIRECT)
        invoke("readPrefs")
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 500f)))
        field("routedTouchMode", SessionPrefs.TOUCH_PAD)
        invoke("applyTouchRouting")
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 30, pen to 500f)))
        ReflectionHelpers.getField<InputManager.InputDeviceListener>(activity, "deviceListener").onInputDeviceRemoved(6)
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 40, pen to 500f)))
        invoke("onPause")
        assertEquals(listOf(PEN_IN, 0, PEN_IN, 0, PEN_IN, 0, PEN_IN, 0), CompositorShadow.pens.map { it.flags })
    }

    @Config(sdk = [34], shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
    @Test fun aHeldPenKeyOutlivesATouchCancelButNotAFocusLoss() {
        penKeyboard()
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(penKey(KeyEvent.ACTION_DOWN, 15))
        invoke("cancelTouchGestures")
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20, pen to 500f)))
        invoke("releaseHeldGuestInput")
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 30, pen to 500f)))
        val right = PEN_IN or PEN_RIGHT
        assertEquals(listOf(PEN_IN, right, 0, right, 0, PEN_IN), CompositorShadow.pens.map { it.flags })
    }

    @Config(sdk = [34], shadows = [PadBridgeRoutingTest.RegisteredKeyEvent::class])
    @Test fun aPenKeyIsAnOrdinaryKeyInTouchMode() {
        penKeyboard()
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(penKey(KeyEvent.ACTION_DOWN, 15))
        SessionPrefs.setPenSetting(activity, "pen", SessionPrefs.PEN_TOUCH)
        invoke("readPrefs")
        assertTrue(penKey(KeyEvent.ACTION_UP, 20))
        assertFalse(penKey(KeyEvent.ACTION_DOWN, 25))
        assertFalse(penKey(KeyEvent.ACTION_UP, 30))
        assertEquals(listOf(PEN_IN, PEN_IN or PEN_RIGHT, 0), CompositorShadow.pens.map { it.flags })
    }

    @Test fun aFingerAndARelativePenOnTheTouchpadStayOneTwoPointerStream() {
        state<Boolean>("glassesOutputActive").value = true
        field("padMouseCheckedAt", android.os.SystemClock.uptimeMillis())
        for (penFirst in listOf(false, true)) {
            val pad = touchpad()
            val first = if (penFirst) pen to 500f else finger to 100f
            val second = if (penFirst) finger to 100f else pen to 500f
            fun both(step: Int) = arrayOf(first.first to first.second + 10f * step, second.first to second.second + 10f * step)
            activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10_000, first))
            activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_010, *both(0)))
            for (step in 1..4) activity.onTouchEvent(event(MotionEvent.ACTION_MOVE, 10_010L + 10 * step, *both(step)))
            activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_060, *both(4)))
            activity.onTouchEvent(event(MotionEvent.ACTION_UP, 10_070, first.first to first.second + 40f))
            assertTrue("penFirst=$penFirst", pad.scrolls.isNotEmpty())
            assertTrue("penFirst=$penFirst ${pad.moves}", pad.moves.all { it in 450f..550f })
            assertTrue(CompositorShadow.pens.isEmpty())
        }
    }

    @Test fun aRelativePenStrokeThatAndroidCanceledDoesNotClick() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        state<Boolean>("glassesOutputActive").value = true
        val pad = touchpad()
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10_000, finger to 100f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_010, finger to 100f, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_050, finger to 100f, pen to 500f,
            flags = MotionEvent.FLAG_CANCELED))
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 10_060, finger to 100f))
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 20_000, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 20_050, pen to 500f, flags = MotionEvent.FLAG_CANCELED))
        assertTrue(pad.presses.isEmpty())
        assertTrue(CompositorShadow.touches.isNotEmpty())
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 30_000, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 30_050, pen to 500f))
        assertEquals(listOf(PointerGestures.BTN_LEFT), pad.presses)
        assertTrue(CompositorShadow.pens.isEmpty())
    }

    @Test fun aTouchCancelAfterAFingerLiftsEndsTheRelativePenStroke() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        state<Boolean>("glassesOutputActive").value = true
        val pad = touchpad()
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 10_000, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_010, pen to 500f, finger to 100f))
        activity.onTouchEvent(event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10_020, pen to 500f, finger to 100f))
        invoke("cancelTouchGestures")
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 10_050, pen to 500f))
        assertTrue(pad.presses.isEmpty())
        activity.onTouchEvent(event(MotionEvent.ACTION_DOWN, 20_000, pen to 500f))
        activity.onTouchEvent(event(MotionEvent.ACTION_UP, 20_050, pen to 500f))
        assertEquals(listOf(PointerGestures.BTN_LEFT), pad.presses)
    }

    @Test fun changingAPenSettingEndsThePenAndRereadingTheSameSettingsDoesNot() {
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        invoke("readPrefs")
        assertEquals(listOf(PEN_IN), CompositorShadow.pens.map { it.flags })
        for ((step, setting) in listOf("penButton" to SessionPrefs.PEN_BUTTON_MIDDLE, "pen" to SessionPrefs.PEN_TOUCH).withIndex()) {
            assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 20L + 10 * step, pen to 500f)))
            SessionPrefs.setPenSetting(activity, setting.first, setting.second)
            invoke("readPrefs")
            assertEquals(setting.first, 0, CompositorShadow.pens.last().flags)
        }
    }

    @Test fun theFirstPenEventRemembersThatThePhoneHasAPen() {
        invoke("readPrefs")
        assertFalse(state<Boolean>("penSeen").value)
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(state<Boolean>("penSeen").value)
        assertTrue(SessionPrefs.penSeen(activity))
    }

    @Config(sdk = [34])
    @Test fun aConnectedStylusDeviceCountsAsSeenBeforeAnyPenEvent() {
        invoke("readPrefs")
        assertFalse(state<Boolean>("penSeen").value)
        shadowOf(activity.getSystemService(InputManager::class.java)).addInputDevice(
            InputDeviceBuilder.newBuilder().setId(42).setName("digitizer").setSources(stylusScreen).build())
        invoke("readPrefs")
        assertTrue(state<Boolean>("penSeen").value)
        assertTrue(SessionPrefs.penSeen(activity))
    }

    @Config(sdk = [33])
    @Test fun beforeAndroid14ATouchscreenWithTheStylusBitWaitsForAPenEvent() {
        shadowOf(activity.getSystemService(InputManager::class.java)).addInputDevice(
            ShadowInputDevice.makeInputDeviceNamed("touchscreen").also {
                ReflectionHelpers.setField(it, "mId", 42)
                ReflectionHelpers.setField(it, "mSources", stylusScreen)
            })
        assertEquals(listOf(42), InputDevice.getDeviceIds().toList())
        invoke("readPrefs")
        assertFalse(state<Boolean>("penSeen").value)
        assertFalse(SessionPrefs.penSeen(activity))
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, 10, pen to 500f)))
        assertTrue(state<Boolean>("penSeen").value)
    }

    @Config(sdk = [34])
    @Test fun aStylusDeviceIsNotAHardwareMouse() {
        fun device(sources: Int) = InputDeviceBuilder.newBuilder().setId(7).setName("device").setSources(sources).build()
        fun mouse(sources: Int) = ReflectionHelpers.callInstanceMethod<Boolean>(activity, "isHardwareMouse",
            ReflectionHelpers.ClassParameter.from(InputDevice::class.java, device(sources)))
        assertTrue(mouse(InputDevice.SOURCE_MOUSE))
        assertFalse(mouse(InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS))
        assertFalse(mouse(InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_KEYBOARD))
        assertFalse(mouse(stylusScreen))
    }

    @Test fun aFingerIsIgnoredWhileThePenIsNear() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        val t = android.os.SystemClock.uptimeMillis()
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, t, pen to 500f)))
        tap(t + 10, 100f)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_EXIT, t + 20, pen to 500f))
        tap(t + 20 + PalmGate.GRACE_MS, 200f)
        assertEquals(listOf(InputSpace.x(200f, 1000)), fingerDowns())
    }

    @Test fun aFingerAlreadyDownIsCancelledWhenThePenComesNear() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        val t = android.os.SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, t, 100f)
        activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_ENTER, t + 50, pen to 500f))
        touch(MotionEvent.ACTION_MOVE, t + 60, 150f)
        touch(MotionEvent.ACTION_UP, t + 70, 150f)
        assertEquals(listOf(0, 3), CompositorShadow.touches.map { it.action })
        assertEquals(listOf(PEN_IN), CompositorShadow.pens.map { it.flags })
    }

    @Test fun aPenOverTheLowerScreenArmsPalmRejection() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        val t = android.os.SystemClock.uptimeMillis()
        ReflectionHelpers.callInstanceMethod<Boolean>(activity, "onBottomGameHover",
            ReflectionHelpers.ClassParameter.from(MotionEvent::class.java, event(MotionEvent.ACTION_HOVER_MOVE, t, pen to 500f)))
        tap(t + 10, 100f)
        assertTrue(fingerDowns().isEmpty())
    }

    @Test fun palmRejectionOffLetsFingersThrough() {
        SessionPrefs.setPenPalm(activity, false)
        steamDirect(SessionPrefs.PEN_MOUSE)
        val t = android.os.SystemClock.uptimeMillis()
        assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, t, pen to 500f)))
        tap(t + 10, 100f)
        assertEquals(listOf(InputSpace.x(100f, 1000)), fingerDowns())
    }

    @Test fun endingThePenEndsPalmRejectionAfterTheGrace() {
        steamDirect(SessionPrefs.PEN_MOUSE)
        val listener = ReflectionHelpers.getField<InputManager.InputDeviceListener>(activity, "deviceListener")
        for (end in listOf({ invoke("cancelTouchGestures") }, { listener.onInputDeviceRemoved(6) })) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            CompositorShadow.clear()
            val t = android.os.SystemClock.uptimeMillis()
            assertTrue(activity.dispatchGenericMotionEvent(event(MotionEvent.ACTION_HOVER_MOVE, t, pen to 500f)))
            end()
            tap(t + PalmGate.GRACE_MS - 10, 100f)
            tap(t + PalmGate.GRACE_MS, 200f)
            assertEquals(listOf(InputSpace.x(200f, 1000)), fingerDowns())
        }
    }

    private fun touch(action: Int, time: Long, x: Float) =
        activity.dispatchTouchEvent(event(action, time, finger to x, source = InputDevice.SOURCE_TOUCHSCREEN))

    private fun tap(time: Long, x: Float) {
        touch(MotionEvent.ACTION_DOWN, time, x)
        touch(MotionEvent.ACTION_UP, time + 20, x)
    }

    private fun fingerDowns() = CompositorShadow.touches.filter { it.action == 0 }.map { it.x }

    private fun steamDirect(mode: String) {
        SessionPrefs.setPenSetting(activity, "pen", mode)
        invoke("readPrefs")
        SessionState.mode = SessionService.MODE_STEAM
        field("routedTouchMode", SessionPrefs.TOUCH_DIRECT)
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
    }

    private fun penKeyboard() = shadowOf(activity.getSystemService(InputManager::class.java)).addInputDevice(
        InputDeviceBuilder.newBuilder().setId(10).setName("OPPO AI Pen").setSources(InputDevice.SOURCE_KEYBOARD).setExternal(true)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC).build())

    private fun penKey(action: Int, time: Long) =
        activity.dispatchKeyEvent(KeyEvent(time, time, action, KeyEvent.KEYCODE_UNKNOWN, 0, 0, 10, 191, 0, InputDevice.SOURCE_KEYBOARD))

    private fun event(action: Int, time: Long, vararg pointers: Pair<Int, Float>, source: Int = stylusScreen, flags: Int = 0,
        buttons: Int = 0): MotionEvent {
        val properties = Array(pointers.size) { MotionEvent.PointerProperties().apply { id = it; toolType = pointers[it].first } }
        val coords = Array(pointers.size) { MotionEvent.PointerCoords().apply { x = pointers[it].second; y = 300f; pressure = 0.5f } }
        return MotionEvent.obtain(0, time, action, pointers.size, properties, coords, 0, buttons, 1f, 1f, 6, 0, source, flags)
    }

    private class Pad : PointerGestures.Listener {
        val moves = mutableListOf<Float>()
        val scrolls = mutableListOf<Float>()
        val presses = mutableListOf<Int>()
        override fun onMove(x: Float, y: Float) { moves += x }
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) { if (pressed) presses += button }
        override fun onWheel(steps: Int) {}
        override fun onLongPress() {}
        override fun onScroll(dx: Float, dy: Float) { scrolls += dx }
    }

    private fun touchpad() = Pad().also { field("touchpad", TouchpadGestures(8f, it)) }

    private fun field(name: String, value: Any) = ReflectionHelpers.setField(activity, name, value)
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name)
    private fun <T> state(name: String): MutableState<T> = ReflectionHelpers.getField(activity, "$name\$delegate")

    private companion object { const val RIGHT_CTRL = 97 }

    data class Pen(val flags: Int, val x: Int, val y: Int)
    data class Touch(val action: Int, val id: Int, val x: Int, val y: Int)

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val pens = mutableListOf<Pen>()
            val touches = mutableListOf<Touch>()
            val pointers = mutableListOf<Triple<Int, Int, Int>>()
            val keys = mutableListOf<Pair<Int, Int>>()
            fun clear() { pens.clear(); touches.clear(); pointers.clear(); keys.clear() }
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendPen(flags: Int, x: Int, y: Int) { pens += Pen(flags, x, y) }
            @JvmStatic @Implementation fun nativeSendTouch(action: Int, id: Int, x: Int, y: Int) { touches += Touch(action, id, x, y) }
            @JvmStatic @Implementation fun nativeSendPointer(action: Int, x: Int, y: Int) { pointers += Triple(action, x, y) }
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) {}
            @JvmStatic @Implementation fun nativeSendKey(code: Int, value: Int) { keys += code to value }
            @JvmStatic @Implementation fun nativeFocusTakesTouch(): Boolean = false
        }
    }
}
