package com.droiddeck.launcher.stores.xbox

import android.app.Application
import android.content.Intent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28], application = Application::class)
class XboxNativePlayerCompatibilityTest {
    @Test fun unsupportedNativeLaunchUsesWebPlayerAndPreservesExtras() {
        val app = RuntimeEnvironment.getApplication()
        val intent = Intent(app, XboxNativePlayerActivity::class.java)
            .putExtra("product", "9AAAAAAAAAAA")
            .putExtra("title", "A cloud game")
            .putExtra("webUrl", "https://www.xbox.com/play/launch/game/9AAAAAAAAAAA")
        val controller = Robolectric.buildActivity(XboxNativePlayerActivity::class.java, intent)
        try {
            val activity = controller.create().get()
            assertTrue(activity.isFinishing)
            val fallback = shadowOf(activity).nextStartedActivity
            assertEquals(XboxCloudPlayerActivity::class.java.name, fallback.component?.className)
            assertEquals("9AAAAAAAAAAA", fallback.getStringExtra("product"))
            assertEquals("A cloud game", fallback.getStringExtra("title"))
            assertEquals(intent.getStringExtra("webUrl"), fallback.getStringExtra("webUrl"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [26, 28, 29, 33])
    fun nativeSupportStartsAtAndroidTen() {
        assertEquals(Build.VERSION.SDK_INT >= 29, XboxNativePlayerActivity.supported())
    }
}
