package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestHostnameTest {
    @Test fun validLabelsAreKept() {
        assertEquals("localhost", SessionPrefs.validGuestHostname("localhost"))
        assertEquals("Fold8-Steam", SessionPrefs.validGuestHostname("Fold8-Steam"))
        assertEquals("a", SessionPrefs.validGuestHostname("a"))
        assertEquals("x".repeat(63), SessionPrefs.validGuestHostname("x".repeat(63)))
    }

    @Test fun surroundingSpaceIsTrimmed() {
        assertEquals("localhost", SessionPrefs.validGuestHostname("  localhost \n"))
    }

    @Test fun anythingElseIsRejected() {
        assertNull(SessionPrefs.validGuestHostname(null))
        assertNull(SessionPrefs.validGuestHostname(""))
        assertNull(SessionPrefs.validGuestHostname("   "))
        assertNull(SessionPrefs.validGuestHostname("-leading"))
        assertNull(SessionPrefs.validGuestHostname("trailing-"))
        assertNull(SessionPrefs.validGuestHostname("two words"))
        assertNull(SessionPrefs.validGuestHostname("dotted.name"))
        assertNull(SessionPrefs.validGuestHostname("back\\slash"))
        assertNull(SessionPrefs.validGuestHostname("x".repeat(64)))
    }
}
