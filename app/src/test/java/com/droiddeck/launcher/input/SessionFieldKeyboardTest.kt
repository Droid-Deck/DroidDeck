package com.droiddeck.launcher.input

import com.droiddeck.launcher.SessionActivity
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
class SessionFieldKeyboardTest {
    private lateinit var activity: SessionActivity
    private lateinit var keyboard: KeyboardHost

    @Before fun setUp() {
        activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        keyboard = KeyboardHost(activity)
        ReflectionHelpers.setField(activity, "keyboard", keyboard)
    }

    @Test fun guestDismissalClosesOnlyTheGuestOwnedKeyboard() {
        openFieldKeyboard()
        call("dismissFieldKeyboard")
        assertFalse(keyboard.shown)
        call("showPrimaryKeyboard")
        call("dismissFieldKeyboard")
        assertTrue(keyboard.shown)
    }

    @Test fun pauseHideThenManualReopenSurvivesGuestDismissal() {
        openFieldKeyboard()
        call("hidePrimaryKeyboard")
        assertFalse(keyboard.shown)
        call("showPrimaryKeyboard")
        call("dismissFieldKeyboard")
        assertTrue(keyboard.shown)
        openFieldKeyboard()
        call("dismissFieldKeyboard")
        assertFalse(keyboard.shown)
    }

    @Test fun manualToggleAndPcKeyboardRelinquishGuestOwnership() {
        openFieldKeyboard()
        call("togglePrimaryKeyboard")
        call("togglePrimaryKeyboard")
        call("dismissFieldKeyboard")
        assertTrue(keyboard.shown)
        openFieldKeyboard()
        call("togglePcKeyboard")
        call("showPrimaryKeyboard")
        call("dismissFieldKeyboard")
        assertTrue(keyboard.shown)
    }

    @Test fun pendingGuestRequestWaitsForFocusAndCanBeDismissed() {
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "setGameOnBottom", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "setSelectedSecondScreenDisplay", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2))
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "setResumed", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        call("openBottomKeyboard")
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "retryFieldKeyboard", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertFalse(keyboard.shown)
        call("dismissFieldKeyboard")
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "retryFieldKeyboard", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertFalse(keyboard.shown)
        call("openBottomKeyboard")
        ReflectionHelpers.callInstanceMethod<Any?>(activity, "retryFieldKeyboard", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertTrue(keyboard.shown)
        call("dismissFieldKeyboard")
        assertFalse(keyboard.shown)
    }

    private fun openFieldKeyboard() {
        ReflectionHelpers.setField(activity, "fieldKeyboardOnPrimary", true)
        keyboard.show()
    }

    private fun call(name: String) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name)
}
