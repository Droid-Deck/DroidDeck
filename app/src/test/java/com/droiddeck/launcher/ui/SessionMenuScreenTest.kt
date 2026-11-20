package com.droiddeck.launcher.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.glasses.GlassesScreen
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionMenuScreen
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import androidx.compose.runtime.MutableState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionMenuScreenTest {
    @Test fun swappingOntoADisabledScreenRestoresItsRememberedPanel() = checkSwapPanel(enabled = false)

    @Test fun swappingOntoAnEnabledScreenKeepsItsCurrentPanel() = checkSwapPanel(enabled = true)

    private fun checkSwapPanel(enabled: Boolean) {
        val previousRequest = SessionState.gameOnBottom
        val previousMode = SessionState.secondScreenMode
        val previousSession = SessionState.mode
        val previousAutoOpened = SessionState.secondScreenAutoOpened
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.secondScreenMode = if (enabled) SecondScreenMode.CUSTOM_CONTROLS else SecondScreenMode.NONE
        SessionState.secondScreenAutoOpened = true
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val game = BottomGameScreen(activity, display, activity, { true }, { false })
        try {
            SessionPrefs.setSecondScreenPanel(activity, false, SecondScreenMode.TERMINAL.id)
            SessionPrefs.setSecondScreenEnabled(activity, false, enabled)
            ReflectionHelpers.setField(activity, "bottomGame", game)

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "putGameOnBottom",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))

            val expected = if (enabled) SecondScreenMode.CUSTOM_CONTROLS else SecondScreenMode.TERMINAL
            assertEquals(expected, ReflectionHelpers.getField<MutableState<SecondScreenMode>>(activity, "secondScreenMode\$delegate").value)
            assertEquals(expected, SessionState.secondScreenMode)
            assertTrue(SessionPrefs.secondScreenEnabled(activity, false))
            assertTrue(SessionState.gameOnBottom)
            assertSame(game, ReflectionHelpers.getField<BottomGameScreen>(activity, "bottomGame"))
            assertNull(ReflectionHelpers.getField<SecondScreenPresentation?>(activity, "secondScreenPresentation"))
            assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(activity, false))
        } finally {
            ReflectionHelpers.setField(activity, "bottomGame", null)
            SessionState.gameOnBottom = previousRequest
            SessionState.secondScreenMode = previousMode
            SessionState.mode = previousSession
            SessionState.secondScreenAutoOpened = previousAutoOpened
        }
    }

    @Test fun repeatingTheMenuActionClosesAndReopensTheDrawer() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val open: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerOpenState\$delegate")
        repeat(3) {
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "toggleDrawer")
            assertTrue(open.value)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "toggleDrawer")
            assertFalse(open.value)
        }
    }

    @Test fun repeatingTheMenuActionAlsoClosesAPinnedMenu() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val pinned: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerPinned\$delegate")
        val docked: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerDocked\$delegate")
        val open: MutableState<Boolean> = ReflectionHelpers.getField(activity, "drawerOpenState\$delegate")
        pinned.value = true
        docked.value = true
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "toggleDrawer")
        assertFalse(pinned.value)
        assertFalse(docked.value)
        assertFalse(open.value)
    }

    @Test fun placementFollowsTheGameWhenScreensAreSwapped() {
        assertFalse(SessionMenuScreen.GAME.onSecondaryDisplay(false, true))
        assertTrue(SessionMenuScreen.GAME.onSecondaryDisplay(true, true))
        assertTrue(SessionMenuScreen.COMPANION.onSecondaryDisplay(false, true))
        assertFalse(SessionMenuScreen.COMPANION.onSecondaryDisplay(true, true))
    }

    @Test fun missingOrDisabledSecondScreenFallsBackWithoutChangingPreference() {
        val context = RuntimeEnvironment.getApplication()
        for (screen in SessionMenuScreen.entries) {
            SessionPrefs.setMenuScreen(context, screen)
            SessionPrefs.setSecondScreenEnabled(context, false, false)
            assertFalse(SessionPrefs.menuScreen(context).onSecondaryDisplay(false, false))
            assertFalse(SessionPrefs.menuScreen(context).onSecondaryDisplay(true, false))
            SessionPrefs.setSecondScreenEnabled(context, false, true)
            assertEquals(screen, SessionPrefs.menuScreen(context))
        }
    }

    @Test fun existingInstallationsKeepTheCompanionMenu() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(SessionMenuScreen.COMPANION, SessionPrefs.menuScreen(context))
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putString("menuScreen", "unknown").apply()
        assertEquals(SessionMenuScreen.COMPANION, SessionPrefs.menuScreen(context))
    }

    @Test fun disablingSecondScreenClearsPendingGameSwapWhileGlassesOwnTheOutput() {
        val previousRequest = SessionState.gameOnBottom
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val glasses = GlassesScreen(activity, display, 60, activity)
        val requested: MutableState<Boolean> = ReflectionHelpers.getField(activity, "gameOnBottom\$delegate")
        try {
            SessionPrefs.setSecondScreenPanel(activity, false, SecondScreenMode.TERMINAL.id)
            SessionPrefs.setSecondScreenEnabled(activity, false, true)
            SessionPrefs.setSecondScreenEnabled(activity, true, true)
            SessionState.gameOnBottom = true
            requested.value = true
            ReflectionHelpers.setField(activity, "glassesScreen", glasses)
            assertFalse(ReflectionHelpers.getField<MutableState<Boolean>>(activity, "bottomOutputActive\$delegate").value)

            ReflectionHelpers.callInstanceMethod<Unit>(activity, "setSecondScreenEnabled",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))

            assertFalse(requested.value)
            assertFalse(SessionState.gameOnBottom)
            assertSame(glasses, ReflectionHelpers.getField<GlassesScreen>(activity, "glassesScreen"))
            assertFalse(SessionPrefs.secondScreenEnabled(activity, false))
            assertTrue(SessionPrefs.secondScreenEnabled(activity, true))
            assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(activity, false))
            assertTrue(ReflectionHelpers.getField<MutableState<Boolean>>(activity, "drawerOpenState\$delegate").value)

            ReflectionHelpers.setField(activity, "glassesScreen", null)
            assertFalse(GameScreenSwap.wanted(requested.value, true, false, false, false, true))
        } finally {
            SessionState.gameOnBottom = previousRequest
            ReflectionHelpers.setField(activity, "glassesScreen", null)
        }
    }

    @Test fun placementDoesNotChangeTheRememberedContentOrKeyboardSetting() {
        val context = RuntimeEnvironment.getApplication()
        SessionPrefs.setSecondScreenPanel(context, false, SecondScreenMode.TERMINAL.id)
        SessionPrefs.setSecondScreenPanel(context, true, SecondScreenMode.CUSTOM_CONTROLS.id)
        val keyboard = SessionPrefs.androidTextFieldKeyboard(context)
        SessionPrefs.setMenuScreen(context, SessionMenuScreen.GAME)
        assertEquals(SecondScreenMode.TERMINAL.id, SessionPrefs.secondScreenPanel(context, false))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS.id, SessionPrefs.secondScreenPanel(context, true))
        assertEquals(keyboard, SessionPrefs.androidTextFieldKeyboard(context))
    }

    @Test fun gameMenuReleasesFocusAndPreservesTheGameSurfaceWhenClosed() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val callbacks = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        }
        val panel = BottomGameScreen(activity, display, callbacks, { true }, { false }, menu = {})
        try {
            panel.show()
            val holder = panel.holder
            repeat(2) {
                panel.showMenu(true)
                assertTrue(panel.menuHost!!.shown)
                assertEquals(0, panel.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                panel.showMenu(false)
                assertFalse(panel.menuHost!!.shown)
                assertNotEquals(0, panel.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                assertSame(holder, panel.holder)
                assertEquals(View.VISIBLE, panel.surfaceView.visibility)
                assertTrue(panel.isShowing)
            }
        } finally { panel.dismiss() }
    }

    @Test fun companionMenuReturnsToTheSameControlsAndKeyboardHost() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val panel = SecondScreenPresentation(activity, display, {}, {}, menu = {})
        try {
            panel.show()
            panel.showMode(SecondScreenMode.CUSTOM_CONTROLS)
            val root = panel.window!!.decorView
            assertTrue(panel.showsKeyboard)
            panel.showMenu(true)
            assertTrue(panel.menuShown)
            assertFalse(panel.showsKeyboard)
            panel.showMenu(false)
            assertFalse(panel.menuShown)
            assertTrue(panel.showsKeyboard)
            assertSame(root, panel.window!!.decorView)
        } finally { panel.closeControls() }
    }
}
