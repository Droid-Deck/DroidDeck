package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamAccountsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = 76561198000000001L
    private val b = 76561198000000002L
    private val c = 76561198000000003L

    private fun ids(name: String) = SteamAccounts.parse(FIXTURES.getValue(name)).map { it.id64 }

    // The ids and order tools/tests/test_steam_account.py expects of the same files.
    @Test fun parseMatchesTheGuestHelper() {
        assertEquals(listOf(a), ids("one.vdf"))
        assertEquals(listOf(a, b), ids("two.vdf"))
        assertEquals(listOf(a, b, c), ids("three.vdf"))
        assertEquals(listOf(a, b), ids("escaped-persona.vdf"))
        assertEquals(listOf(a, b), ids("comments.vdf"))
        assertEquals(listOf(a, b), ids("no-mostrecent.vdf"))
        assertEquals(listOf(a, b, c), ids("several-recent.vdf"))
        val two = FIXTURES.getValue("two.vdf")
        assertEquals(listOf(a, b), SteamAccounts.parse(two.replace("\n", "\r\n")).map { it.id64 })
        assertEquals(listOf(a, b), SteamAccounts.parse("\uFEFF" + two).map { it.id64 })
    }

    @Test fun onlyRememberedAccountsAreListed() {
        assertEquals(listOf(a), ids("not-remembered.vdf"))
    }

    @Test fun accountFields() {
        val list = SteamAccounts.parse(FIXTURES.getValue("two.vdf"))
        assertEquals(SteamAccounts.Account(a, "alpha_acct", "Alpha Persona", true), list[0])
        assertEquals(SteamAccounts.Account(b, "bravo_acct", "Bravo Persona", false), list[1])
        assertEquals("Bra\"vo {x} // y", SteamAccounts.parse(FIXTURES.getValue("escaped-persona.vdf"))[1].persona)
    }

    @Test fun malformedFilesListNothing() {
        for (name in listOf("unbalanced.vdf", "no-root.vdf", "duplicate-id.vdf", "bad-id.vdf", "no-accountname.vdf", "unterminated.vdf")) {
            assertEquals(name, emptyList<Long>(), ids(name))
        }
        assertEquals(emptyList<SteamAccounts.Account>(), SteamAccounts.parse(""))
        assertEquals(emptyList<SteamAccounts.Account>(), SteamAccounts.parse("\u0000users\u0000"))
    }

    @Test fun labelsTellMatchingPersonasApart() {
        val x = SteamAccounts.Account(a, "one", "Player", true)
        val y = SteamAccounts.Account(b, "two", "Player", false)
        val z = SteamAccounts.Account(c, "three", "", false)
        assertEquals("Player · one", SteamAccounts.label(x, listOf(x, y, z)))
        assertEquals("Player", SteamAccounts.label(x, listOf(x, z)))
        assertEquals("three", SteamAccounts.label(z, listOf(x, z)))
    }

    private fun request(root: File) = File(root, "root/.config/droiddeck/steam-account-next")

    @Test fun pendingAcceptsOnlyASteamId() {
        val root = tmp.root
        assertNull(SteamAccounts.pending(root))
        request(root).apply { parentFile!!.mkdirs() }.writeText("$b\n")
        assertEquals(b, SteamAccounts.pending(root))
        for (bad in listOf("", "12345", "${b}x", "bravo_acct", "765611980000000021", "\u0000")) {
            request(root).writeText(bad)
            assertNull(bad, SteamAccounts.pending(root))
        }
    }

    @Test fun noRequestWhileASessionRuns() {
        assertFalse(SteamAccounts.requestAllowed(true))
        assertTrue(SteamAccounts.requestAllowed(false))
    }

    @Test fun requestWritesOneLineAndTheActiveAccountCancelsIt() {
        val root = tmp.root
        assertTrue(SteamAccounts.request(root, b, a))
        assertEquals("$b\n", request(root).readText())
        assertFalse(File(request(root).parentFile, "steam-account-next.tmp").exists())
        assertEquals(b, SteamAccounts.pending(root))
        assertTrue(SteamAccounts.request(root, a, a))
        assertFalse(request(root).exists())
        assertTrue(SteamAccounts.request(root, a, a))
        assertFalse(SteamAccounts.request(root, 12345L, a))
        assertFalse(request(root).exists())
    }

    companion object {
        // Copies of tools/tests/fixtures/steam-accounts (test_steam_account.py checks they match).
        val FIXTURES = mapOf(
        "one.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
}
""",
        "two.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
}
""",
        "three.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
	"76561198000000003"
	{
		"AccountName"		"charlie_acct"
		"PersonaName"		"Charlie Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000003"
	}
}
""",
        "escaped-persona.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bra\"vo {x} // y"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
}
""",
        "comments.vdf" to """// written by hand
"users" // the accounts
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
}
""",
        "no-mostrecent.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"Timestamp"		"1700000002"
	}
}
""",
        "several-recent.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000002"
	}
	"76561198000000003"
	{
		"AccountName"		"charlie_acct"
		"PersonaName"		"Charlie Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000003"
	}
}
""",
        "not-remembered.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"0"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
}
""",
        "unbalanced.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}

""",
        "no-root.vdf" to """"accounts"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
}
""",
        "duplicate-id.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000001"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000001"
	}
}
""",
        "bad-id.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"12345"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000045"
	}
}
""",
        "no-accountname.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"PersonaName"		"Bravo Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
}
""",
        "unterminated.vdf" to """"users"
{
	"76561198000000001"
	{
		"AccountName"		"alpha_acct"
		"PersonaName"		"Alpha Persona"
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"1"
		"Timestamp"		"1700000001"
	}
	"76561198000000002"
	{
		"AccountName"		"bravo_acct"
		"PersonaName"		"Bravo Persona
		"RememberPassword"		"1"
		"WantsOfflineMode"		"0"
		"SkipOfflineModeWarning"		"0"
		"AllowAutoLogin"		"1"
		"MostRecent"		"0"
		"Timestamp"		"1700000002"
	}
}
""",
        )
    }
}
