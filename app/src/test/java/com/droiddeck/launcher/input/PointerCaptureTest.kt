package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PointerCaptureTest {
    @Before fun setUp() = PointerCapture.reset()

    @Test fun aCapturedMouseReportsItsPositionAsMovement() {
        val event = event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_MOUSE_RELATIVE, x = 12f, y = -7f)
        try {
            assertTrue(PointerCapture.accepts(event))
            assertArrayEquals(floatArrayOf(12f, -7f), PointerCapture.delta(event), 0.001f)
        } finally {
            event.recycle()
        }
    }

    @Test fun aCapturedTouchpadReportsItsRelativeAxesNotTheFingerPosition() {
        val event = event(
            MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD,
            x = 800f, y = 400f, relativeX = 3f, relativeY = -5f,
        )
        try {
            assertTrue(PointerCapture.accepts(event))
            assertTrue(PointerCapture.isTouchpad(event))
            assertArrayEquals(floatArrayOf(3f, -5f), PointerCapture.delta(event), 0.001f)
        } finally {
            event.recycle()
        }
    }

    @Test fun anUncapturedMouseIsNotACapturedPointer() {
        val event = event(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE, x = 12f, y = -7f)
        try {
            assertFalse(PointerCapture.accepts(event))
        } finally {
            event.recycle()
        }
    }

    @Test fun aCapturedTouchpadWithoutRelativeAxesFallsBackToItsPositions() {
        val down = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, x = 500f, y = 300f)
        try {
            PointerCapture.track(down)
        } finally {
            down.recycle()
        }
        val move = event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD, x = 510f, y = 295f)
        try {
            assertArrayEquals(floatArrayOf(10f, -5f), PointerCapture.delta(move), 0.001f)
        } finally {
            move.recycle()
        }
    }

    @Test fun theRelativeAxesWinWhenTheTouchpadReportsThem() {
        val down = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, x = 500f, y = 300f)
        try {
            PointerCapture.track(down)
        } finally {
            down.recycle()
        }
        val move = event(
            MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD,
            x = 800f, y = 400f, relativeX = 3f, relativeY = -5f,
        )
        try {
            assertArrayEquals(floatArrayOf(3f, -5f), PointerCapture.delta(move), 0.001f)
        } finally {
            move.recycle()
        }
    }

    @Test fun aLiftedTouchpadPointerStartsFromANewPosition() {
        val down = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, x = 500f, y = 300f)
        try {
            PointerCapture.track(down)
        } finally {
            down.recycle()
        }
        val up = event(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHPAD, x = 500f, y = 300f)
        try {
            PointerCapture.track(up)
        } finally {
            up.recycle()
        }
        val move = event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD, x = 800f, y = 400f)
        try {
            assertArrayEquals(floatArrayOf(0f, 0f), PointerCapture.delta(move), 0.001f)
        } finally {
            move.recycle()
        }
    }

    @Test fun endingOneTouchpadKeepsAnotherDevicesPosition() {
        val downA = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, x = 500f, y = 300f, device = 1)
        try {
            PointerCapture.track(downA)
        } finally {
            downA.recycle()
        }
        val downB = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, x = 600f, y = 400f, device = 2)
        try {
            PointerCapture.track(downB)
        } finally {
            downB.recycle()
        }
        val upA = event(MotionEvent.ACTION_UP, InputDevice.SOURCE_TOUCHPAD, x = 500f, y = 300f, device = 1)
        try {
            PointerCapture.track(upA)
        } finally {
            upA.recycle()
        }
        val moveB = event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD, x = 610f, y = 395f, device = 2)
        try {
            assertArrayEquals(floatArrayOf(10f, -5f), PointerCapture.delta(moveB), 0.001f)
        } finally {
            moveB.recycle()
        }
    }

    @Test fun positionsAreKeptPerDevice() {
        val down = event(MotionEvent.ACTION_DOWN, InputDevice.SOURCE_TOUCHPAD, x = 500f, y = 300f, device = 1)
        try {
            PointerCapture.track(down)
        } finally {
            down.recycle()
        }
        val other = event(MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD, x = 800f, y = 400f, device = 2)
        try {
            assertArrayEquals(floatArrayOf(0f, 0f), PointerCapture.delta(other), 0.001f)
        } finally {
            other.recycle()
        }
    }

    @Test fun batchedTouchpadSamplesAddUp() {
        val event = event(
            MotionEvent.ACTION_MOVE, InputDevice.SOURCE_TOUCHPAD,
            x = 800f, y = 400f, relativeX = 3f, relativeY = -5f,
        )
        try {
            event.addBatch(
                1L,
                arrayOf(coords(810f, 390f, relativeX = 2f, relativeY = 4f)),
                0,
            )
            assertArrayEquals(floatArrayOf(5f, -1f), PointerCapture.delta(event), 0.001f)
        } finally {
            event.recycle()
        }
    }

    private fun coords(x: Float, y: Float, relativeX: Float, relativeY: Float) =
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_RELATIVE_X, relativeX)
            setAxisValue(MotionEvent.AXIS_RELATIVE_Y, relativeY)
        }

    private fun event(
        action: Int,
        source: Int,
        x: Float,
        y: Float,
        relativeX: Float = 0f,
        relativeY: Float = 0f,
        device: Int = 0,
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        return MotionEvent.obtain(
            0L, 0L, action, 1, properties,
            arrayOf(coords(x, y, relativeX, relativeY)),
            0, 0, 1f, 1f, device, 0, source, 0,
        )
    }
}
