package com.droiddeck.launcher.runtime

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.time.Duration
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, application = Application::class, shadows = [WriteSettingsShadow::class])
class DeviceControlsComponentTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private lateinit var component: DeviceControlsComponent
    private lateinit var directory: File
    private var visible = true

    @Before fun start() {
        WriteSettingsShadow.writable = true
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val root = tmp.newFolder()
        directory = DeviceControlsComponent.directory(root)
        component = DeviceControlsComponent(context, root) { visible }
        component.publish()
        component.start()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun stop() {
        component.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tick() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    private fun request(name: String, text: String) {
        File(directory, "$name.request").writeText(text)
        tick()
    }

    @Test fun androidChangesArePublishedAndRequestsUseDeviceVolumeSteps() {
        request("volume", "999")
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        tick()
        assertEquals("3", File(directory, "volume.state").readText().split(" ").first())
        request("volume", "invalid")
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun deniedBrightnessDoesNotOpenSettingsOrReplayAnOldSliderValueAfterPermission() {
        WriteSettingsShadow.writable = false
        request("brightness", "0.75")
        assertEquals(128, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        assertNull(shadowOf(context).nextStartedActivity)
        assertFalse(JSONObject(File(directory, "state.json").readText()).getBoolean("brightnessWritable"))
        WriteSettingsShadow.writable = true
        tick()
        assertEquals(128, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        request("brightness", "0.75")
        assertEquals(192, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test fun brightnessIsBoundedAndExternalChangesAreReadBack() {
        for (invalid in listOf("NaN", "Infinity", "-0.1", "1.1")) request("brightness", invalid)
        assertEquals(128, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        request("brightness", "0")
        assertEquals(1, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
        tick()
        assertEquals(1.0, JSONObject(File(directory, "state.json").readText()).getDouble("brightness"), 0.0001)
    }

    @Test fun permissionNotificationOpensOptionalLauncherSetupOnlyWhenSelected() {
        visible = false
        WriteSettingsShadow.writable = false
        request("brightness", "0.25")
        request("brightness", "0.75")
        assertNull(shadowOf(context).nextStartedActivity)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val notification = notifications.activeNotifications.single().notification
        notification.contentIntent.send()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(com.droiddeck.launcher.core.SteamIntegration.ACTION_SETUP, intent.action)
        assertEquals("BRIGHTNESS", intent.getStringExtra(com.droiddeck.launcher.core.SteamIntegration.EXTRA_FEATURE))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
        WriteSettingsShadow.writable = true
        tick()
        assertEquals(128, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun stoppingClearsThePermissionNotificationAndPendingRequest() {
        visible = false
        WriteSettingsShadow.writable = false
        request("brightness", "0.75")
        component.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        WriteSettingsShadow.writable = true
        tick()
        assertEquals(128, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test fun stoppedSessionsDoNotConsumeNewCommands() {
        component.stop()
        shadowOf(Looper.getMainLooper()).idle()
        request("brightness", "0.2")
        assertEquals(128, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }

    @Test fun repeatedSliderChangesDoNotInterruptTheSession() {
        WriteSettingsShadow.writable = false
        request("brightness", "0.75")
        assertNull(shadowOf(context).nextStartedActivity)
        request("brightness", "0.8")
        assertNull(shadowOf(context).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        request("brightness", "0.8")
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun oversizedRequestsAreDiscardedAndDoNotBlockLaterCommands() {
        request("brightness", "0".repeat(1000))
        assertFalse(File(directory, "brightness.request.processing").exists())
        request("brightness", "0.75")
        assertEquals(192, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
    }
}

@Implements(Settings.System::class)
class WriteSettingsShadow : ShadowSettings.ShadowSystem() {
    companion object {
        @JvmField var writable = true
        @JvmStatic @Implementation fun canWrite(context: Context) = writable
    }
}
