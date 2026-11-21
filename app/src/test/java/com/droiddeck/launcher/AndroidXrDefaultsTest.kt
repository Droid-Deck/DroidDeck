package com.droiddeck.launcher

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidXrDefaultsTest {
    @Test fun defaultAllowsRuntimeHandsAndControllers() {
        val intent = Intent()
        applyAndroidXrDefaults(intent)
        assertEquals("auto", intent.getStringExtra("xr_input"))
        assertFalse(intent.hasExtra("xr_render_scale"))
    }

    @Test fun explicitInputIsPreserved() {
        for (input in listOf("auto", "hands", "controllers")) {
            val intent = Intent().putExtra("xr_input", input)
            applyAndroidXrDefaults(intent)
            assertEquals(input, intent.getStringExtra("xr_input"))
        }
    }

    @Test fun explicitResolutionIsPreserved() {
        for (scale in listOf(0.75f, 1f)) {
            val intent = Intent().putExtra("xr_render_scale", scale)
            applyAndroidXrDefaults(intent)
            assertEquals(scale, intent.getFloatExtra("xr_render_scale", 0f), 0f)
        }
    }
}
