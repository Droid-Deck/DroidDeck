package com.droiddeck.launcher.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NativeXrSupportTest {
    @Test fun runtimeEligibilityDoesNotSelectQuestGpuDefaults() {
        val context = RuntimeEnvironment.getApplication()
        ShadowBuild.setModel("XR device")
        ShadowBuild.setDevice("unrecognized")
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        val pm = shadowOf(context.packageManager)
        pm.setSystemFeature("android.software.xr.api.openxr", false)
        assertFalse(NativeXrSupport.isSupported(context))
        pm.setSystemFeature("android.software.xr.api.openxr", true)
        assertTrue(NativeXrSupport.isSupported(context))
        QuestExperimental.initialize(context)
        assertNull(QuestExperimental.defaultAndroidDriver())
        assertEquals("", SessionPrefs.androidDriver(context))
        pm.setSystemFeature("android.software.xr.api.openxr", false)
        ShadowBuild.setModel("Quest 3")
        assertTrue(NativeXrSupport.isSupported(context))
    }
}
