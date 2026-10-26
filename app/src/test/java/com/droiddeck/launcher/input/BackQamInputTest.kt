package com.droiddeck.launcher.input

import android.os.Looper
import android.view.KeyEvent
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackQamInputTest {
    private val changes = mutableListOf<Boolean>()
    private val taps = mutableListOf<Boolean>()
    private val input = BackQamInput(changes::add, taps::add)

    @Test fun singleTapWaitsForTheDoubleTapWindowWithoutPressingQam() {
        send(KeyEvent.ACTION_DOWN)
        advance(100)
        send(KeyEvent.ACTION_UP)
        advance(499)
        assertTrue(taps.isEmpty())
        advance(1)
        assertEquals(listOf(false), taps)
        assertTrue(changes.isEmpty())
    }

    @Test fun doubleTapProducesOnlyTheDoubleAction() {
        repeat(2) {
            send(KeyEvent.ACTION_DOWN)
            advance(100)
            send(KeyEvent.ACTION_UP)
            advance(100)
        }
        advance(1000)
        assertEquals(listOf(true), taps)
        assertTrue(changes.isEmpty())
    }

    @Test fun aHoldStartsAt700msAndNeverProducesATap() {
        assertTrue(send(KeyEvent.ACTION_DOWN))
        advance(699)
        assertTrue(changes.isEmpty())
        advance(1)
        for (repeat in 1..10) assertTrue(send(KeyEvent.ACTION_DOWN, repeat = repeat))
        advance(4000)
        assertEquals(listOf(true), changes)
        assertTrue(send(KeyEvent.ACTION_UP))
        advance(1000)
        assertEquals(listOf(true, false), changes)
        assertTrue(taps.isEmpty())
    }

    @Test fun releasingBetween500And700msStillProducesASingleTap() {
        send(KeyEvent.ACTION_DOWN)
        advance(600)
        send(KeyEvent.ACTION_UP)
        advance(500)
        assertEquals(listOf(false), taps)
        assertTrue(changes.isEmpty())
    }

    @Test fun holdingTheSecondPressCancelsTheFirstTap() {
        send(KeyEvent.ACTION_DOWN)
        send(KeyEvent.ACTION_UP)
        advance(400)
        send(KeyEvent.ACTION_DOWN)
        advance(699)
        assertTrue(taps.isEmpty())
        advance(1)
        assertEquals(listOf(true), changes)
        send(KeyEvent.ACTION_UP)
        advance(1000)
        assertTrue(taps.isEmpty())
    }

    @Test fun otherButtonsAndSelectPassThroughWhileBackIsHeld() {
        send(KeyEvent.ACTION_DOWN)
        advance(700)
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_SELECT)) {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                assertFalse(input.onKeyEvent(KeyEvent(action, code), true))
            }
        }
        assertEquals(listOf(true), changes)
        send(KeyEvent.ACTION_UP)
        assertEquals(listOf(true, false), changes)
    }

    @Test fun disabledRoutingPreservesBackNavigation() {
        assertFalse(send(KeyEvent.ACTION_DOWN, enabled = false))
        assertFalse(send(KeyEvent.ACTION_UP, enabled = false))
        assertTrue(changes.isEmpty())
        assertTrue(taps.isEmpty())
    }

    @Test fun losingFocusReleasesQamAndConsumesTheLateRelease() {
        send(KeyEvent.ACTION_DOWN)
        advance(700)
        input.cancel()
        input.cancel()
        assertTrue(send(KeyEvent.ACTION_DOWN, repeat = 1, enabled = false))
        assertTrue(send(KeyEvent.ACTION_UP, enabled = false))
        advance(1000)
        assertEquals(listOf(true, false), changes)
        assertTrue(taps.isEmpty())
    }

    @Test fun cancellationBeforeTheThresholdPreventsBothHoldAndTap() {
        send(KeyEvent.ACTION_DOWN)
        advance(100)
        input.cancel()
        send(KeyEvent.ACTION_UP)
        advance(1000)
        assertTrue(changes.isEmpty())
        assertTrue(taps.isEmpty())
    }

    @Test fun cancellationAlsoDiscardsAPendingSingleTap() {
        send(KeyEvent.ACTION_DOWN)
        send(KeyEvent.ACTION_UP)
        input.cancel()
        advance(1000)
        assertTrue(taps.isEmpty())
    }

    @Test fun openingAnOverlayReleasesQamBeforeRoutingItsNextKey() {
        send(KeyEvent.ACTION_DOWN)
        advance(700)
        assertFalse(input.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B), false))
        assertTrue(send(KeyEvent.ACTION_UP, enabled = false))
        assertEquals(listOf(true, false), changes)
    }

    @Test fun repeatsAfterCancellationCannotReassertQam() {
        send(KeyEvent.ACTION_DOWN)
        advance(700)
        input.cancel()
        assertTrue(send(KeyEvent.ACTION_DOWN, repeat = 1))
        advance(1000)
        assertEquals(listOf(true, false), changes)
        assertTrue(send(KeyEvent.ACTION_DOWN))
        advance(700)
        assertTrue(send(KeyEvent.ACTION_UP))
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test fun cancelledReleaseNeverProducesATap() {
        send(KeyEvent.ACTION_DOWN)
        assertTrue(input.onKeyEvent(KeyEvent(0, 100, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK,
            0, 0, 4, 0, KeyEvent.FLAG_CANCELED), true))
        advance(1000)
        assertTrue(changes.isEmpty())
        assertTrue(taps.isEmpty())
    }

    @Test fun removingOneDeviceDoesNotReleaseAnotherDevicesHold() {
        send(KeyEvent.ACTION_DOWN, device = 4)
        send(KeyEvent.ACTION_DOWN, device = 5)
        advance(700)
        input.onDeviceRemoved(4)
        assertEquals(listOf(true), changes)
        send(KeyEvent.ACTION_UP, device = 5)
        assertEquals(listOf(true, false), changes)
        assertTrue(taps.isEmpty())
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun send(action: Int, repeat: Int = 0, device: Int = 4, enabled: Boolean = true) =
        input.onKeyEvent(KeyEvent(0, 100, action, KeyEvent.KEYCODE_BACK, repeat, 0, device, 0), enabled)
}
