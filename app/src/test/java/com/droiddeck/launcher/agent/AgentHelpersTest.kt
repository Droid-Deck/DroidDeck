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
        File(stale.parentFile, AgentSetup.SKILL_MARK).writeText("")

        val failed = AgentSetup.installSkills(root, mapOf("diagnose-game" to listOf("SKILL.md"))) { "new $it".byteInputStream() }

        assertEquals(emptyList<String>(), failed)
        for (harness in AgentHarness.all) {
            assertEquals("new diagnose-game/SKILL.md", File(root, "${harness.skills}/diagnose-game/SKILL.md").readText())
        }
        assertEquals("mine", mine.readText())
        assertFalse(File(root, "${ClaudeCode.skills}/diagnose-game/SKILL.md").canExecute())
    }

    @Test fun aSkillTheUserMadeWithTheSameNameIsKept() {
        val root = tmp.newFolder("rootfs3")
        val own = File(root, "${ClaudeCode.skills}/diagnose-game/SKILL.md").apply { parentFile!!.mkdirs(); writeText("my version") }

        AgentSetup.installSkills(root, mapOf("diagnose-game" to listOf("SKILL.md"))) { "new".byteInputStream() }

        assertEquals("my version", own.readText())
        assertFalse(File(own.parentFile, AgentSetup.SKILL_MARK).exists())
        assertEquals("new", File(root, "${Codex.skills}/diagnose-game/SKILL.md").readText())
    }

    @Test fun aSkillThatFailedPartWayIsRetriedNextTurn() {
        val root = tmp.newFolder("rootfs5")
        val skills = mapOf("diagnose-game" to listOf("SKILL.md"))

        val failed = AgentSetup.installSkills(root, skills) { throw java.io.IOException("asset unreadable") }
        assertEquals(AgentHarness.all.size, failed.size)

        assertEquals(emptyList<String>(), AgentSetup.installSkills(root, skills) { "new".byteInputStream() })
        for (harness in AgentHarness.all) assertEquals("new", File(root, "${harness.skills}/diagnose-game/SKILL.md").readText())
    }

    @Test fun instructionsBehindAGuestSymlinkAreWrittenWhereItPoints() {
        val root = tmp.newFolder("rootfs4")
        val dotfile = File(root, "root/dotfiles/CLAUDE.md").apply { parentFile!!.mkdirs(); writeText("my rule\n") }
        val link = File(root, ClaudeCode.instructions).apply { parentFile!!.mkdirs() }
        Files.createSymbolicLink(link.toPath(), File("/root/dotfiles/CLAUDE.md").toPath())

        AgentSetup.installInstructions(root, "rules")

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(dotfile.readText().contains("rules"))
        assertTrue(dotfile.readText().endsWith("\nmy rule\n"))
    }

    @Test fun theRulesGoFirstInAFileThatHasNoBlockYetAndKeepTheUsersOwn() {
        val text = AgentSetup.withBlock("# Mine\nAlways answer in French.\n", "rules v1")
        assertEquals("${AgentSetup.BLOCK_BEGIN}\nrules v1\n${AgentSetup.BLOCK_END}\n\n# Mine\nAlways answer in French.\n", text)
    }

    @Test fun onlyTheBlockIsReplacedOnTheNextTurn() {
        val first = AgentSetup.withBlock("before\n", "rules v1") + "after\n"
        val second = AgentSetup.withBlock(first, "rules v2")
        assertEquals(first.replace("rules v1", "rules v2"), second)
        assertEquals(second, AgentSetup.withBlock(second, "rules v2"))
    }

    @Test fun anEmptyOrMissingFileGetsJustTheBlock() {
        assertEquals("${AgentSetup.BLOCK_BEGIN}\nrules\n${AgentSetup.BLOCK_END}\n", AgentSetup.withBlock(null, "rules\n"))
        assertEquals(AgentSetup.withBlock(null, "rules"), AgentSetup.withBlock("  \n", "rules"))
    }

    @Test fun everyHarnessGetsTheRules() {
        val root = tmp.newFolder("rootfs2")
        val claude = File(root, ClaudeCode.instructions).apply { parentFile!!.mkdirs(); writeText("my rule\n") }

        assertEquals(emptyList<String>(), AgentSetup.installInstructions(root, "rules"))

        for (harness in AgentHarness.all) assertTrue(File(root, harness.instructions).readText().contains("rules"))
        assertTrue(claude.readText().endsWith("\nmy rule\n"))
    }
}
