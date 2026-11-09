package com.droiddeck.launcher.agent

import com.droiddeck.launcher.agent.AgentHost.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentHostTest {
    @Test fun claudeInitCarriesOnlyTheConversation() {
        val event = ClaudeCode.parse("""{"type":"system","subtype":"init","session_id":"abc","tools":[]}""")!!
        assertEquals("abc", event.conversation)
        assertEquals(emptyList<Entry>(), event.entries)
    }

    @Test fun claudeTextAndToolUseBecomeEntries() {
        val event = ClaudeCode.parse(
            """{"type":"assistant","session_id":"abc","message":{"content":[
                {"type":"text","text":"  Looking at the log.  "},
                {"type":"tool_use","name":"Bash","input":{"command":"droiddeckctl state\nmore"}},
                {"type":"thinking","thinking":"hidden"}]}}""",
        )!!
        assertEquals(listOf(Entry.Said("Looking at the log."), Entry.Used("Bash", "droiddeckctl state")), event.entries)
    }

    @Test fun aClaudeResultEndsTheTurn() {
        assertEquals(listOf(Entry.Finished(null)), ClaudeCode.parse("""{"type":"result","subtype":"success","is_error":false,"result":"done"}""")!!.entries)
        assertEquals(listOf(Entry.Finished("Invalid API key")),
            ClaudeCode.parse("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key"}""")!!.entries)
        assertNull(ClaudeCode.parse("not json"))
    }

    @Test fun claudeTurnsBypassPermissionsAndResume() {
        val first = ClaudeCode.turn("hi", null)
        assertTrue("bypassPermissions" in first && "--resume" !in first)
        assertEquals("abc", ClaudeCode.turn("hi", "abc").let { it[it.indexOf("--resume") + 1] })
    }

    @Test fun codexEventsBecomeEntries() {
        assertEquals("t1", Codex.parse("""{"type":"thread.started","thread_id":"t1"}""")!!.conversation)
        assertEquals(listOf(Entry.Used("Shell", "uname -m")),
            Codex.parse("""{"type":"item.started","item":{"id":"i1","type":"command_execution","command":"uname -m","status":"in_progress"}}""")!!.entries)
        assertEquals(listOf(Entry.Said("It is aarch64.")),
            Codex.parse("""{"type":"item.completed","item":{"id":"i2","type":"agent_message","text":"It is aarch64."}}""")!!.entries)
        assertEquals(listOf(Entry.Used("Edit", "a.kt, b.kt")),
            Codex.parse("""{"type":"item.completed","item":{"type":"file_change","changes":[{"path":"a.kt"},{"path":"b.kt"}]}}""")!!.entries)
        assertEquals(listOf(Entry.Finished(null)), Codex.parse("""{"type":"turn.completed","usage":{}}""")!!.entries)
        assertEquals(listOf(Entry.Finished("quota")), Codex.parse("""{"type":"turn.failed","error":{"message":"quota"}}""")!!.entries)
        assertNull(Codex.parse("""{"type":"item.started","item":{"type":"reasoning"}}"""))
    }

    @Test fun codexTurnsSkipItsSandboxAndResume() {
        assertEquals(listOf("/root/.local/bin/codex", "exec", "--json", "--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check", "hi"),
            Codex.turn("hi", null))
        assertEquals(listOf("exec", "resume", "t1"), Codex.turn("hi", "t1").subList(1, 4))
    }

    @Test fun anUnknownHarnessFallsBackToClaudeCode() {
        assertEquals(Codex, AgentHarness.byId("codex"))
        assertEquals(ClaudeCode, AgentHarness.byId("gone"))
    }

    @Test fun aTurnWithoutAnEndWasInterrupted() {
        assertTrue(AgentHost.interrupted(listOf(Entry.Asked("hi"), Entry.Used("Bash", "dd-lab-build"))))
        assertEquals(false, AgentHost.interrupted(listOf(Entry.Asked("hi"), Entry.Finished(null))))
        assertEquals(false, AgentHost.interrupted(emptyList()))
    }

    @Test fun theTranscriptSurvivesARestart() {
        val entries = listOf(Entry.Asked("hi"), Entry.Said("hello"), Entry.Used("Read", "/root/x"), Entry.Finished(null), Entry.Finished("stopped"))
        val file = Files.createTempFile("transcript", ".jsonl").toFile()
        file.writeText(entries.joinToString("\n") { AgentHost.encode(it) } + "\nbroken line\n")
        assertEquals(entries, AgentHost.readTranscript(file))
        assertEquals(emptyList<Entry>(), AgentHost.readTranscript(File(file.path + ".missing")))
    }
}
