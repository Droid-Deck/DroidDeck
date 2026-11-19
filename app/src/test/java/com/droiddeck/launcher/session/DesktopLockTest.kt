package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLockTest {
    @Test
    fun pinMustBeFourToEightDigits() {
        assertTrue(DesktopLock.validPin("1234"))
        assertTrue(DesktopLock.validPin("12345678"))
        assertFalse(DesktopLock.validPin("123"))
        assertFalse(DesktopLock.validPin("123456789"))
        assertFalse(DesktopLock.validPin("12a4"))
        assertFalse(DesktopLock.validPin(""))
        assertFalse(DesktopLock.validPin("١٢٣٤"))
    }

    @Test
    fun desktopModeAndSteamDesktopUiAreGated() {
        assertTrue(DesktopLock.entersDesktop(SessionService.MODE_DESKTOP, null))
        assertTrue(DesktopLock.entersDesktop(SessionService.MODE_DESKTOP, "bigpicture"))
        assertTrue(DesktopLock.entersDesktop(SessionService.MODE_STEAM, "desktop"))
    }

    @Test
    fun bigPictureAndGamesAreNotGated() {
        assertFalse(DesktopLock.entersDesktop(SessionService.MODE_STEAM, null))
        assertFalse(DesktopLock.entersDesktop(SessionService.MODE_STEAM, "bigpicture"))
        assertFalse(DesktopLock.entersDesktop(SessionService.MODE_RUN, null))
        assertFalse(DesktopLock.entersDesktop(null, null))
    }

    @Test
    fun hashIsSaltedAndDeterministic() {
        val salt = ByteArray(16) { it.toByte() }
        val other = ByteArray(16) { (it + 1).toByte() }
        val a = DesktopLock.hash("2468", salt)
        assertEquals(32, a.size)
        assertArrayEquals(a, DesktopLock.hash("2468", salt))
        assertFalse(a.contentEquals(DesktopLock.hash("2469", salt)))
        assertFalse(a.contentEquals(DesktopLock.hash("2468", other)))
    }
}
