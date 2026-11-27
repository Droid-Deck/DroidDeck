package com.droiddeck.launcher.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import com.droiddeck.launcher.ui.BuildWatermark
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GameDebugLogsExpiryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun reset() {
        SessionPaths.take()
        prefs.edit().clear().commit()
        listOf("build-watermark", "build-watermark.lock").forEach { File(context.filesDir, it).delete() }
    }

    @After fun end() {
        SessionPaths.take()
    }

    private fun setDebugLogs(on: Boolean) = prefs.edit().putBoolean("gameDebugLogsEnabled", on).commit()

    @Test fun settingTurnsOffWhenTheSessionItStartedWithEnds() {
        setDebugLogs(true)
        SessionPaths.beginOrCurrent(context, "steam")
        assertTrue(SessionPrefs.gameDebugLogsEnabled(context))
        SessionPaths.take()
        assertFalse(SessionPrefs.gameDebugLogsEnabled(context))
    }

    @Test fun settingStaysOnForTheSessionRunningWhenItWasEnabled() {
        SessionPaths.beginOrCurrent(context, "steam")
        setDebugLogs(true)
        SessionPaths.take()
        assertTrue(SessionPrefs.gameDebugLogsEnabled(context))
        SessionPaths.beginOrCurrent(context, "steam")
        SessionPaths.take()
        assertFalse(SessionPrefs.gameDebugLogsEnabled(context))
    }

    @Test fun settingThatWasOffStaysOff() {
        SessionPaths.beginOrCurrent(context, "steam")
        SessionPaths.take()
        assertFalse(SessionPrefs.gameDebugLogsEnabled(context))
    }

    @Test fun badgeShowsWhileOnEvenWithTheBuildWatermarkOff() {
        BuildWatermark.install(context)
        SessionPrefs.setBuildWatermarkEnabled(context, false)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            setDebugLogs(true)
            BuildWatermarkSetting.notifyChanged(context)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            SessionPaths.beginOrCurrent(context, "steam")
            SessionPaths.take()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
