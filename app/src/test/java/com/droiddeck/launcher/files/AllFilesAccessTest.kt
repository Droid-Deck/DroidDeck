package com.droiddeck.launcher.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllFilesAccessTest {
    @Test
    fun androidTenAndOlderNeverAsks() {
        assertFalse(AllFilesAccess.missing(sdk = 29) { false })
    }

    @Test
    fun androidElevenAsksUntilGranted() {
        assertTrue(AllFilesAccess.missing(sdk = 30) { false })
        assertFalse(AllFilesAccess.missing(sdk = 30) { true })
    }

    @Test
    fun newerAndroidAsksUntilGranted() {
        assertTrue(AllFilesAccess.missing(sdk = 35) { false })
        assertFalse(AllFilesAccess.missing(sdk = 35) { true })
    }
}
