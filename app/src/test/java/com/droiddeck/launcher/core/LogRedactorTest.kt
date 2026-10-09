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

    @Test fun macAddressesAreRedactedInAnyLog() {
        assertEquals("  mac <redacted:mac>", LogRedactor.redact("  mac 02:ab:cd:ef:12:34"))
        assertEquals("controller <redacted:mac> connected", LogRedactor.redact("controller A2-B3-C4-D5-E6-F7 connected"))
        assertEquals("mac=<redacted:mac>, peer=<redacted:mac>", LogRedactor.redact("mac=02:ab:cd:ef:12:34, peer=a2:b3:c4:d5:e6:f7"))
        assertEquals("MAC:<redacted:mac>", LogRedactor.redact("MAC:02:ab:cd:ef:12:34"))
        assertEquals("MacAddress:<redacted:mac>", LogRedactor.redact("MacAddress:A2-B3-C4-D5-E6-F7"))
        assertEquals("{\"mac_address\":\"<redacted:mac>\"}", LogRedactor.redact("{\"mac_address\":\"02:ab:cd:ef:12:34\"}"))
    }

    @Test fun macRedactionPreservesOtherDiagnosticValues() {
        val line = "[12:34:56] server ab:cd:ef:12:34:56:78:90 partial=ab:cd:ef:12:34 mixed=ab:cd-ef:12:34:56"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test fun labelledDeviceIdentifiersAreRedacted() {
        for (label in listOf("Serial number", "serial_number", "serialNumber", "serial", "device_serial", "Controller Serial", "ANDROID_ID", "build.serial")) {
            assertEquals("$label: <redacted:serial>", LogRedactor.redact("$label: controller-123456"))
        }
        assertEquals("Serial number: <redacted:serial> connected", LogRedactor.redact("Serial number: 12345678901234567 connected"))
        assertEquals("{\"serial_number\":\"<redacted:serial>\",\"result\":0}", LogRedactor.redact("{\"serial_number\":\"controller 123456\",\"result\":0}"))
        assertEquals("device_serial = '<redacted:serial>'", LogRedactor.redact("device_serial = 'controller 123456'"))
    }

    @Test fun serialRedactionPreservesUnlabelledDiagnostics() {
        val line = "serial port initialized; serial_number_count=2; controller=12345678901234567"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test fun redactingTwiceChangesNothing() {
        val lines = listOf(
            "Using JWT 25484942796017334",
            "token=abcdefghijklmnop sessionid=qrstuvwxyz123",
            "user 76561198012341234 [U:1:52075506]",
            "external address 2607:f8b0:4005:80a::200e",
            "OnLoginStateChange someaccount 2 1 0 0",
            "mac 02:ab:cd:ef:12:34 controller A2-B3-C4-D5-E6-F7",
            "MAC:02:ab:cd:ef:12:34",
            "Serial number: 12345678901234567",
            "{\"ANDROID_ID\":\"0123456789abcdef\"}",
        )
        for (line in lines) {
            val once = LogRedactor.redact(line)
            assertEquals(once, LogRedactor.redact(once))
        }
    }

    // The Steam UI's own login line (webhelper_js.txt), as the privacy scan found it.
    @Test fun loginStateLineLosesAnEmailAccount() {
        val line = "[2026-10-07 21:14:03] SteamUI: INFO: Login: OnLoginStateChange someone.masked@example.com 2 1 0 0"
        val out = LogRedactor.redact(line)
        assertFalse(out.contains("someone.masked"))
        assertFalse(out.contains("example.com"))
        assertEquals("[2026-10-07 21:14:03] SteamUI: INFO: Login: OnLoginStateChange <redacted:account> 2 1 0 0", out)
        assertEquals(out, LogRedactor.redactForShare(line))
    }

    @Test fun loginStateLineLosesAPlainAccountName() {
        val line = "[2026-10-07 21:14:03] SteamUI: INFO: Login: OnLoginStateChange maskeduser42 2 1 0 0"
        val out = LogRedactor.redact(line)
        assertFalse(out.contains("maskeduser42"))
        assertEquals("[2026-10-07 21:14:03] SteamUI: INFO: Login: OnLoginStateChange <redacted:account> 2 1 0 0", out)
        assertEquals(out, LogRedactor.redactForShare(line))
    }

    @Test fun loginStateWithoutAnAccountIsKept() {
        val line = "SteamUI: INFO: Login: OnLoginStateChange  0 1 0 0"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test fun accountNameFieldsAreRedacted() {
        val lines = listOf(
            "\t\"AccountName\"\t\t\"maskeduser42\"",
            "account_name=maskeduser42 remember=1",
            "{\"login\":\"maskeduser42\",\"persist\":1}",
            "auth: login=maskeduser42&remember=1",
            "accountName: maskeduser42",
            "{\"username\": \"masked user 42\"}",
        )
        for (line in lines) {
            val out = LogRedactor.redactForShare(line)
            assertFalse(out, out.contains("maskeduser42") || out.contains("masked user"))
            assertTrue(out, out.contains("<redacted:account>"))
            assertEquals(out, LogRedactor.redactForShare(out))
        }
    }

    @Test fun loginAsAWordIsNotAField() {
        for (line in listOf("SteamUI: INFO: Login: OnLoginStateChange  0 1 0 0", "login refresh pending", "Login: state 5")) {
            assertEquals(line, LogRedactor.redact(line))
        }
    }
}
