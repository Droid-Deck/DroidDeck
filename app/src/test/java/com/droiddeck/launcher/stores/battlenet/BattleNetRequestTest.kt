package com.droiddeck.launcher.stores.battlenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BattleNetRequestTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun requestsLeftBeforeThePollAreAllKeptInOrder() {
        val root = tmp.root
        assertTrue(BattleNetClient.request(root, listOf("install-game", "wow")))
        assertTrue(BattleNetClient.request(root, listOf("install-game", "fenris")))
        assertTrue(BattleNetClient.request(root, listOf("launch", "OSI")))
        val names = root.list()!!.sorted()
        assertEquals(3, names.size)
        assertTrue(names.all { Regex("battlenet-request\\.\\d{20}").matches(it) })
        assertEquals(listOf("install-game\nwow\n", "install-game\nfenris\n", "launch\nOSI\n"), names.map { java.io.File(root, it).readText() })
    }

    @Test fun aLineBreakInAnArgumentIsRefused() {
        assertFalse(BattleNetClient.request(tmp.root, listOf("launch", "OSI\nreboot")))
        assertEquals(0, tmp.root.list()!!.size)
    }

    @Test fun aNewSessionClearsEveryLeftRequest() {
        val root = tmp.root
        BattleNetClient.request(root, listOf("launch", "WTCG"))
        java.io.File(root, "battlenet-request").writeText("launch\nPro\n")
        java.io.File(root, "battlenet-request.1.tmp").writeText("launch\nPro\n")
        java.io.File(root, "battlenet-fex.request").writeText("x")
        BattleNetClient.clearRequests(root)
        assertEquals(listOf("battlenet-fex.request"), root.list()!!.toList())
    }
}
