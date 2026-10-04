package com.droiddeck.launcher.ui

import android.content.Context
import android.provider.Settings
import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MotionTest {
    @After fun restoreMotion() {
        val context = RuntimeEnvironment.getApplication() as Context
        SessionPrefs.setAnimationsEnabled(context, true)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Motion.refresh(context)
    }

    @Test fun appToggleAppliesImmediatelyAndCannotOverrideSystemReducedMotion() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.5f)
        Motion.refresh(context)
        assertEquals(1.5f, Motion.scale, 0f)
        SessionPrefs.setAnimationsEnabled(context, false)
        Motion.refresh(context)
        assertEquals(0f, Motion.scale, 0f)
        assertEquals(0, Motion.ms(300))
        // The app's toggle never writes Android's preference.
        assertEquals(1.5f, Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
        SessionPrefs.setAnimationsEnabled(context, true)
        Motion.refresh(context)
        assertEquals(1.5f, Motion.scale, 0f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        Motion.refresh(context)
        assertEquals(0f, Motion.scale, 0f)
    }
}
