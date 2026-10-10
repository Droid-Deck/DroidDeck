package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretScrubTest {
    private fun share(line: String) = SecretScrub.scrub(line, SecretScrub.Urls.KEEP_PATH, "<redacted:token>")

    @Test fun windowsExceptionCodesAreKept() {
        for (line in listOf(
            "0024:trace:seh:dispatch_exception code=406d1388 flags=0 addr=00006FFFFFC0A6E8",
            "0024:warn:seh:dispatch_exception code=c0000005 flags=0 addr=0000000140012345",
            "0030:trace:seh:handle_syscall_fault code=80000003 flags=0",
            "exception code=0xC0000005 at 0x140012345",
            // An exception code stays one even on a line that also carries OAuth keys.
            "client_id=abc code=c0000005",
        )) assertEquals(line, share(line))
    }

    @Test fun anOAuthCodeIsBlanked() {
        assertEquals("redirect client_id=34a02cf8 redirect_uri=x state=1 code=<redacted:token>",
            share("redirect client_id=34a02cf8 redirect_uri=x state=1 code=AUTHCODE123"))
        assertEquals("GET /on_login_success?code=<redacted:token>&scope=",
            share("GET /on_login_success?code=AUTHCODE123&scope="))
        assertEquals("exchange_code=<redacted:token>", share("exchange_code=0123456789abcdef"))
        assertEquals("authorizationCode=<redacted:token>", share("authorizationCode=ABCD1234XYZ"))
    }

    @Test fun aCodeWithoutOAuthAroundItIsKept() {
        for (line in listOf("exit code=1337", "status code=ERR_TIMEOUT", "code 403")) assertEquals(line, share(line))
    }

    @Test fun scrubbingTwiceChangesNothing() {
        val once = share("client_id=1 code=AUTHCODE123 ?code=XYZW1234567")
        assertFalse(once.contains("AUTHCODE123"))
        assertEquals(once, share(once))
    }
}
