package com.droiddeck.launcher.frontend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddedGamesAccountTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun steamRoot(vararg blocks: Pair<Long, Map<String, String>>): File = tmp.newFolder("Steam").also { root ->
        File(root, "config").mkdirs()
        File(root, "userdata/123/config").mkdirs()
        File(root, "userdata/456/config").mkdirs()
        val body = blocks.joinToString("") { (account, keys) ->
            "\t\"${76561197960265728L + account}\"\n\t{\n" + keys.entries.joinToString("") { "\t\t\"${it.key}\"\t\t\"${it.value}\"\n" } + "\t}\n"
        }
        File(root, "config/loginusers.vdf").writeText("\"users\"\n{\n$body}\n")
    }

    @Test fun theAccountMarkedMostRecentIsUsed() {
        val root = steamRoot(123L to mapOf("MostRecent" to "1", "AutoLogin" to "0", "timestamp" to "100"),
            456L to mapOf("MostRecent" to "0", "AutoLogin" to "1", "timestamp" to "200"))
        assertEquals(File(root, "userdata/123/config"), AddedGames.accountConfig(root))
    }

    @Test fun withoutMostRecentTheAccountThatSignsInIsUsed() {
        val root = steamRoot(123L to mapOf("AutoLogin" to "1", "timestamp" to "100"), 456L to mapOf("AutoLogin" to "0", "timestamp" to "200"))
        assertEquals(File(root, "userdata/123/config"), AddedGames.accountConfig(root))
    }

    @Test fun withoutEitherMarkTheAccountUsedLastIsUsed() {
        val root = steamRoot(456L to mapOf("AutoLogin" to "0", "timestamp" to "100"), 123L to mapOf("AutoLogin" to "0", "timestamp" to "200"))
        assertEquals(File(root, "userdata/123/config"), AddedGames.accountConfig(root))
    }

    @Test fun twoAccountsWithoutAnyMarkGiveNoAccount() {
        val root = steamRoot(123L to mapOf("AccountName" to "a"), 456L to mapOf("AccountName" to "b"))
        assertNull(AddedGames.accountConfig(root))
    }
}
