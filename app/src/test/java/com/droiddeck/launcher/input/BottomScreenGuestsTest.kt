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
    private val homes = setOf("com.android.launcher3", "com.retroidpocket.gamelauncher")

    @After fun clear() = DualScreen.setTaken(emptySet())

    @Test fun onlyTheRetroidPocketDuoAndAynThorWatchTheirBottomScreen() {
        assertTrue(BottomScreenGuests.supported("Retroid Pocket Duo", 36))
        assertTrue(BottomScreenGuests.supported(" retroid pocket duo ", 33))
        assertFalse(BottomScreenGuests.supported("Retroid Pocket Duo", 32))
        assertTrue(BottomScreenGuests.supported("AYN Thor", 33))
        assertTrue(BottomScreenGuests.supported("AYN Thor Max", 34))
        assertFalse(BottomScreenGuests.supported("AYN Odin 2", 36))
        assertFalse(BottomScreenGuests.supported("Pixel 8", 36))
    }

    @Test fun anyWindowButTheHomeScreenHoldsTheScreen() {
        assertEquals(Top.HOME, BottomScreenGuests.classify("com.android.launcher3", homes))
        assertEquals(Top.GUEST, BottomScreenGuests.classify("com.android.chrome", homes))
        assertEquals(Top.GUEST, BottomScreenGuests.classify("com.retroidpocket.dualscreen.assistant", homes))
        assertEquals(Top.NONE, BottomScreenGuests.classify(null, homes))
    }

    @Test fun theWindowListDecidesWhenItSeesPastDroidDeck() {
        assertEquals("com.android.chrome", BottomScreenGuests.holder("com.android.chrome", null, homes))
        assertEquals(null, BottomScreenGuests.holder("com.android.launcher3", "com.android.chrome", homes))
    }

    @Test fun anAppOpenedUnderDroidDeckHoldsTheScreenUntilTheListSeesIt() {
        assertEquals("com.google.android.youtube", BottomScreenGuests.holder(null, "com.google.android.youtube", homes))
        assertEquals(null, BottomScreenGuests.holder(null, "com.android.launcher3", homes))
        assertEquals(null, BottomScreenGuests.holder(null, null, homes))
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
