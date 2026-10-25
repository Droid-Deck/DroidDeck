package com.example.dsedge

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EmbeddedBridgeTest {
    @Before fun resetSettings() {
        Settings::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
    }

    @Test
    fun keyboardSliderChangesAreSavedWithoutATouchDrag() {
        val app = RuntimeEnvironment.getApplication()
        Settings.init(app)
        val activity = Robolectric.buildActivity(BridgeActivity::class.java).get()
        val root = LinearLayout(activity)
        val sinkType = BridgeActivity::class.java.declaredClasses.single { it.simpleName == "IntSink" }
        val sink = Proxy.newProxyInstance(sinkType.classLoader, arrayOf(sinkType)) { _, method, args ->
            if (method.name == "set") Settings.gyroGain[1] = args[0] as Int
            null
        }
        Settings.gyroGain[1] = 100
        BridgeActivity::class.java.declaredMethods.single { it.name == "addSlider" }
            .apply { isAccessible = true }.invoke(activity, root, "Gyro speed", 800, 100, sink)
        val slider = root.getChildAt(1) as SeekBar
        assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(slider.progress > 100)
        assertEquals(slider.progress, app.getSharedPreferences(Host.prefs(app, "bridge"), 0)
            .getInt("gyroGain_p1", -1))
    }

    @Test
    fun calibrationUsesOnlyTheSelectedPlayer() {
        Settings.init(RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(BridgeActivity::class.java).get()
        fun field(name: String) = BridgeActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("calibPlayer").setInt(activity, 2)
        field("calibCount").setInt(activity, 0)
        val frame = FloatArray(13).apply { this[7] = 0.5f; this[8] = -1f; this[9] = 2f }
        activity.onFrame(frame, 1)
        assertEquals(0, field("calibCount").getInt(activity))
        repeat(180) { activity.onFrame(frame, 2) }
        assertEquals(-1, field("calibCount").getInt(activity))
        assertEquals(28.64789f, Settings.gyroBias[2][0], 0.0001f)
        assertEquals(-57.29578f, Settings.gyroBias[2][1], 0.0001f)
        assertEquals(114.59156f, Settings.gyroBias[2][2], 0.0001f)
    }

    @Test
    fun driverComponentsStayPrivateAndRunOutsideTheSessionProcess() {
        val app = RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        val service = pm.getServiceInfo(ComponentName(app, "com.example.dsedge.BridgeService"), 0)
        assertFalse(service.exported)
        assertEquals("${app.packageName}:sdl3", service.processName)
        for (name in listOf("BridgeActivity", "SdlHostActivity")) {
            val activity = pm.getActivityInfo(ComponentName(app, "com.example.dsedge.$name"), 0)
            assertFalse(activity.exported)
            assertEquals(service.processName, activity.processName)
        }
        val usb = pm.getActivityInfo(ComponentName(app, "com.example.dsedge.UsbAttachActivity"),
            PackageManager.GET_META_DATA)
        assertTrue(usb.exported)
        assertEquals(service.processName, usb.processName)
        assertTrue(usb.metaData.containsKey("android.hardware.usb.action.USB_DEVICE_ATTACHED"))
    }

    @Test
    fun embeddedBridgePreferencesDoNotShareDroidDeckSettings() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(Host.embedded(app))
        assertEquals("sdl3bridge_settings", Host.prefs(app, "settings"))
        assertEquals("sdl3bridge_engine", Host.prefs(app, "engine"))
    }

    @Test
    fun headlessEmbeddedDriverLeavesAndroidControllersToDroidDeck() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(Host.usbOnly(app, false))
        assertTrue(Host.usbOnly(app, true))
        val standalone = object : ContextWrapper(app) {
            override fun getPackageName() = Host.OWN_PACKAGE
        }
        assertFalse(Host.usbOnly(standalone, false))
        assertTrue(Host.usbOnly(standalone, true))
    }
}
