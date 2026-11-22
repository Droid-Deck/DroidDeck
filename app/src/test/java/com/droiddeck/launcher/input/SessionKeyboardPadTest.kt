package com.droiddeck.launcher.input

import android.os.Looper
import android.view.View
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionKeyboardPadTest {
    private lateinit var activity: SessionActivity
    private lateinit var controls: OnScreenControls
    private val directory = Files.createTempDirectory("keyboard-pad").toFile()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        activity.getSharedPreferences("session", 0).edit().clear().commit()
        SessionState.mode = SessionService.MODE_STEAM
        controls = OnScreenControls(activity, null).apply { visibility = View.GONE }
        ReflectionHelpers.setField(activity, "onScreenControls", controls)
    }

    @After fun tearDown() {
        steamKeyboard(false)
        SteamTouchDevice.release()
        directory.deleteRecursively()
        SessionState.mode = SessionService.MODE_STEAM
    }

    @Test fun padStepsAsideWhileEitherKeyboardIsUpInSteam() {
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_ALWAYS)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
        steamKeyboard(true)
        update()
        assertEquals(View.GONE, controls.visibility)
        steamKeyboard(false)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
        pcKeyboard(true)
        update()
        assertEquals(View.GONE, controls.visibility)
        pcKeyboard(false)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
    }

    @Test fun steamsKeyboardLeavesThePadAloneOutsideSteam() {
        SessionState.mode = SessionService.MODE_DESKTOP
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_ALWAYS)
        steamKeyboard(true)
        update()
        assertEquals(View.VISIBLE, controls.visibility)
        pcKeyboard(true)
        update()
        assertEquals(View.GONE, controls.visibility)
    }

    @Test fun steamTouchControllerHidesAndHandsTouchBackWhileSteamsKeyboardIsUp() {
        SteamTouchDevice.prepare(directory)
        val steam = SteamTouchControls(activity, SteamTouchDevice.current!!, {}, {})
        ReflectionHelpers.setField(activity, "steamTouchControls", steam)
        ReflectionHelpers.setField(activity, "steamTouchOptions",
            SteamTouchConfig.Options(inputMode = SteamTouchConfig.INPUT_CONTROLLER))
        SessionPrefs.setTouchMode(activity, SessionPrefs.TOUCH_DIRECT)
        SessionPrefs.setOscMode(activity, SessionPrefs.OSC_STEAM_TOUCH)
        update()
        assertEquals(View.VISIBLE, steam.visibility)
        assertEquals(SessionPrefs.TOUCH_OFF, routedTouchMode())
        steamKeyboard(true)
        update()
        assertEquals(View.GONE, steam.visibility)
        assertTrue(SteamTouchDevice.current!!.plugged)
        assertEquals(SessionPrefs.TOUCH_DIRECT, routedTouchMode())
        steamKeyboard(false)
        update()
        assertEquals(View.VISIBLE, steam.visibility)
        assertEquals(SessionPrefs.TOUCH_OFF, routedTouchMode())
    }

    @Test fun endingTheAgentForgetsSteamsKeyboard() {
        steamKeyboard(true)
        AgentGuest.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AgentGuest.steamKeyboard)
    }

    private fun update() = ReflectionHelpers.callInstanceMethod<Any?>(activity, "updateOnScreenControls")

    private fun routedTouchMode() = ReflectionHelpers.getField<String>(activity, "routedTouchMode")

    private fun pcKeyboard(open: Boolean) {
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "pcKeyboardOpen\$delegate").value = open
    }

    @Suppress("UNCHECKED_CAST")
    private fun steamKeyboard(showing: Boolean) {
        val field = AgentGuest::class.java.getDeclaredField("steamKeyboard\$delegate").apply { isAccessible = true }
        (field.get(AgentGuest) as MutableState<Boolean>).value = showing
    }
}
