package com.droiddeck.launcher.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BottomBrightnessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun followsTheSystemUntilTheUserPicksALevel() {
        assertNull(BottomBrightness.level(context))
        SessionPrefs.setBottomBrightness(context, 40)
        assertEquals(0.4f, BottomBrightness.level(context)!!, 0.001f)
    }

    @Test fun theSliderNeverReachesZero() {
        assertEquals(BottomBrightness.MIN, BottomBrightness.clamp(0))
        assertEquals(BottomBrightness.MIN, BottomBrightness.clamp(-20))
        assertEquals(100, BottomBrightness.clamp(150))
        SessionPrefs.setBottomBrightness(context, 0)
        assertEquals(BottomBrightness.MIN / 100f, BottomBrightness.level(context)!!, 0.001f)
    }

    @Test fun autoDimNeverBrightensADarkerChoice() {
        assertEquals(0.05f, BottomBrightness.dimmed(context, 0.05f), 0.001f)
        SessionPrefs.setBottomBrightness(context, 5)
        assertEquals(0.05f, BottomBrightness.dimmed(context, 0.2f), 0.001f)
        SessionPrefs.setBottomBrightness(context, 80)
        assertEquals(0.05f, BottomBrightness.dimmed(context, 0.05f), 0.001f)
    }
}
