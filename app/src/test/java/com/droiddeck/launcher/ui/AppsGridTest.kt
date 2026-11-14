package com.droiddeck.launcher.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppsGridTest {
    @Test fun anUnknownStoredGridValueFallsBackToTheDefault() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("home_launcher", Context.MODE_PRIVATE).edit().putInt("grid_icon", 999).putInt("grid_spacing", 7).commit()
        AppsGrid.load(context)
        assertEquals(AppsGrid.MEDIUM, AppsGrid.iconDp)
        assertEquals(AppsGrid.NORMAL, AppsGrid.spacing)
    }
}
