package com.droiddeck.launcher.agent

import com.droiddeck.launcher.agent.AgentHost.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun claudeTextThinkingAndToolUseBecomeEntries() {
        val event = ClaudeCode.parse(
            """{"type":"assistant","session_id":"abc","message":{"content":[
                {"type":"text","text":"  Looking at the log.  "},
                {"type":"tool_use","id":"t1","name":"Bash","input":{"command":"droiddeckctl state\nmore"}},
                {"type":"thinking","thinking":"why"}],"usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":5}}}""",
        )!!
        assertEquals(listOf(Entry.Said("Looking at the log."), Entry.Used("t1", "Bash", "droiddeckctl state", "droiddeckctl state\nmore"), Entry.Thought("why")), event.entries)
        assertEquals(105L, event.context)
    }

    @Test fun claudeStreamsTextAsItIsWritten() {
        val event = ClaudeCode.parse("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}""")!!
        assertEquals("Hel", event.text)
        assertEquals("hm", ClaudeCode.parse("""{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"hm"}}}""")!!.thinking)
        assertNull(ClaudeCode.parse("""{"type":"stream_event","event":{"type":"message_start"}}"""))
    }

    @Test fun claudeToolResultsAnswerTheirCall() {
        val event = ClaudeCode.parse(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"ok"}],"is_error":true}]}}""",
        )!!
        assertEquals(listOf(Entry.Result("t1", "ok", true)), event.entries)
        val entries = mutableListOf<Entry>(Entry.Used("t1", "Bash", "ls", "ls"))
        assertTrue(AgentChats.merge(entries, event.entries.single()))
        assertEquals(Entry.Used("t1", "Bash", "ls", "ls", "ok", true), entries.single())
        assertFalse(AgentChats.merge(entries, Entry.Result("unknown", "x", false)))
    }

    @Test fun aClaudeResultEndsTheTurnWithItsUsage() {
        val event = ClaudeCode.parse("""{"type":"result","subtype":"success","is_error":false,"result":"done","duration_ms":4200,"total_cost_usd":0.05,"usage":{"input_tokens":3,"output_tokens":7}}""")!!
        assertEquals(listOf(Entry.Finished(null)), event.entries)
        assertEquals(AgentHost.Usage(4200, 0.05, 3, 7), event.usage)
        assertEquals(listOf(Entry.Finished("Invalid API key")),
            ClaudeCode.parse("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key"}""")!!.entries)
        assertNull(ClaudeCode.parse("not json"))
    }

    @Test fun claudeTurnsBypassPermissionsOrPlanAndTakeAModel() {
        val first = ClaudeCode.turn("hi", null, AgentHarness.Options())
        assertTrue("bypassPermissions" in first && "--resume" !in first && "--model" !in first && "--include-partial-messages" in first)
        val next = ClaudeCode.turn("hi", "abc", AgentHarness.Options(model = "opus", plan = true))
        assertEquals("abc", next[next.indexOf("--resume") + 1])
        assertEquals("opus", next[next.indexOf("--model") + 1])
        assertEquals("plan", next[next.indexOf("--permission-mode") + 1])
    }

    @Test fun claudeShowsWholeEditsAndTodos() {
        assertEquals("a.kt\n\n- old\n\n+ new", ClaudeCode.fullInput("Edit", org.json.JSONObject("""{"file_path":"a.kt","old_string":"old","new_string":"new"}""")))
        assertEquals("[x] read\n[~] fix\n[ ] test", ClaudeCode.fullInput("TodoWrite", org.json.JSONObject(
            """{"todos":[{"content":"read","status":"completed"},{"content":"fix","status":"in_progress"},{"content":"test","status":"pending"}]}""")))
    }

    @Test fun codexEventsBecomeEntries() {
        assertEquals("t1", Codex.parse("""{"type":"thread.started","thread_id":"t1"}""")!!.conversation)
        assertEquals(listOf(Entry.Used("i1", "Shell", "uname -m", "uname -m")),
            Codex.parse("""{"type":"item.started","item":{"id":"i1","type":"command_execution","command":"uname -m","status":"in_progress"}}""")!!.entries)
        assertEquals(listOf(Entry.Result("i1", "aarch64\n", false)),
            Codex.parse("""{"type":"item.completed","item":{"id":"i1","type":"command_execution","command":"uname -m","aggregated_output":"aarch64\n","exit_code":0}}""")!!.entries)
        assertEquals(listOf(Entry.Said("It is aarch64.")),
            Codex.parse("""{"type":"item.completed","item":{"id":"i2","type":"agent_message","text":"It is aarch64."}}""")!!.entries)
        assertEquals(Entry.Used("i3", "Edit", "a.kt, b.kt", "a.kt\nb.kt"),
            Codex.parse("""{"type":"item.completed","item":{"id":"i3","type":"file_change","status":"completed","changes":[{"path":"a.kt"},{"path":"b.kt"}]}}""")!!.entries.first())
        assertEquals(listOf(Entry.Finished(null)), Codex.parse("""{"type":"turn.completed","usage":{}}""")!!.entries)
        assertEquals(listOf(Entry.Finished("quota")), Codex.parse("""{"type":"turn.failed","error":{"message":"quota"}}""")!!.entries)
        assertNull(Codex.parse("""{"type":"item.started","item":{"type":"reasoning"}}"""))
    }

    @Test fun codexTurnsSkipItsSandboxResumeAndTakeAModel() {
        assertEquals(listOf("/root/.local/bin/codex", "exec", "--json", "--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check", "hi"),
            Codex.turn("hi", null, AgentHarness.Options()))
        assertEquals(listOf("/root/.local/bin/codex", "exec", "resume", "t1", "--json", "--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check", "-m", "gpt", "hi"),
            Codex.turn("hi", "t1", AgentHarness.Options(model = "gpt")))
    }

    @Test fun aTurnWithoutAnEndWasInterrupted() {
        assertTrue(AgentHost.interrupted(listOf(Entry.Asked("hi"), Entry.Used("1", "Bash", "dd-lab-build"))))
        assertEquals(false, AgentHost.interrupted(listOf(Entry.Asked("hi"), Entry.Finished(null))))
    }

    @Test fun aChatRoundTripsThroughItsFile() {
        val file = Files.createTempFile("chat", ".jsonl").toFile()
        AgentChats.start(file, "claude")
        AgentChats.appendConversation(file, "abc")
        val written = listOf(
            Entry.Asked("hi", listOf("/root/x.png")), Entry.Said("hello"), Entry.Thought("hm"), Entry.Used("t1", "Read", "/root/x", "{}"),
            Entry.Result("t1", "contents", false), Entry.Finished(null, usage = AgentHost.Usage(1, 0.5, 2, 3)), Entry.Finished("boom", stopped = true),
        )
        written.forEach { AgentChats.append(file, it) }
        file.appendText("broken line\n")
        val loaded = AgentChats.read(file)
        assertEquals("claude", loaded.harness)
        assertEquals("abc", loaded.conversation)
        assertEquals(listOf(
            Entry.Asked("hi", listOf("/root/x.png")), Entry.Said("hello"), Entry.Thought("hm"), Entry.Used("t1", "Read", "/root/x", "{}", "contents", false),
            Entry.Finished(null, usage = AgentHost.Usage(1, 0.5, 2, 3)), Entry.Finished("boom", stopped = true),
        ), loaded.entries)
        assertEquals(emptyList<Entry>(), AgentChats.read(File(file.path + ".missing")).entries)
    }

    @Test fun longOutputKeepsItsEnd() {
        val clipped = AgentChats.clip("a".repeat(30_000) + "END")
        assertTrue(clipped.endsWith("END") && clipped.length < 30_000)
    }

    @Test fun attachmentsAreListedForTheAgent() {
        assertEquals("look", AgentAttachments.prompt("look", emptyList()))
        assertEquals("look\n\nAttached files (read them with your tools):\n- /root/a.png", AgentAttachments.prompt("look", listOf("/root/a.png")))
    }

    @Test fun procStatFieldsCountFromTheEndOfTheName() {
        val stat = AgentProcesses.parseStat("1234 (java (x) y) S 1200 1234 1234 0 -1 4194304 10 0 0 0 150 50 0 0 20 0 30 0 98765 1000000 2048 18446744073709551615")
        assertEquals(1200, stat.ppid)
        assertEquals(200L, stat.ticks)
        assertEquals(98765L, stat.startTicks)
        assertEquals(2048L, stat.rssPages)
    }

    @Test fun labProgressIsRead() {
        val p = LabProgress.parse("""{"stage":"building","mode":"build","permille":420,"task":":app:compileReleaseKotlin","done":120,"total":300,"eta":95,"elapsed":30,"pid":77}""")!!
        assertEquals(420, p.permille)
        assertTrue(p.running && !p.prepare)
        assertEquals(95, p.etaSeconds)
    }
}
