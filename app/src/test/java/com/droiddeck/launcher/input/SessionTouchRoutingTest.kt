package com.droiddeck.launcher.input

import android.view.View
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionTouchRoutingTest {
    private lateinit var activity: SessionActivity
    private lateinit var steam: SteamTouchControls
    private lateinit var touchpad: TouchpadGestures
    private val directory = Files.createTempDirectory("touch-routing").toFile()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        activity.getSharedPreferences("session", 0).edit().clear().commit()
        SessionState.mode = SessionService.MODE_STEAM
        SteamTouchDevice.prepare(directory)
        steam = SteamTouchControls(activity, SteamTouchDevice.current!!, {}, {})
        steam.visibility = View.VISIBLE
        touchpad = TouchpadGestures(8f, object : PointerGestures.Listener {
            override fun onMove(x: Float, y: Float) {}
            override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {}
            override fun onWheel(steps: Int) {}
            override fun onLongPress() {}
        })
        field("onScreenControls", OnScreenControls(activity, null).apply { visibility = View.GONE })
        field("steamTouchControls", steam)
        field("steamTouchOptions", SteamTouchConfig.Options(inputMode = SteamTouchConfig.INPUT_MOUSE, mouseMode = SteamTouchConfig.MOUSE_ABSOLUTE, trackpadSensitivity = 2f))
        field("touchpad", touchpad)
        field("routedTouchMode", SessionPrefs.TOUCH_DIRECT)
    }

    @After fun tearDown() {
        SteamTouchDevice.release()
        directory.deleteRecursively()
        SessionState.mode = SessionService.MODE_STEAM
    }

    @Test fun disablingSteamTouchRestoresEverySavedModeEvenWhenRegularControlsAreAlreadyHidden() {
        for (mode in listOf(SessionPrefs.TOUCH_PAD, SessionPrefs.TOUCH_DIRECT, SessionPrefs.TOUCH_AUTO, SessionPrefs.TOUCH_OFF)) {
            SessionPrefs.setTouchMode(activity, mode)
            SessionPrefs.setOscMode(activity, SessionPrefs.OSC_NEVER)
            steam.visibility = View.VISIBLE
            invoke("readPrefs")
            assertEquals(SessionPrefs.TOUCH_DIRECT, ReflectionHelpers.getField<String>(activity, "routedTouchMode"))
            invoke("updateOnScreenControls")
            assertEquals(View.GONE, steam.visibility)
            assertEquals(mode, ReflectionHelpers.getField<String>(activity, "routedTouchMode"))
            assertEquals(1.15f, touchpad.sensitivity, 0f)
        }
    }

    private fun field(name: String, value: Any) = ReflectionHelpers.setField(activity, name, value)
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name)
}
