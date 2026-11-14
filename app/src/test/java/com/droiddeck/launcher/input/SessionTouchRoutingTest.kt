package com.droiddeck.launcher.input

import android.view.View
import androidx.compose.runtime.MutableState
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.ui.InputSurfaceView
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

    @Test fun pinnedMouseButtonSuppressesTapsAfterSteamTouchIsHiddenForTheKeyboard() {
        val controls = shownControls(button.copy(alwaysShown = true))
        field("inputProfile", userMouseButtonProfile)
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "steamTouchInUse\$delegate").value = true
        ReflectionHelpers.getField<MutableState<Boolean>>(activity, "pcKeyboardOpen\$delegate").value = true
        invoke("updateOnScreenControls")
        assertEquals(View.GONE, steam.visibility)
        assertEquals(View.VISIBLE, controls.visibility)
        assertTrue(mouseButtonShown())
    }

    @Test fun visibleSteamTouchKeepsTapClicksAndGameBindingsHaveNoMouseButton() {
        shownControls(button)
        field("inputProfile", userMouseButtonProfile)
        assertFalse(mouseButtonShown())
        steam.visibility = View.GONE
        assertTrue(mouseButtonShown())
        field("inputProfile", InputProfile.defaults(InputProfile.GAME))
        assertFalse(mouseButtonShown())
    }

    @Test fun mouseButtonOnTheDeckSurfaceSuppressesTapsOnlyWhileItIsShown() {
        steam.visibility = View.GONE
        field("inputProfile", userMouseButtonProfile)
        val deck = InputSurfaceView(activity, null, listOf(button), InputRole.PRIMARY)
        field("unfoldedDeckSurface", deck)
        assertTrue(mouseButtonShown())
        deck.visibility = View.GONE
        assertFalse(mouseButtonShown())
        deck.visibility = View.VISIBLE
        field("inputProfile", InputProfile.defaults(InputProfile.GAME))
        assertFalse(mouseButtonShown())
    }

    @Test fun pinnedMouseButtonInButtonsOnlyControlsSuppressesTaps() {
        steam.visibility = View.GONE
        field("inputProfile", userMouseButtonProfile)
        val controls = shownControls(button.copy(alwaysShown = true))
        controls.setButtonsOnly(true)
        assertTrue(mouseButtonShown())
        val plain = shownControls(button)
        plain.setButtonsOnly(true)
        assertFalse(mouseButtonShown())
    }

    @Test fun mouseClickOnAStickOrTrackpadSuppressesTaps() {
        steam.visibility = View.GONE
        val stick = InputSurfaceElement(InputControl.LS, .1f, .3f, .3f, .4f)
        shownControls(stick)
        field("inputProfile", InputProfile.defaults(InputProfile.DESKTOP))
        assertFalse(mouseButtonShown())
        field("inputProfile", InputProfile.defaults(InputProfile.DESKTOP)
            .copy(clicks = mapOf(InputRole.PRIMARY to mapOf(stick.id to InputAction.MouseButton(274)))))
        assertTrue(mouseButtonShown())
    }

    private val userMouseButtonProfile = InputProfile.defaults(InputProfile.DESKTOP)
        .withBinding(InputRole.PRIMARY, InputControl.A, InputAction.MouseButton(274))

    @Test fun stockDesktopOverlayKeepsTapClicks() {
        steam.visibility = View.GONE
        shownControls(button)
        field("inputProfile", InputProfile.defaults(InputProfile.DESKTOP))
        assertFalse(mouseButtonShown())
        field("inputProfile", userMouseButtonProfile)
        assertTrue(mouseButtonShown())
    }

    private val button = InputSurfaceElement(InputControl.A, .7f, .3f, .2f, .3f)

    private fun shownControls(vararg elements: InputSurfaceElement) = OnScreenControls(activity, null).apply {
        updateLayout(SavedInputLayout("test", "Test", elements.toList(), aspect = 1000f / 600f))
        measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        layout(0, 0, 1000, 600)
        visibility = View.VISIBLE
        field("onScreenControls", this)
    }

    private fun mouseButtonShown() = invoke("onScreenMouseButtonShown") as Boolean
    private fun field(name: String, value: Any) = ReflectionHelpers.setField(activity, name, value)
    private fun invoke(name: String) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name)
}
