package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test fun labelledJwtIsRedacted() {
        val out = LogRedactor.redact("Using JWT 25484942796017334")
        assertFalse(out.contains("25484942796017334"))
        assertTrue(out.contains("<redacted:jwt>"))
    }

    @Test fun secretPairKeepsItsKey() {
        val out = LogRedactor.redact("GET /x?sessionid=abcdef123456 200")
        assertFalse(out.contains("abcdef123456"))
        assertTrue(out.contains("sessionid="))
    }

    @Test fun plainKeyValueSurvives() {
        assertEquals("language key=english", LogRedactor.redact("language key=english"))
    }

    @Test fun steamIdIsMaskedNotDeleted() {
        assertEquals("user 76561********1234", LogRedactor.redact("user 76561198012341234"))
    }

    @Test fun webApiKeyIsRedactedButDepotChunkIsKept() {
        val key = "0123456789abcdef0123456789abcdef"
        val chunk = "0123456789abcdef0123456789abcdef01234567"
        assertFalse(LogRedactor.redact("key $key").contains(key))
        assertTrue(LogRedactor.redact("chunk $chunk").contains(chunk))
    }

    @Test fun emailIsRedacted() {
        assertEquals("login <redacted:email> ok", LogRedactor.redact("login someone@example.com ok"))
    }

    @Test fun clockTimeIsNotAnAddress() {
        val line = "[2026-09-27 10:03:05] Startup"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test fun redactingTwiceChangesNothing() {
        val lines = listOf(
            "Using JWT 25484942796017334",
            "token=abcdefghijklmnop sessionid=qrstuvwxyz123",
            "user 76561198012341234 [U:1:52075506]",
            "external address 2607:f8b0:4005:80a::200e",
            "OnLoginStateChange someaccount 2 1 0 0",
        )
        for (line in lines) {
            val once = LogRedactor.redact(line)
            assertEquals(once, LogRedactor.redact(once))
        }
    }

    @Test fun everyAccountOnTheDeviceIsMasked() {
        val file = java.io.File.createTempFile("loginusers", ".vdf")
        try {
            file.writeText(com.droiddeck.launcher.session.SteamAccountsTest.FIXTURES.getValue("two.vdf"))
            LogRedactor.learnAccounts(file)
            val line = "alpha_acct Alpha Persona bravo_acct Bravo Persona"
            val out = LogRedactor.redact(line)
            for (name in listOf("alpha_acct", "Alpha Persona", "bravo_acct", "Bravo Persona")) assertFalse(out, out.contains(name))
        } finally {
            file.delete()
            LogRedactor.learnAccounts(java.io.File("/nonexistent"))
        }
    }
}
