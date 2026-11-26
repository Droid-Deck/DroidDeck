package com.droiddeck.launcher.session

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.droiddeck.launcher.input.SecondScreenMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DesktopLockLifecycleTest {
    @Test fun invalidDesktopMetadataStillRequiresApproval() {
        for ((ui, url) in listOf("desktop" to "https://example.org", "bad-ui" to "", "" to "")) {
            assertTrue(DesktopLock.requiresUnlock(true, SessionService.MODE_DESKTOP, ui))
            assertNull(DesktopLock.handoff(SessionService.MODE_DESKTOP, ui, url))
        }
        assertFalse(DesktopLock.requiresUnlock(false, SessionService.MODE_DESKTOP, "desktop"))
        assertFalse(DesktopLock.requiresUnlock(true, SessionService.MODE_STEAM, "bigpicture"))
        assertNotNull(DesktopLock.handoff(SessionService.MODE_STEAM, "desktop", "steam://open/main"))
    }

    @Test fun handoffRetainsSourceAndIsConsumed() {
        val request = DesktopLock.Handoff(SessionService.MODE_DESKTOP, "desktop", "", "original")
        val intent = request.put(Intent())
        assertEquals(request, DesktopLock.handoff(intent))
        assertNull(DesktopLock.handoff(intent))
        assertTrue(DesktopLock.currentSession("original", "original", true))
        assertFalse(DesktopLock.currentSession("original", "replacement", true))
        assertFalse(DesktopLock.currentSession("original", "original", false))
        assertFalse(DesktopLock.currentSession(null, null, true))
    }

    @Test fun unavailableDesktopFallsBackWithoutChangingSavedChoice() {
        val remembered = SecondScreenMode.DESKTOP.id
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening(remembered, true, bottomDesktop = false))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening(remembered, false, bottomDesktop = false))
        assertEquals(SecondScreenMode.DESKTOP, SecondScreenMode.opening(remembered, true, bottomDesktop = true))
        assertEquals(SecondScreenMode.TERMINAL, SecondScreenMode.opening("terminal", true, bottomDesktop = false))
    }

    @Test fun failedWritesKeepLastSavedCredentialsAndSuccessfulClearDisablesLock() {
        val base = RuntimeEnvironment.getApplication() as Context
        val real = base.getSharedPreferences("desktop-lock-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        var fail = false
        val preferences = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        val written = editor.commit()
                        return written && !fail
                    }
                }
            }
        }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = preferences
        }
        assertFalse(DesktopLock.enabled(context))
        assertTrue(DesktopLock.set(context, "2468"))
        assertFalse(DesktopLock.clear(context, "1234"))
        fail = true
        assertThrows(java.io.IOException::class.java) { DesktopLock.clear(context, "2468") }
        assertTrue(DesktopLock.enabled(context))
        assertTrue(DesktopLock.matches(context, "2468"))
        assertThrows(java.io.IOException::class.java) { DesktopLock.set(context, "1357") }
        assertTrue(DesktopLock.matches(context, "2468"))
        assertFalse(DesktopLock.matches(context, "1357"))
        fail = false
        assertTrue(DesktopLock.clear(context, "2468"))
        assertFalse(DesktopLock.enabled(context))
        assertFalse(DesktopLock.matches(context, "2468"))
    }
}
