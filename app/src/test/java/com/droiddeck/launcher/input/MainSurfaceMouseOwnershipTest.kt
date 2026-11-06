package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSurfaceMouseOwnershipTest {
    @Test fun buttonsAreOwnedPerDeviceAndReleasedIndependentlyOutside() {
        val ownership = MainSurfaceMouseOwnership()
        assertTrue(ownership.handle(1, MainSurfaceMouseEvent.BUTTON_PRESS,
            MotionEvent.BUTTON_PRIMARY, true).forward)
        assertTrue(ownership.handle(1, MainSurfaceMouseEvent.BUTTON_PRESS,
            MotionEvent.BUTTON_SECONDARY, true).forward)
        assertFalse(ownership.handle(2, MainSurfaceMouseEvent.BUTTON_PRESS,
            MotionEvent.BUTTON_PRIMARY, false).forward)

        assertTrue(ownership.handle(1, MainSurfaceMouseEvent.BUTTON_RELEASE,
            MotionEvent.BUTTON_PRIMARY, false).forward)
        assertTrue(ownership.handle(1, MainSurfaceMouseEvent.MOVE, 0, false).forward)
        assertTrue(ownership.handle(1, MainSurfaceMouseEvent.BUTTON_RELEASE,
            MotionEvent.BUTTON_SECONDARY, false).forward)
        assertFalse(ownership.handle(1, MainSurfaceMouseEvent.MOVE, 0, false).forward)

        assertFalse(ownership.handle(2, MainSurfaceMouseEvent.MOVE, 0, true).forward)
        assertFalse(ownership.handle(2, MainSurfaceMouseEvent.BUTTON_RELEASE,
            MotionEvent.BUTTON_PRIMARY, true).forward)
        assertTrue(ownership.handle(2, MainSurfaceMouseEvent.MOVE, 0, true).forward)
    }

    @Test fun hoverAndScrollRequireThePointerToBeInside() {
        val ownership = MainSurfaceMouseOwnership()
        assertFalse(ownership.handle(1, MainSurfaceMouseEvent.HOVER_OR_SCROLL, 0, false).forward)
        assertTrue(ownership.handle(1, MainSurfaceMouseEvent.HOVER_OR_SCROLL, 0, true).forward)
    }

    @Test fun cancelAndClearReturnOnlyAcceptedButtons() {
        val ownership = MainSurfaceMouseOwnership()
        ownership.handle(1, MainSurfaceMouseEvent.BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, true)
        ownership.handle(1, MainSurfaceMouseEvent.BUTTON_PRESS, MotionEvent.BUTTON_SECONDARY, true)
        ownership.handle(2, MainSurfaceMouseEvent.BUTTON_PRESS, MotionEvent.BUTTON_TERTIARY, true)
        ownership.handle(3, MainSurfaceMouseEvent.BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, false)

        assertEquals(
            MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_SECONDARY,
            ownership.handle(1, MainSurfaceMouseEvent.CANCEL, 0, false).releaseButtons,
        )
        assertEquals(MotionEvent.BUTTON_TERTIARY, ownership.clear())
        assertEquals(0, ownership.clear())
    }

    @Test fun sharedGuestButtonReleasesOnlyAfterTheLastOwningDevice() {
        val ownership = MainSurfaceMouseOwnership()
        assertTrue(ownership.handle(1, MainSurfaceMouseEvent.BUTTON_PRESS,
            MotionEvent.BUTTON_PRIMARY, true).forward)
        assertFalse(ownership.handle(2, MainSurfaceMouseEvent.BUTTON_PRESS,
            MotionEvent.BUTTON_PRIMARY, true).forward)
        assertFalse(ownership.handle(1, MainSurfaceMouseEvent.BUTTON_RELEASE,
            MotionEvent.BUTTON_PRIMARY, false).forward)
        assertTrue(ownership.handle(2, MainSurfaceMouseEvent.MOVE, 0, false).forward)
        assertTrue(ownership.handle(2, MainSurfaceMouseEvent.BUTTON_RELEASE,
            MotionEvent.BUTTON_PRIMARY, false).forward)
    }

    @Test fun deviceRemovalReleasesOnlyButtonsNotOwnedByAnotherDevice() {
        val ownership = MainSurfaceMouseOwnership()
        ownership.handle(1, MainSurfaceMouseEvent.BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, true)
        ownership.handle(1, MainSurfaceMouseEvent.BUTTON_PRESS, MotionEvent.BUTTON_SECONDARY, true)
        ownership.handle(2, MainSurfaceMouseEvent.BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, true)
        assertEquals(MotionEvent.BUTTON_SECONDARY, ownership.removeDevice(1))
        assertEquals(MotionEvent.BUTTON_PRIMARY, ownership.removeDevice(2))
    }

    @Test fun touchscreenAndStylusContactsStayOnTheSurface() {
        for (source in listOf(InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_STYLUS)) {
            for (action in listOf(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
            )) {
                assertTrue(acceptsMainSurfaceContact(source, action))
            }
        }
        assertFalse(acceptsMainSurfaceContact(InputDevice.SOURCE_STYLUS, MotionEvent.ACTION_HOVER_MOVE))
        assertFalse(acceptsMainSurfaceContact(InputDevice.SOURCE_MOUSE, MotionEvent.ACTION_DOWN))
    }

    @Test fun pointerEventsMissingTheSurfaceAreConsumedByTheActivity() {
        assertTrue(consumesMainSurfaceActivityFallback(InputDevice.SOURCE_TOUCHSCREEN))
        assertTrue(consumesMainSurfaceActivityFallback(InputDevice.SOURCE_STYLUS))
        assertTrue(consumesMainSurfaceActivityFallback(InputDevice.SOURCE_MOUSE))
        assertFalse(consumesMainSurfaceActivityFallback(InputDevice.SOURCE_JOYSTICK))
    }
}
