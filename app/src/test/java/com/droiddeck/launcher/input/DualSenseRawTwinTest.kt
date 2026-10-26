package com.droiddeck.launcher.input

import android.view.KeyEvent
import com.droiddeck.launcher.input.DualSenseManager.Companion.RawTwinCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualSenseRawTwinTest {
    private val sony = 0x054C
    private val dualSense = 0x0CE6
    private val edge = 0x0DF2

    @Test fun theOnlyConnectedUsbDualSenseIsTheClaimedPadsTwin() {
        assertEquals(7, DualSenseManager.pickRawTwin(listOf(RawTwinCandidate(7, sony, dualSense)), sony to dualSense))
    }

    @Test fun tcpOrUnclaimedRawInputNeverSuppressesAnAndroidPad() {
        assertEquals(-1, DualSenseManager.pickRawTwin(listOf(RawTwinCandidate(7, sony, dualSense)), null))
    }

    @Test fun aBluetoothPadIsNeverPickedAsTheUsbPadsTwin() {
        val btOnly = listOf(RawTwinCandidate(9, sony, dualSense, bluetooth = true))
        assertEquals(-1, DualSenseManager.pickRawTwin(btOnly, sony to dualSense))
        val usbAndBt = listOf(
            RawTwinCandidate(7, sony, dualSense, bluetooth = false),
            RawTwinCandidate(9, sony, dualSense, bluetooth = true),
        )
        assertEquals(7, DualSenseManager.pickRawTwin(usbAndBt, sony to dualSense))
    }

    @Test fun aSecondPadIsNeverSuppressedWhenTheTwinIsAmbiguous() {
        val two = listOf(RawTwinCandidate(7, sony, dualSense), RawTwinCandidate(9, sony, dualSense))
        assertEquals(-1, DualSenseManager.pickRawTwin(two, null))
        assertEquals(-1, DualSenseManager.pickRawTwin(two, sony to dualSense))
    }

    @Test fun usbIdsPickTheMatchingModelOutOfMixedPads() {
        val mixed = listOf(RawTwinCandidate(7, sony, dualSense), RawTwinCandidate(9, sony, edge))
        assertEquals(9, DualSenseManager.pickRawTwin(mixed, sony to edge))
        assertEquals(-1, DualSenseManager.pickRawTwin(emptyList(), sony to edge))
    }

    private fun report(lx: Int = 128, cross: Boolean = false): ByteArray = ByteArray(64).also {
        it[0] = 0x01
        it[1] = lx.toByte(); it[2] = 128.toByte(); it[3] = 128.toByte(); it[4] = 128.toByte()
        it[8] = (0x08 or if (cross) 0x20 else 0).toByte()
    }

    @Test fun launcherKeysFollowTheHeldButtonsAndStick() {
        assertTrue(LauncherPadKeys.held(null).isEmpty())
        assertTrue(LauncherPadKeys.held(DualSense.parseInputReport(report())).isEmpty())
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_A), LauncherPadKeys.held(DualSense.parseInputReport(report(cross = true))))
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_LEFT), LauncherPadKeys.held(DualSense.parseInputReport(report(lx = 0))))
    }
}
