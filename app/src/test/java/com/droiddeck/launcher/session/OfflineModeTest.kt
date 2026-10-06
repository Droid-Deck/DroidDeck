package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModeTest {
    // OfflineMode.account before accounts could be switched, verbatim.
    private val REMEMBER = Regex(""""RememberPassword"\s*"1"""")
    private val PERSONA = Regex(""""PersonaName"\s*"([^"]*)"""")
    private fun old(text: String): String? {
        if (!REMEMBER.containsMatchIn(text)) return null
        return PERSONA.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "signed in"
    }

    private val one = SteamAccountsTest.FIXTURES.getValue("one.vdf")
    private val two = SteamAccountsTest.FIXTURES.getValue("two.vdf")

    @Test fun oneAccountReadsAsBefore() {
        val cases = listOf(
            one,
            one.replace("\"RememberPassword\"\t\t\"1\"", "\"RememberPassword\"\t\t\"0\""),
            one.replace("\"Alpha Persona\"", "\"\""),
            one.replace("\"Alpha Persona\"", "\"   \""),
            one.replace("\t\t\"MostRecent\"\t\t\"1\"\n", ""),
            one.replace("\t\t\"PersonaName\"\t\t\"Alpha Persona\"\n", ""),
            "",
            "garbage",
        )
        for (text in cases) assertEquals(text, old(text), SteamAccounts.activeLabel(text))
        assertEquals("Alpha Persona", SteamAccounts.activeLabel(one))
        assertNull(SteamAccounts.activeLabel(cases[1]))
        assertEquals("signed in", SteamAccounts.activeLabel(cases[2]))
    }

    @Test fun twoAccountsNameTheMostRecentOne() {
        assertEquals("Alpha Persona", SteamAccounts.activeLabel(two))
        val switched = two.replaceFirst("\"MostRecent\"\t\t\"1\"", "\"MostRecent\"\t\t\"0\"")
            .replaceFirst(Regex("(\"76561198000000002\"[^}]*\"MostRecent\"\\t\\t\")0"), "$11")
        assertEquals("Bravo Persona", SteamAccounts.activeLabel(switched))
        // No account marked: the first persona, as before.
        assertEquals("Alpha Persona", SteamAccounts.activeLabel(two.replace("\"MostRecent\"\t\t\"1\"", "\"MostRecent\"\t\t\"0\"")))
    }

    private val twoAccounts = listOf(
        "\"users\"", "{",
        "\t\"76561197960287930\"", "\t{",
        "\t\t\"AccountName\"\t\t\"alice\"",
        "\t\t\"RememberPassword\"\t\t\"1\"",
        "\t\t\"WantsOfflineMode\"\t\t\"0\"",
        "\t\t\"SkipOfflineModeWarning\"\t\t\"0\"",
        "\t\t\"MostRecent\"\t\t\"0\"",
        "\t}",
        "\t\"76561197960287931\"", "\t{",
        "\t\t\"AccountName\"\t\t\"bob\"",
        "\t\t\"RememberPassword\"\t\t\"1\"",
        "\t\t\"MostRecent\"\t\t\"1\"",
        "\t}",
        "}",
    )

    @Test fun anAccountAddedLaterGetsTheOfflineKeysToo() {
        val out = OfflineMode.rewrite(twoAccounts, "1")!!
        val bob = out.subList(out.indexOfFirst { it.contains("\"bob\"") }, out.lastIndexOf("\t}"))
        assertTrue(bob.any { it.trim() == "\"WantsOfflineMode\"\t\t\"1\"" })
        assertTrue(bob.any { it.trim() == "\"SkipOfflineModeWarning\"\t\t\"1\"" })
        val alice = out.subList(0, out.indexOfFirst { it.contains("\"bob\"") })
        assertEquals(1, alice.count { it.contains("WantsOfflineMode") })
        assertTrue(alice.any { it.trim() == "\"WantsOfflineMode\"\t\t\"1\"" })
        assertEquals(2, out.count { it.contains("WantsOfflineMode") })
    }

    @Test fun nothingChangesWhenEveryAccountAlreadyAsks() {
        val once = OfflineMode.rewrite(twoAccounts, "0")!!
        assertNull(OfflineMode.rewrite(once, "0"))
    }
}
