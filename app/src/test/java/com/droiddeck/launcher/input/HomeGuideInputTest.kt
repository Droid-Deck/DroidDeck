package com.droiddeck.launcher.input

import android.app.Application
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, application = Application::class)
class HomeGuideInputTest {
    private val states = mutableListOf<Boolean>()
    private var cancellations = 0
    private val input = HomeGuideInput({ states.add(it) }, { cancellations++ })

    private fun home(action: Int, device: Int = 10, repeat: Int = 0, down: Long = 10) =
        KeyEvent(down, down + 100, action, KeyEvent.KEYCODE_HOME, repeat, 0, device, 102)

    @Test fun guideIsHeldImmediatelyAndReleaseDoesNotDependOnAndroidDownTime() {
        assertTrue(input.onKeyEvent(home(KeyEvent.ACTION_DOWN), true))
        assertEquals(listOf(true), states)
        assertTrue(input.onKeyEvent(home(KeyEvent.ACTION_DOWN, repeat = 1), true))
        assertFalse(input.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X), true))
        assertTrue(input.onKeyEvent(home(KeyEvent.ACTION_UP, down = 77), true))
        assertEquals(listOf(true, false), states)
    }

    @Test fun genericLayoutMoveHomeHoldsGuideLikeHome() {
        val down = KeyEvent(10, 110, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME, 0, 0, 10, 102)
        val up = KeyEvent(10, 200, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MOVE_HOME, 0, 0, 10, 102)
        assertTrue(input.onKeyEvent(down, true))
        assertTrue(input.onKeyEvent(up, true))
        assertEquals(listOf(true, false), states)
    }

    @Test fun anUnownedHomePressKeepsAndroidBehaviorEvenIfSessionBecomesEligibleBeforeRelease() {
        assertFalse(input.onKeyEvent(home(KeyEvent.ACTION_DOWN), false))
        assertFalse(input.onKeyEvent(home(KeyEvent.ACTION_UP), true))
        assertTrue(states.isEmpty())
    }

    @Test fun focusLossReleasesGuideAndConsumesTheOwnedReleaseWithoutAnotherTap() {
        input.onKeyEvent(home(KeyEvent.ACTION_DOWN), true)
        input.cancel()
        assertEquals(1, cancellations)
        assertTrue(input.onKeyEvent(home(KeyEvent.ACTION_DOWN, repeat = 2), false))
        assertTrue(input.onKeyEvent(home(KeyEvent.ACTION_UP), false))
        assertEquals(listOf(true, false), states)
        assertFalse(input.onKeyEvent(home(KeyEvent.ACTION_UP), false))
    }

    @Test fun disablingDuringAHoldReleasesItAndDoesNotStealTheNextAndroidHomePress() {
        input.onKeyEvent(home(KeyEvent.ACTION_DOWN), true)
        assertTrue(input.onKeyEvent(home(KeyEvent.ACTION_UP), false))
        assertEquals(listOf(true, false), states)
        assertFalse(input.onKeyEvent(home(KeyEvent.ACTION_DOWN), false))
    }

    @Test fun guideStaysHeldUntilAllOwnersReleaseAndDisconnectCancelsTheLastOwner() {
        input.onKeyEvent(home(KeyEvent.ACTION_DOWN), true)
        input.onKeyEvent(home(KeyEvent.ACTION_DOWN, device = 11), true)
        input.onKeyEvent(home(KeyEvent.ACTION_UP), true)
        assertEquals(listOf(true), states)
        input.onDeviceRemoved(11)
        assertEquals(listOf(true, false), states)
        assertEquals(1, cancellations)
    }
}
