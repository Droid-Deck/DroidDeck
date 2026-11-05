package com.droiddeck.launcher.stores.xbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XodusDiagnosticsTest {
    private val rejected = Regex(Xodus.SIGN_IN_REJECTED)

    @Test fun aContentLicenseRejectionIsRecognised() {
        assertTrue("Microsoft content licensing rejected sign-in (HTTP 401). Sign in again in Settings.".matches(rejected))
    }

    @Test fun otherStderrTextIsNotTakenForARejection() {
        assertFalse("Microsoft content licensing rejected sign-in (HTTP 401). Sign in again in Settings. token=abc".matches(rejected))
        assertFalse("Microsoft content licensing rejected sign-in (HTTP 403). Sign in again in Settings.".matches(rejected))
        assertFalse("NEXUS_CONTENT_LICENSE_DIAGNOSTIC_V1 content-http 401".matches(rejected))
    }
}
