package com.droiddeck.launcher.input

import android.os.Looper
import android.view.accessibility.AccessibilityWindowInfo
import com.droiddeck.launcher.input.BottomScreenGuests.Top
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BottomScreenGuestsTest {
    private val own = "com.droiddeck.launcher"
    private val homes = setOf("com.android.launcher3", "com.retroidpocket.gamelauncher")

    @After fun clear() = DualScreen.setTaken(emptySet())

    @Test fun onlyTheRetroidPocketDuoWatchesItsBottomScreen() {
        assertTrue(BottomScreenGuests.supported("Retroid Pocket Duo", 36))
        assertTrue(BottomScreenGuests.supported(" retroid pocket duo ", 33))
        assertFalse(BottomScreenGuests.supported("Retroid Pocket Duo", 32))
        assertFalse(BottomScreenGuests.supported("AYN Thor", 36))
        assertFalse(BottomScreenGuests.supported("Pixel 8", 36))
    }

    @Test fun theTopWindowDecidesWhoHoldsTheScreen() {
        assertEquals(Top.OWN, BottomScreenGuests.classify(own, own, homes))
        assertEquals(Top.HOME, BottomScreenGuests.classify("com.android.launcher3", own, homes))
        assertEquals(Top.GUEST, BottomScreenGuests.classify("com.android.chrome", own, homes))
        assertEquals(Top.GUEST, BottomScreenGuests.classify("com.retroidpocket.dualscreen.assistant", own, homes))
        assertEquals(Top.NONE, BottomScreenGuests.classify(null, own, homes))
    }

    @Test fun anotherAppTakesTheScreenAndHomeOrNothingGivesItBack() {
        assertTrue(BottomScreenGuests.taken(Top.GUEST, hiddenArrival = false))
        assertTrue("an app opened under DroidDeck's panel", BottomScreenGuests.taken(Top.OWN, hiddenArrival = true))
        assertFalse(BottomScreenGuests.taken(Top.OWN, hiddenArrival = false))
        assertFalse(BottomScreenGuests.taken(Top.HOME, hiddenArrival = true))
        assertFalse(BottomScreenGuests.taken(Top.NONE, hiddenArrival = true))
    }

    @Test fun keyboardsAndOverlaysNeverTakeTheScreen() {
        assertTrue(BottomScreenGuests.ignorable(AccessibilityWindowInfo.TYPE_INPUT_METHOD))
        assertTrue(BottomScreenGuests.ignorable(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY))
        assertFalse(BottomScreenGuests.ignorable(AccessibilityWindowInfo.TYPE_APPLICATION))
        assertFalse(BottomScreenGuests.ignorable(AccessibilityWindowInfo.TYPE_SYSTEM))
    }

    @Test fun listenersHearWhenTheScreenIsTakenAndFreed() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        DualScreen.watchTaken(listener)
        DualScreen.setTaken(setOf(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DualScreen.taken(2))
        assertFalse(DualScreen.taken(0))
        DualScreen.setTaken(setOf(2))
        DualScreen.setTaken(emptySet())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(DualScreen.taken(2))
        assertEquals(2, calls)
        DualScreen.unwatchTaken(listener)
        DualScreen.setTaken(setOf(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, calls)
    }
}
