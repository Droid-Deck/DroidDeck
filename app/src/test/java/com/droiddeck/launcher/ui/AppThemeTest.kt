package com.droiddeck.launcher.ui

import android.graphics.Color
import android.view.ContextThemeWrapper
import com.droiddeck.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppThemeTest {
    @Test fun theNavigationBarIsOpaqueAndDarkSoNothingLightShowsThroughIt() {
        val themed = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val attrs = themed.obtainStyledAttributes(intArrayOf(android.R.attr.navigationBarColor))
        val color = attrs.getColor(0, Color.TRANSPARENT)
        attrs.recycle()
        assertEquals(0xFF, Color.alpha(color))
        assertTrue(Color.luminance(color) < 0.1f)
    }
}
