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

    @Test fun skillsGoToEveryHarnessAndLeaveTheUsersOwnAlone() {
        val root = tmp.newFolder("rootfs")
        val mine = File(root, "root/.claude/skills/my-skill/SKILL.md").apply { parentFile!!.mkdirs(); writeText("mine") }
        val stale = File(root, "root/.codex/skills/diagnose-game/SKILL.md").apply { parentFile!!.mkdirs(); writeText("old") }

        AgentSetup.installSkills(root, mapOf("diagnose-game" to listOf("SKILL.md"))) { "new $it".byteInputStream() }

        for (harness in AgentHarness.all) {
            assertEquals("new diagnose-game/SKILL.md", File(root, "${harness.skills}/diagnose-game/SKILL.md").readText())
        }
        assertEquals("new diagnose-game/SKILL.md", stale.readText())
        assertEquals("mine", mine.readText())
        assertFalse(File(root, "${ClaudeCode.skills}/diagnose-game/SKILL.md").canExecute())
    }
}
