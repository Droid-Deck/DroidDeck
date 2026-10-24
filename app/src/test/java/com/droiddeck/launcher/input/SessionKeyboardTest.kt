package com.droiddeck.launcher.input

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.io.File
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [33], shadows = [SessionKeyboardTest.PadShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionKeyboardTest {
    private lateinit var activity: SessionActivity
    private lateinit var host: KeyboardHost
    private lateinit var imm: InputMethodManager

    @Before fun setUp() {
        val window = Robolectric.buildActivity(Activity::class.java).setup().get()
        imm = window.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        host = KeyboardHost(window)
        window.setContentView(FrameLayout(window).apply { addView(host) })
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        ReflectionHelpers.setField(activity, "keyboard", host)
        ReflectionHelpers.setField(activity, "padBridge", PadBridge(window.cacheDir))
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.steamUi = "bigpicture"
        PadShadow.requests = 0
        PadShadow.releases = 0
        idle()
    }

    @After fun tearDown() {
        host.hide()
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.steamUi = null
    }

    @Test fun gamingShortcutClosesAndroidKeyboardAndRequestsSteam() {
        host.show()
        idle()
        assertTrue(shadowOf(imm).isSoftInputVisible)
        keyboardAction("toggleKeyboard")
        assertFalse(shadowOf(imm).isSoftInputVisible)
        assertFalse(host.onCheckIsTextEditor())
        assertEquals(1, PadShadow.requests)
        assertEquals(1, PadShadow.releases)
    }

    @Test fun androidKeyboardRemainsDirectlyAccessibleInSteam() {
        keyboardAction("toggleAndroidKeyboard")
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertTrue(host.onCheckIsTextEditor())
        assertEquals(0, PadShadow.requests)
    }

    @Test fun disablingTheBuiltInPadFallsBackToAndroid() {
        SessionPrefs.setSteamController(activity, SessionPrefs.CONTROLLER_NONE)
        keyboardAction("toggleKeyboard")
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertEquals(0, PadShadow.requests)
    }

    @Test fun unavailablePadBridgeFallsBackToAndroid() {
        ReflectionHelpers.setField(activity, "padBridge", null)
        keyboardAction("toggleKeyboard")
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertEquals(0, PadShadow.requests)
    }

    @Test fun steamDesktopShortcutUsesAndroidKeyboard() {
        SessionState.steamUi = "desktop"
        keyboardAction("toggleKeyboard")
        assertTrue(shadowOf(imm).isSoftInputVisible)
        assertEquals(0, PadShadow.requests)
    }

    @Test fun desktopAndStandaloneShortcutsUseAndroidKeyboard() {
        for (mode in listOf(SessionService.MODE_DESKTOP, SessionService.MODE_RUN)) {
            host.hide()
            SessionState.mode = mode
            keyboardAction("toggleKeyboard")
            assertTrue(shadowOf(imm).isSoftInputVisible)
            assertEquals(0, PadShadow.requests)
        }
    }

    private fun keyboardAction(name: String) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, name)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Implements(value = PadBridge::class, isInAndroidSdk = false)
    class PadShadow {
        @Implementation fun __constructor__(directory: File) {}
        @Implementation fun releaseAll() { releases++ }
        @Implementation fun triggerSteamKeyboard() { requests++ }

        companion object {
            var requests = 0
            var releases = 0
            @JvmStatic @Implementation fun __staticInitializer__() {}
        }
    }
}
