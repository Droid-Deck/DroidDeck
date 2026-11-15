package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class AgentHelpersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aSymlinkLeftByAHandMadeSetupIsReplacedNotFollowed() {
        val bin = tmp.newFolder("bin")
        val old = tmp.newFile("old-droiddeckctl").apply { writeText("old") }
        val target = File(bin, "droiddeckctl")
        Files.createSymbolicLink(target.toPath(), old.toPath())

        AgentSetup.replaceFile("new".byteInputStream(), target)

        assertFalse(Files.isSymbolicLink(target.toPath()))
        assertEquals("new", target.readText())
        assertEquals("old", old.readText())
        assertTrue(target.canExecute())
    }

    @Test fun aDanglingSymlinkIsReplaced() {
        val bin = tmp.newFolder("bin")
        val target = File(bin, "droiddeckctl")
        Files.createSymbolicLink(target.toPath(), File("/no/such/userland/path").toPath())

        AgentSetup.replaceFile("new".byteInputStream(), target)

        assertEquals("new", target.readText())
        assertEquals(listOf("droiddeckctl"), bin.list()!!.toList())
    }
}
