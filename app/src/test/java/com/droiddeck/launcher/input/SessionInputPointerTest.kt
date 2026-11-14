package com.droiddeck.launcher.input

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.InputDevice
import android.view.View
import android.view.SurfaceView
import android.view.Surface
import android.view.SurfaceHolder
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionInputPointerTest.CompositorShadow::class, SessionInputPointerTest.WriterShadow::class])
class SessionInputPointerTest {
    private lateinit var activity: SessionActivity
    private lateinit var output: InputOutput
    private var pointerUiActivity: Activity? = null
    private var previousSize = SessionState.outputSize
    private var previousMode = SessionState.mode
    private var previousSuspended = SessionState.suspended

    @Before fun setUp() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        output = ReflectionHelpers.getField(activity, "inputOutput")
        ReflectionHelpers.setField(activity, "surfaceView", SurfaceView(activity).apply { layout(0, 0, 1000, 600) })
        SessionState.outputSize = 1600 to 900
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionState.suspended = false
        InputProfiles.resetAll(activity)
        InputProfiles.select(activity, InputProfile.DESKTOP)
        refresh()
        CompositorShadow.pointers.clear()
        CompositorShadow.events.clear()
        CompositorShadow.keys.clear()
        CompositorShadow.gestures.clear()
        WriterShadow.mouse.clear()
        WriterShadow.beforeWheel = null
    }

    @After fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        SessionState.outputSize = previousSize
        SessionState.mode = previousMode
        SessionState.suspended = previousSuspended
        pointerUiActivity?.finish()
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
        output.pointer(-400f, -225f)
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

    @Test fun desktopRightCtrlUsesUpstreamAliasWithoutReleasingTheProfileHold() {
        fun hardwareCtrl(down: Boolean) = ReflectionHelpers.callInstanceMethod<Unit>(activity, "sendGuestKey",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 97),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, down),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 8))
        output.key(29, true)
        hardwareCtrl(true)
        hardwareCtrl(false)
        assertEquals(listOf(29 to 1), CompositorShadow.keys)
        output.key(29, false)
        assertEquals(listOf(29 to 1, 29 to 0), CompositorShadow.keys)
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

    @Test fun focusCleanupReleasesHardwareAndKeyboardKeysWithoutReleasingAnotherOwner() {
        val held = ReflectionHelpers.getField<HeldInput>(activity, "heldKeys")
        output.key(29, true)
        for ((owner, code) in listOf(8 to 30, 16 to 31, 32 to 32, 16 to 29)) held.set(owner, code, true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "releaseHeldGuestInput")
        assertEquals(setOf(30 to 0, 31 to 0, 32 to 0), CompositorShadow.keys.filter { it.second == 0 }.toSet())
        output.key(29, false)
        assertEquals(29 to 0, CompositorShadow.keys.last())
    }

    @Test fun horizontalWheelUsesTheSameDirectionInBothMouseBackends() {
        output.scrollHorizontal(2)
        assertEquals(listOf(Triple(11, 0, 240)), CompositorShadow.events)
        CompositorShadow.events.clear()
        ReflectionHelpers.setField(activity, "mouseWriter", FakeInputWriter(activity.cacheDir.path, FakeInputWriter.MOUSE_SLOT))
        ReflectionHelpers.setField(activity, "evdevMouseActive", true)
        output.scrollHorizontal(2)
        assertEquals(listOf("wheel:0:2:120"), WriterShadow.mouse)
        assertTrue(CompositorShadow.events.isEmpty())
    }

    @Test fun physicalWheelDirectionsAgreeAcrossWaylandAndEvdev() {
        fun wheel(x: Float, y: Float) {
            val coordinates = MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_HSCROLL, x)
                setAxisValue(MotionEvent.AXIS_VSCROLL, y)
            }
            val event = MotionEvent.obtain(1, 1, MotionEvent.ACTION_SCROLL, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
                arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, 0, 0)
            try { ReflectionHelpers.getField<MouseWheelInput>(activity, "mouseWheel").onEvent(event) }
            finally { event.recycle() }
        }
        wheel(1f, 1f)
        wheel(-1f, -1f)
        assertEquals(listOf(Triple(11, -120, 120), Triple(11, 120, -120)), CompositorShadow.events)
        CompositorShadow.events.clear()
        ReflectionHelpers.setField(activity, "mouseWriter", FakeInputWriter(activity.cacheDir.path, FakeInputWriter.MOUSE_SLOT))
        ReflectionHelpers.setField(activity, "evdevMouseActive", true)
        wheel(1f, 1f)
        wheel(-1f, -1f)
        assertEquals(listOf("wheel120:120:120", "wheel120:-120:-120"), WriterShadow.mouse)
        assertTrue(CompositorShadow.events.isEmpty())
    }

    @Test fun backgroundMouseButtonsReachTheMainThreadInPressReleaseOrder() {
        val windows = com.droiddeck.launcher.session.LinuxWindows
        val owner = ReflectionHelpers.getField<MutableState<String?>>(windows, "owner\$delegate")
        val previous = owner.value
        try {
            owner.value = "test-window"
            val thread = Thread {
                output.button(272, true)
                output.button(272, false)
            }
            thread.start()
            thread.join(5000)
            assertFalse(thread.isAlive)
            assertEquals("test-window", windows.owner)
            assertTrue(CompositorShadow.events.isEmpty())
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(windows.owner)
            assertEquals(listOf(Triple(28, 0, 0), Triple(3, 272, 1), Triple(3, 272, 0)), CompositorShadow.events)
        } finally {
            owner.value = previous
        }
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

    @Test fun wowFieldSelectionPreservesCtrlHeldByTheInputProfile() {
        output.key(29, true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "selectGameField",
            ReflectionHelpers.ClassParameter.from(IntArray::class.java, intArrayOf(46)))
        assertEquals(listOf(29 to 1, 30 to 1, 30 to 0, 46 to 1, 46 to 0), CompositorShadow.keys)
        output.key(29, false)
        assertEquals(29 to 0, CompositorShadow.keys.last())
    }

    @Test fun horizontalWheelFinishesBeforeRawMouseDestruction() {
        checkWheelTransition(horizontal = true, transition = "releaseEvdevMouse", marker = "destroy")
    }

    @Test fun verticalWheelFinishesBeforeRawMouseSoftRelease() {
        checkWheelTransition(horizontal = false, transition = "closeEvdevMouse", marker = "release")
    }

    @Config(sdk = [28, 34])
    @Test fun uiOwnedTouchpadClicksReachTheViewHierarchyWithoutGuestInput() {
        var clicks = 0
        val button = Button(activity).apply { setOnClickListener { clicks++ } }
        installPointerView(button)
        val flags = listOf("drawerOpenState", "pcKeyboardOpen", "achievementsOpen", "inputSettingsOpen",
            "gestureSettingsOpen", "touchEditorOpen", "bottomOutputActive", "glassesOutputActive", "wowChatOnTop")
        for (flag in flags) {
            state<Boolean>(flag).value = true
            touchpadClick(button)
            state<Boolean>(flag).value = false
        }
        SessionState.suspended = true
        touchpadClick(button)
        assertEquals(flags.size + 1, clicks)
        assertTrue(CompositorShadow.events.isEmpty())
        assertTrue(CompositorShadow.gestures.isEmpty())
    }

    @Config(sdk = [28, 34])
    @Test fun uiOwnedTouchpadHoverAndScrollReachTheViewHierarchy() {
        val actions = mutableListOf<Int>()
        val view = object : View(activity) {
            override fun onGenericMotionEvent(event: MotionEvent): Boolean {
                actions += event.actionMasked
                return true
            }
            override fun onHoverEvent(event: MotionEvent): Boolean {
                actions += event.actionMasked
                return true
            }
        }
        installPointerView(view)
        state<Boolean>("pcKeyboardOpen").value = true
        for (action in listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL)) {
            val event = touchpadEvent(action, view)
            try { assertTrue(activity.dispatchGenericMotionEvent(event)) }
            finally { event.recycle() }
        }
        assertTrue(actions.containsAll(listOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_SCROLL)))
        assertTrue(CompositorShadow.events.isEmpty())
        assertTrue(CompositorShadow.gestures.isEmpty())
    }

    @Config(sdk = [28, 34])
    @Test fun openingUiReleasesAGuestTouchpadHoldBeforeDeliveringTheClick() {
        val surface = guestTouchpadSurface()
        val down = touchpadEvent(MotionEvent.ACTION_DOWN, surface, MotionEvent.BUTTON_PRIMARY)
        try { assertTrue(activity.dispatchTouchEvent(down)) }
        finally { down.recycle() }
        assertEquals(listOf(Triple(3, 272, 1)), CompositorShadow.events)
        val gestures = CompositorShadow.gestures.toList()
        var clicked = false
        val button = Button(activity).apply {
            setOnClickListener {
                assertEquals(listOf(Triple(3, 272, 1), Triple(3, 272, 0)), CompositorShadow.events)
                clicked = true
            }
        }
        installPointerView(button)
        state<Boolean>("drawerOpenState").value = true
        touchpadClick(button)
        assertTrue(clicked)
        assertEquals(gestures, CompositorShadow.gestures)
        state<Boolean>("drawerOpenState").value = false
        val lateUp = touchpadEvent(MotionEvent.ACTION_UP, surface)
        try { assertTrue(activity.dispatchTouchEvent(lateUp)) }
        finally { lateUp.recycle() }
        assertEquals(listOf(Triple(3, 272, 1), Triple(3, 272, 0)), CompositorShadow.events)
    }

    @Config(sdk = [28, 34])
    @Test fun touchpadClicksStillReachTheGuestWhenNoUiOwnsThePointer() {
        val surface = guestTouchpadSurface()
        touchpadClick(surface)
        assertEquals(listOf(Triple(3, 272, 1), Triple(3, 272, 0)), CompositorShadow.events)
        assertTrue(CompositorShadow.gestures.any { it == 10 })
    }

    private fun guestTouchpadSurface(): SurfaceView =
        object : SurfaceView(activity) {
            private val testSurface = Surface(SurfaceTexture(0))
            private val testHolder = object : SurfaceHolder by super.getHolder() {
                override fun getSurface() = testSurface
            }
            override fun getHolder() = testHolder
            override fun isShown() = true
        }.apply {
            layout(0, 0, 1000, 600)
            ReflectionHelpers.setField(activity, "surfaceView", this)
            assertTrue(holder.surface.isValid)
        }

    private fun installPointerView(view: View) {
        if (pointerUiActivity == null) {
            pointerUiActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
            shadowOf(activity).setWindow(pointerUiActivity!!.window)
        }
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) })
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1000, 600)
        }
        assertTrue(view.isAttachedToWindow)
    }

    private fun touchpadClick(view: View) {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = touchpadEvent(action, view, if (action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0)
            try {
                assertEquals(AndroidPointerSource.Kind.TOUCHPAD, AndroidPointerSource.kind(event))
                assertTrue(activity.dispatchTouchEvent(event))
            } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun touchpadEvent(action: Int, view: View, buttons: Int = 0): MotionEvent {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return MotionEvent.obtain(100, 110, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
            arrayOf(MotionEvent.PointerCoords().apply { x = at[0] + view.width / 2f; y = at[1] + view.height / 2f; pressure = 1f }),
            0, buttons, 1f, 1f, 1, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    private fun checkWheelTransition(horizontal: Boolean, transition: String, marker: String) {
        ReflectionHelpers.setField(activity, "mouseWriter", FakeInputWriter(activity.cacheDir.path, FakeInputWriter.MOUSE_SLOT))
        ReflectionHelpers.setField(activity, "evdevMouseActive", true)
        val entered = CountDownLatch(1)
        val continueWrite = CountDownLatch(1)
        val transitioning = CountDownLatch(1)
        val finished = CountDownLatch(1)
        WriterShadow.beforeWheel = {
            entered.countDown()
            check(continueWrite.await(5, TimeUnit.SECONDS))
        }
        val executor = Executors.newFixedThreadPool(2)
        fun scroll() { if (horizontal) output.scrollHorizontal(2) else output.scroll(2) }
        try {
            val writing = executor.submit { scroll() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = executor.submit {
                transitioning.countDown()
                invoke(transition)
                finished.countDown()
            }
            assertTrue(transitioning.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            continueWrite.countDown()
            writing.get(5, TimeUnit.SECONDS)
            closing.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(if (horizontal) "wheel:0:2:120" else "wheel:-2:0:120", marker), WriterShadow.mouse)
            scroll()
            assertEquals(2, WriterShadow.mouse.size)
            assertEquals(listOf(if (horizontal) Triple(11, 0, 240) else Triple(4, 2, 0)), CompositorShadow.events)
        } finally {
            continueWrite.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            WriterShadow.beforeWheel = null
        }
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
            val gestures = mutableListOf<Int>()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSendPointer(action: Int, x: Int, y: Int) { pointers += Triple(action, x, y) }
            @JvmStatic @Implementation fun nativeSendSceneInput(type: Int, code: Int, value: Int) { events += Triple(type, code, value) }
            @JvmStatic @Implementation fun nativeSendKey(code: Int, value: Int) { keys += code to value }
            @JvmStatic @Implementation fun nativePointerGesture(key: String?, generation: Long, type: Int,
                a: Float, b: Float, c: Float, d: Float, time: Long) { gestures += type }
        }
    }

    @Implements(value = FakeInputWriter::class, isInAndroidSdk = false)
    class WriterShadow {
        @Implementation fun writeMouseWheel120(vertical: Int, horizontal: Int) { mouse += "wheel120:$vertical:$horizontal" }
        @Implementation fun writeMouseButton(code: Short, down: Boolean) { mouse += "button:$code:$down" }
        @Implementation fun writeMouseDelta(dx: Float, dy: Float) { mouse += "delta:$dx:$dy" }
        @Implementation fun writeMouseWheel(vertical: Int, horizontal: Int, value120: Int) {
            beforeWheel?.invoke()
            mouse += "wheel:$vertical:$horizontal:$value120"
        }
        @Implementation fun softRelease() { mouse += "release" }
        @Implementation fun destroy() { mouse += "destroy" }
        companion object {
            val mouse: MutableList<String> = Collections.synchronizedList(mutableListOf())
            @Volatile var beforeWheel: (() -> Unit)? = null
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun writeDeckControls(index: Int, mask: Int, pads: ShortArray, pressure: ShortArray) {}
        }
    }
}
