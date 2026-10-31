package com.droiddeck.launcher.input

import android.os.Looper
import android.view.SurfaceView
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
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
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionInputPointerTest.CompositorShadow::class, SessionInputPointerTest.WriterShadow::class])
class SessionInputPointerTest {
    private lateinit var activity: SessionActivity
    private lateinit var output: InputOutput
    private var previousSize = SessionState.outputSize
    private var previousMode = SessionState.mode

    @Before fun setUp() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        output = ReflectionHelpers.getField(activity, "inputOutput")
        ReflectionHelpers.setField(activity, "surfaceView", SurfaceView(activity).apply { layout(0, 0, 1000, 600) })
        SessionState.outputSize = 1600 to 900
        SessionState.mode = SessionService.MODE_DESKTOP
        InputProfiles.resetAll(activity)
        InputProfiles.select(activity, InputProfile.DESKTOP)
        refresh()
        CompositorShadow.pointers.clear()
        CompositorShadow.events.clear()
        CompositorShadow.keys.clear()
        WriterShadow.mouse.clear()
    }

    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        SessionState.outputSize = previousSize
        SessionState.mode = previousMode
    }

    @Test fun desktopMouseButtonsKeepTheVisibleCursorAndDeliverImmediately() {
        state<Offset>("cursorPos").value = Offset(500f, 300f)
        state<Boolean>("cursorVisible").value = true
        output.button(272, true)
        invoke("onPadInput")
        output.button(272, false)
        assertTrue(state<Boolean>("cursorVisible").value)
        assertEquals(listOf(Triple(3, 272, 1), Triple(3, 272, 0)), CompositorShadow.events)
    }

    @Test fun nativeGameInputStillHidesCursor() {
        InputProfiles.select(activity, InputProfile.GAME)
        refresh()
        state<Boolean>("cursorVisible").value = true
        invoke("onPadInput")
        assertFalse(state<Boolean>("cursorVisible").value)
    }

    @Test fun lowerScreenPointerBindingAlsoKeepsCursorVisible() {
        InputProfiles.save(activity, InputProfile(InputProfile.GAME).withBinding(InputRole.SECONDARY, InputControl.RIGHT_PAD, InputAction.Pointer()))
        InputProfiles.select(activity, InputProfile.GAME)
        refresh()
        state<Boolean>("cursorVisible").value = true
        invoke("onPadInput")
        assertTrue(state<Boolean>("cursorVisible").value)
    }

    @Test fun stickContinuesFromTrackpadPositionAndUpdatesVisibleAndGuestCoordinates() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "moveSecondScreenPointer",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 250f),
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 150f),
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 1000f),
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 600f))
        output.pointer(160f, 90f)
        invoke("onPadInput")
        assertEquals(Offset(350f, 215.625f), state<Offset>("cursorPos").value)
        assertTrue(state<Boolean>("cursorVisible").value)
        assertEquals(Triple(1, InputSpace.x(350f, 1000), InputSpace.y(215.625f, 600)), CompositorShadow.pointers.last())
    }

    @Test fun firstStickMovementStartsAtCenterAndClampsToPictureBounds() {
        output.pointer(160f, 90f)
        assertEquals(Offset(600f, 356.25f), state<Offset>("cursorPos").value)
        output.pointer(10000f, -10000f)
        assertEquals(Offset(1000f, 18.75f), state<Offset>("cursorPos").value)
    }

    @Test fun stickFrameNotifiesCursorDrawingBeforeReturningToTheLooper() {
        val position = state<Offset>("cursorPos")
        Snapshot.sendApplyNotifications()
        var changed = false
        val observer = Snapshot.registerApplyObserver { objects, _ ->
            if (position in objects) changed = true
        }
        try {
            output.pointer(20f, 10f)
            assertTrue(changed)
        } finally { observer.dispose() }
    }

    @Test fun pointerLockKeepsRelativeMotionAndHiddenCursor() {
        ReflectionHelpers.setField(activity, "pointerLocked", true)
        state<Boolean>("cursorVisible").value = true
        output.pointer(2.5f, -1.25f)
        assertEquals(listOf(Triple(6, 640, -320)), CompositorShadow.events)
        assertTrue(CompositorShadow.pointers.isEmpty())
        assertFalse(state<Boolean>("cursorVisible").value)
    }

    @Test fun clientRequestedHiddenCursorStaysHidden() {
        ReflectionHelpers.setField(activity, "clientHidesCursor", true)
        output.pointer(1f, 1f)
        assertFalse(state<Boolean>("cursorVisible").value)
    }

    @Test fun mappedPointerStaysVisibleWithDirectTouchSelected() {
        ReflectionHelpers.setField(activity, "routedTouchMode", com.droiddeck.launcher.session.SessionPrefs.TOUCH_DIRECT)
        state<Boolean>("directCursorOn").value = false
        output.pointer(20f, 10f)
        assertTrue(state<Boolean>("cursorVisible").value)
    }

    @Test fun touchpadZoomDoesNotReleaseCtrlHeldByTheProfileOrScreenKeys() {
        val held = ReflectionHelpers.getField<HeldInput>(activity, "heldKeys")
        output.key(29, true)
        held.set(64, 29, true)
        ReflectionHelpers.getField<PointerGestures.Listener>(activity, "pointerListener").onZoom(1)
        assertEquals(listOf(29 to 1), CompositorShadow.keys)
        output.key(29, false)
        assertEquals(listOf(29 to 1), CompositorShadow.keys)
        held.set(64, 29, false)
        assertEquals(listOf(29 to 1, 29 to 0), CompositorShadow.keys)
        assertEquals(listOf(Triple(4, 1, 0)), CompositorShadow.events)
    }

    @Test fun routedMouseUsesRawBackendAndKeepsSharedButtonOwnership() {
        ReflectionHelpers.setField(activity, "mouseWriter", FakeInputWriter(activity.cacheDir.path, FakeInputWriter.MOUSE_SLOT))
        ReflectionHelpers.setField(activity, "evdevMouseActive", true)
        ReflectionHelpers.setField(activity, "pointerLocked", true)
        val held = ReflectionHelpers.getField<HeldInput>(activity, "heldButtons")
        output.button(272, true)
        held.set(2, 272, true)
        output.button(272, false)
        assertEquals(listOf("button:272:true"), WriterShadow.mouse)
        held.set(2, 272, false)
        output.pointer(2.5f, -1.25f)
        output.scroll(2)
        assertEquals(listOf("button:272:true", "button:272:false", "delta:2.5:-1.25", "wheel:-2:0:120"), WriterShadow.mouse)
        assertTrue(CompositorShadow.events.isEmpty())
    }

    @Test fun leavingRawMouseReleasesItsHoldBeforeReturningToTheWaylandSeat() {
        ReflectionHelpers.setField(activity, "mouseWriter", FakeInputWriter(activity.cacheDir.path, FakeInputWriter.MOUSE_SLOT))
        ReflectionHelpers.setField(activity, "evdevMouseActive", true)
        output.button(272, true)
        invoke("closeEvdevMouse")
        output.button(272, false)
        assertEquals(listOf("button:272:true", "button:272:false", "release"), WriterShadow.mouse)
        assertTrue(CompositorShadow.events.isEmpty())
        output.button(272, true)
        output.button(272, false)
        assertEquals(listOf(Triple(3, 272, 1), Triple(3, 272, 0)), CompositorShadow.events)
    }

    private fun refresh() = invoke("refreshInputProfile")
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Unit>(activity, name)
    private fun <T> state(name: String): MutableState<T> = ReflectionHelpers.getField(activity, "$name\$delegate")

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            val pointers = mutableListOf<Triple<Int, Int, Int>>()
            val events = mutableListOf<Triple<Int, Int, Int>>()
            val keys = mutableListOf<Pair<Int, Int>>()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendPointer(action: Int, x: Int, y: Int) { pointers += Triple(action, x, y) }
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) { events += Triple(type, code, value) }
            @JvmStatic @Implementation fun nativeSendKey(code: Int, value: Int) { keys += code to value }
        }
    }

    @Implements(value = FakeInputWriter::class, isInAndroidSdk = false)
    class WriterShadow {
        @Implementation fun writeMouseButton(code: Short, down: Boolean) { mouse += "button:$code:$down" }
        @Implementation fun writeMouseDelta(dx: Float, dy: Float) { mouse += "delta:$dx:$dy" }
        @Implementation fun writeMouseWheel(vertical: Int, horizontal: Int, value120: Int) { mouse += "wheel:$vertical:$horizontal:$value120" }
        @Implementation fun softRelease() { mouse += "release" }
        companion object {
            val mouse = mutableListOf<String>()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun writeDeckControls(index: Int, mask: Int, pads: ShortArray, pressure: ShortArray) {}
        }
    }
}
