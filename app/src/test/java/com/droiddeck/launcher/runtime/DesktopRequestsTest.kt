package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopRequestsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aRequestIsTheProgramThenOneArgumentALine() {
        val dir = File(tmp.root, "session")
        DesktopRequests.open(dir, "/usr/local/bin/droiddeck-battlenet", listOf("open", "a b"))
        assertEquals("/usr/local/bin/droiddeck-battlenet\nopen\na b\n", File(dir, DesktopRequests.REQUEST).readText())
        assertEquals(listOf(DesktopRequests.REQUEST), dir.list()!!.toList())
    }

    @Test fun aGamesInstallIsAskedForWithItsId() {
        DesktopRequests.reset(tmp.root, BattleNet.PROGRAM, listOf("install-game", "fenris"))
        assertEquals(BattleNet.PROGRAM + "\ninstall-game\nfenris\n", File(tmp.root, DesktopRequests.REQUEST).readText())
    }

    @Test fun aStartingDesktopKeepsOnlyItsOwnRequest() {
        val dir = tmp.root
        DesktopRequests.open(dir, "/usr/local/bin/droiddeck-old")
        DesktopRequests.reset(dir, null)
        assertFalse(File(dir, DesktopRequests.REQUEST).exists())
        DesktopRequests.open(dir, "/usr/local/bin/droiddeck-old")
        DesktopRequests.reset(dir, BattleNet.PROGRAM)
        assertEquals(BattleNet.PROGRAM + "\n", File(dir, DesktopRequests.REQUEST).readText())
    }

    @Test fun aLineBreakOrARelativePathIsNoRequest() {
        assertThrows(IllegalArgumentException::class.java) { DesktopRequests.open(tmp.root, "droiddeck-battlenet") }
        assertThrows(IllegalArgumentException::class.java) { DesktopRequests.open(tmp.root, "/usr/local/bin/x", listOf("a\nb")) }
        assertFalse(File(tmp.root, DesktopRequests.REQUEST).exists())
    }
}
