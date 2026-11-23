package com.droiddeck.launcher.input

import android.view.KeyEvent
import android.view.WindowInsets
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
        keyboard.onImeChanged = { visible ->
            ReflectionHelpers.callInstanceMethod<Any?>(activity, "onPrimaryImeChanged",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, visible))
        }
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

    @Config(sdk = [33])
    @Test fun staleHiddenEventFromEarlierHideKeepsNewGuestOwnership() {
        call("showPrimaryKeyboard")
        ime(true)
        call("hidePrimaryKeyboard")
        requestFieldKeyboard()
        ime(false)
        assertTrue(owned())
        ime(true)
        call("dismissFieldKeyboard")
        assertFalse(keyboard.shown)
    }

    @Config(sdk = [33])
    @Test fun hiddenEventAfterGuestKeyboardShowsClearsOwnership() {
        requestFieldKeyboard()
        ime(true)
        ime(false)
        assertFalse(owned())
        call("showPrimaryKeyboard")
        call("dismissFieldKeyboard")
        assertTrue(keyboard.shown)
    }

    @Config(sdk = [33])
    @Test fun guestRequestWhileKeyboardAlreadyVisibleClearsOnRealDismissal() {
        call("showPrimaryKeyboard")
        ime(true)
        requestFieldKeyboard()
        assertTrue(owned())
        ime(false)
        assertFalse(owned())
    }

    @Test fun backDismissalWithoutImeInsetsClearsOwnership() {
        requestFieldKeyboard()
        assertTrue(owned())
        val t = System.currentTimeMillis()
        keyboard.onKeyPreIme(KeyEvent.KEYCODE_BACK, KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        assertFalse(keyboard.shown)
        assertFalse(owned())
    }

    @Test fun focusReturnSkipsPrimaryKeyboardWhenDestinationNoLongerPrimary() {
        setFlag("setGameOnBottom", true)
        selectDisplay(2)
        setFlag("setResumed", true)
        call("openBottomKeyboard")
        retry(false)
        setFlag("setGameOnBottom", false)
        retry(true)
        assertFalse(keyboard.shown)
        assertFalse(owned())
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "pendingFieldKeyboard"))
    }

    private fun requestFieldKeyboard() {
        setFlag("setGameOnBottom", true)
        selectDisplay(2)
        setFlag("setResumed", true)
        call("openBottomKeyboard")
        retry(true)
        assertTrue(keyboard.shown)
    }

    private fun ime(visible: Boolean) {
        keyboard.dispatchApplyWindowInsets(WindowInsets.Builder().setVisible(WindowInsets.Type.ime(), visible).build())
    }

    private fun owned() = ReflectionHelpers.getField<Boolean>(activity, "fieldKeyboardOnPrimary")

    private fun selectDisplay(id: Int) = ReflectionHelpers.callInstanceMethod<Any?>(activity, "setSelectedSecondScreenDisplay",
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id))

    private fun retry(focus: Boolean) = ReflectionHelpers.callInstanceMethod<Any?>(activity, "retryFieldKeyboard",
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, focus))

    private fun setFlag(name: String, value: Boolean) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name,
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, value))

    private fun openFieldKeyboard() {
        ReflectionHelpers.setField(activity, "fieldKeyboardOnPrimary", true)
        keyboard.show()
    }

    private fun call(name: String) = ReflectionHelpers.callInstanceMethod<Any?>(activity, name)
}
