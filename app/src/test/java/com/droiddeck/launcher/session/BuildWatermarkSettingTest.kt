package com.droiddeck.launcher.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import com.droiddeck.launcher.ui.BuildWatermark
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class BuildWatermarkSettingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        listOf("build-watermark", "build-watermark.new", "build-watermark.bak", "build-watermark.lock").forEach {
            File(context.filesDir, it).delete()
        }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanUp() = clear()

    @Test fun defaultsOnAndPreservesLegacyChoice() {
        assertTrue(SessionPrefs.buildWatermarkEnabled(context))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("buildWatermark", false).commit()
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
    }

    @Test fun sharedFileOverridesCachedPreferencesAndNotifiesOtherProcesses() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("buildWatermark", false).commit()
        SessionPrefs.setBuildWatermarkEnabled(context, true)
        assertTrue(SessionPrefs.buildWatermarkEnabled(context))
        val change = shadowOf(context).broadcastIntents.last { it.action == BuildWatermarkSetting.CHANGED }
        assertEquals(context.packageName, change.`package`)
        File(context.filesDir, "build-watermark").writeText("off")
        assertFalse(SessionPrefs.buildWatermarkEnabled(context))
    }

    @Test fun remoteChangeRefreshesExistingAndRecreatedActivities() {
        BuildWatermark.install(context)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertNotNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            File(context.filesDir, "build-watermark").writeText("off")
            context.sendBroadcast(Intent(BuildWatermarkSetting.CHANGED).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            activity.recreate()
            assertNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
            SessionPrefs.setBuildWatermarkEnabled(context, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(activity.get().window.decorView.findViewWithTag<View>("build-watermark"))
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
