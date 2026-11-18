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

    @Test fun aSubagentShowsOnlyItsToolCallsUnderItsParent() {
        val event = ClaudeCode.parse(
            """{"type":"assistant","session_id":"abc","parent_tool_use_id":"task1","message":{"content":[
                {"type":"text","text":"I will look."},
                {"type":"tool_use","id":"t2","name":"Grep","input":{"pattern":"crash"}}],"usage":{"input_tokens":10,"output_tokens":5}}}""",
        )!!
        assertEquals(listOf(Entry.Used("t2", "Grep", "crash", "{\n  \"pattern\": \"crash\"\n}", parent = "task1")), event.entries)
        assertNull(event.context)
        assertNull(ClaudeCode.parse("""{"type":"stream_event","parent_tool_use_id":"task1","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}}"""))
    }

    @Test fun aParentlessMessageReadsTheNullAsNoParent() {
        val event = ClaudeCode.parse("""{"type":"assistant","parent_tool_use_id":null,"message":{"content":[{"type":"text","text":"hi"}]}}""")!!
        assertEquals(listOf(Entry.Said("hi")), event.entries)
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

    @Test fun aResultForALeftoverBackgroundTaskDoesNotEndTheTurn() {
        val event = ClaudeCode.parse("""{"type":"result","is_error":false,"num_turns":0,"session_id":"s","total_cost_usd":0,"usage":{"input_tokens":0,"output_tokens":0},"origin":{"kind":"task-notification"}}""")
        assertTrue(event == null || event.entries.none { it is Entry.Finished })
    }

    @Test fun claudeTakesItsMessagesOverStdinSoItStaysOpen() {
        val command = ClaudeCode.turn("hello there", null, AgentHarness.Options())
        assertEquals("stream-json", command[command.indexOf("--input-format") + 1])
        assertFalse("hello there" in command)
        val line = org.json.JSONObject(ClaudeCode.input("hello there"))
        assertEquals("user", line.getString("type"))
        assertEquals("hello there", line.getJSONObject("message").getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(listOf(ClaudeCode.input("hi")), ClaudeCode.protocol().input("hi", "abc", AgentHarness.Options(), started = false))
    }

    @Test fun aTaskNotificationResultIsMarkedBackgroundAndCarriesItsUsage() {
        val event = ClaudeCode.parse("""{"type":"result","is_error":false,"session_id":"s","duration_ms":900,"total_cost_usd":0.01,"usage":{"input_tokens":3,"output_tokens":4},"origin":{"kind":"task-notification"}}""")!!
        assertTrue(event.background)
        assertTrue(event.entries.isEmpty())
        assertEquals(4L, event.usage?.outputTokens)
        assertNull(event.backgroundError)
    }

    @Test fun retainedClaudeCostsAreRecordedOncePerProcess() {
        val costs = AgentCostTracker()
        fun usage(cost: Double) = AgentHost.Usage(1, cost, 1, 1)
        assertEquals(0.05, costs.perTurn(usage(0.05)).costUsd, 0.000001)
        assertEquals(0.02, costs.perTurn(usage(0.07)).costUsd, 0.000001)
        assertEquals(0.0, costs.perTurn(usage(0.07)).costUsd, 0.000001)
        costs.reset()
        assertEquals(0.0, costs.perTurn(usage(0.0)).costUsd, 0.000001)
        assertEquals(0.07, costs.perTurn(usage(0.07)).costUsd, 0.000001)
        assertEquals(0.01, costs.perTurn(usage(0.01)).costUsd, 0.000001)
        assertEquals(0.07, AgentCostTracker().perTurn(usage(0.07)).costUsd, 0.000001)
    }

    @Test fun aFailedTaskNotificationResultKeepsItsError() {
        val event = ClaudeCode.parse("""{"type":"result","is_error":true,"result":"API Error: overloaded","session_id":"s","usage":{"input_tokens":0,"output_tokens":0},"origin":{"kind":"task-notification"}}""")!!
        assertTrue(event.background)
        assertEquals("API Error: overloaded", event.backgroundError)
    }

    @Test fun aToolCallThatRunsInTheBackgroundIsMarked() {
        fun used(tool: String, input: String) = ClaudeCode.parse(
            """{"type":"assistant","session_id":"s","message":{"content":[{"type":"tool_use","id":"t","name":"$tool","input":$input}]}}"""
        )!!.entries.single() as Entry.Used
        assertTrue(used("Bash", """{"command":"./gradlew test","run_in_background":true}""").background)
        assertFalse(used("Bash", """{"command":"ls"}""").background)
        assertTrue(used("Monitor", """{"command":"tail -f log"}""").background)
    }

    @Test fun claudeTurnsAskFirstByDefaultAndTakeAModel() {
        val first = ClaudeCode.turn("hi", null, AgentHarness.Options())
        assertEquals("manual", first[first.indexOf("--permission-mode") + 1])
        assertTrue("--permission-prompt-tool=stdio" in first && "--resume" !in first && "--model" !in first && "--include-partial-messages" in first)
        val next = ClaudeCode.turn("hi", "abc", AgentHarness.Options(model = "opus", plan = true))
        assertEquals("abc", next[next.indexOf("--resume") + 1])
        assertEquals("opus", next[next.indexOf("--model") + 1])
        assertEquals("plan", next[next.indexOf("--permission-mode") + 1])
        assertFalse("--permission-prompt-tool=stdio" in next)
        fun mode(permission: String) = ClaudeCode.turn("hi", null, AgentHarness.Options(permission = permission)).let { it[it.indexOf("--permission-mode") + 1] }
        assertEquals(listOf("manual", "acceptEdits", "auto", "dontAsk", "bypassPermissions"), ClaudeCode.permissions.map { mode(it.id) })
        assertFalse("--permission-prompt-tool=stdio" in ClaudeCode.turn("hi", null, AgentHarness.Options(permission = AgentHarness.FULL)))
    }

    @Test fun claudePermissionPromptsBecomeApprovalsAnsweredOnStdin() {
        val event = ClaudeCode.parse("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"rm -rf build"},"tool_use_id":"t1"}}""")!!
        val approval = event.approval!!
        assertEquals(AgentHost.Approval("r1", "Bash", "rm -rf build", "rm -rf build", """{"command":"rm -rf build"}"""), approval)
        val allow = org.json.JSONObject(ClaudeCode.answer(approval, allow = true))
        assertEquals("control_response", allow.getString("type"))
        val response = allow.getJSONObject("response")
        assertEquals("r1", response.getString("request_id"))
        assertEquals("allow", response.getJSONObject("response").getString("behavior"))
        assertEquals("rm -rf build", response.getJSONObject("response").getJSONObject("updatedInput").getString("command"))
        val deny = org.json.JSONObject(ClaudeCode.answer(approval, allow = false)).getJSONObject("response").getJSONObject("response")
        assertEquals("deny", deny.getString("behavior"))
        assertTrue(deny.getString("message").isNotEmpty())
        assertEquals("r1", ClaudeCode.parse("""{"type":"control_cancel_request","request_id":"r1"}""")!!.resolved)
        assertNull(ClaudeCode.parse("""{"type":"control_request","request_id":"r2","request":{"subtype":"hook_callback"}}"""))
    }

    @Test fun claudeShowsWholeEditsAndTodos() {
        assertEquals("a.kt\n-old\n+new", ClaudeCode.fullInput("Edit", org.json.JSONObject("""{"file_path":"a.kt","old_string":"old","new_string":"new"}""")))
        assertEquals("b.kt\n+one\n+two", ClaudeCode.fullInput("Write", org.json.JSONObject("""{"file_path":"b.kt","content":"one\ntwo"}""")))
        assertEquals("[x] read\n[~] fix\n[ ] test", ClaudeCode.fullInput("TodoWrite", org.json.JSONObject(
            """{"todos":[{"content":"read","status":"completed"},{"content":"fix","status":"in_progress"},{"content":"test","status":"pending"}]}""")))
    }

    private fun json(line: String) = org.json.JSONObject(line)

    @Test fun codexOpensAThreadThenStartsTheTurn() {
        val command = Codex.turn("hi", null, AgentHarness.Options())
        assertEquals(listOf("/root/.local/bin/codex", "app-server"), command.takeLast(2))
        assertTrue(command[2].endsWith("2>/root/.cache/droiddeck/codex.log"))
        val p = Codex.Protocol()
        val init = json(p.input("hi", null, AgentHarness.Options(model = "gpt", effort = "high", brief = "brief"), started = true).single())
        assertEquals("initialize", init.getString("method"))
        val lines = p.parse("""{"id":${init.getInt("id")},"result":{"userAgent":"codex"}}""")!!.reply
        assertEquals(listOf("initialized", "thread/start"), lines.map { json(it).getString("method") })
        val start = json(lines[1])
        val params = start.getJSONObject("params")
        assertEquals("untrusted", params.getString("approvalPolicy"))
        assertEquals("danger-full-access", params.getString("sandbox"))
        assertEquals("brief", params.getString("developerInstructions"))
        val opened = p.parse("""{"id":${start.getInt("id")},"result":{"thread":{"id":"t1"},"approvalPolicy":"untrusted"}}""")!!
        assertEquals("t1", opened.conversation)
        val turn = json(opened.reply.single())
        assertEquals("turn/start", turn.getString("method"))
        assertEquals("t1", turn.getJSONObject("params").getString("threadId"))
        assertEquals("hi", turn.getJSONObject("params").getJSONArray("input").getJSONObject(0).getString("text"))
        assertEquals("high", turn.getJSONObject("params").getString("effort"))
        val next = json(p.input("again", "t1", AgentHarness.Options(), started = false).single())
        assertEquals("turn/start", next.getString("method"))
        assertEquals("t1", next.getJSONObject("params").getString("threadId"))
        fun opening(q: Codex.Protocol, conversation: String?, options: AgentHarness.Options): org.json.JSONObject {
            val id = json(q.input("hi", conversation, options, started = true).single()).getInt("id")
            return json(q.parse("""{"id":$id,"result":{}}""")!!.reply.last())
        }
        val resumed = opening(Codex.Protocol(), "t9", AgentHarness.Options(permission = AgentHarness.FULL))
        assertEquals("thread/resume", resumed.getString("method"))
        assertEquals("t9", resumed.getJSONObject("params").getString("threadId"))
        assertEquals("never", resumed.getJSONObject("params").getString("approvalPolicy"))
        assertEquals(listOf(Entry.Finished("no such thread")), Codex.Protocol().let { q ->
            val id = opening(q, "gone", AgentHarness.Options()).getInt("id")
            q.parse("""{"id":$id,"error":{"code":-32600,"message":"no such thread"}}""")!!.entries
        })
        assertEquals(listOf(Entry.Finished("not signed in")), Codex.Protocol().let { q ->
            val id = json(q.input("hi", null, AgentHarness.Options(), started = true).single()).getInt("id")
            q.parse("""{"id":$id,"error":{"code":-32600,"message":"not signed in"}}""")!!.entries
        })
    }

    @Test fun codexItemsBecomeEntries() {
        val p = Codex.Protocol()
        assertEquals(listOf(Entry.Used("e1", "Shell", "touch x", "touch x")), p.parse(
            """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"e1","command":"/bin/bash -lc 'touch x'","status":"inProgress","aggregatedOutput":null,"exitCode":null}}}""")!!.entries)
        assertEquals(listOf(Entry.Result("e1", "", false)), p.parse(
            """{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"e1","command":"/bin/bash -lc 'touch x'","status":"completed","aggregatedOutput":null,"exitCode":0}}}""")!!.entries)
        assertTrue((p.parse("""{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"e2","command":"false","status":"declined","aggregatedOutput":null,"exitCode":null}}}""")!!.entries.single() as Entry.Result).failed)
        assertEquals(listOf(Entry.Said("done")), p.parse("""{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"m1","text":"done","phase":"final_answer"}}}""")!!.entries)
        assertEquals("Do", p.parse("""{"method":"item/agentMessage/delta","params":{"itemId":"m1","delta":"Do"}}""")!!.text)
        assertEquals(listOf(Entry.Used("f1#0", "Edit", "/w/note.txt", "/w/note.txt\n+hi")), p.parse(
            """{"method":"item/started","params":{"item":{"type":"fileChange","id":"f1","changes":[{"path":"/w/note.txt","kind":{"type":"add"},"diff":"hi\n"}],"status":"inProgress"}}}""")!!.entries)
        assertEquals(listOf(Entry.Result("f1#0", "", false)), p.parse(
            """{"method":"item/completed","params":{"item":{"type":"fileChange","id":"f1","changes":[{"path":"/w/note.txt","kind":{"type":"add"},"diff":"hi\n"}],"status":"completed"}}}""")!!.entries)
        p.parse("""{"method":"turn/started","params":{"turn":{"id":"u1"}}}""")
        assertEquals("[x] read\n[~] fix\n[ ] test", (p.parse("""{"method":"turn/plan/updated","params":{"plan":[{"step":"read","status":"completed"},{"step":"fix","status":"inProgress"},{"step":"test","status":"pending"}]}}""")!!.entries.single() as Entry.Used).input)
        val usage = p.parse("""{"method":"thread/tokenUsage/updated","params":{"tokenUsage":{"total":{"totalTokens":32017},"last":{"totalTokens":16055,"inputTokens":16019,"outputTokens":36},"modelContextWindow":258400}}}""")!!
        assertEquals(16_055L, usage.context)
        assertEquals(258_400L, usage.window)
        assertEquals(listOf(AgentHost.Limit(10_080, 84, 1795123687)), p.parse(
            """{"method":"account/rateLimits/updated","params":{"rateLimits":{"limitId":"codex","primary":{"usedPercent":84,"windowDurationMins":10080,"resetsAt":1795123687},"secondary":null}}}""")!!.limits)
        val end = p.parse("""{"method":"turn/completed","params":{"turn":{"id":"u1","status":"completed","error":null,"durationMs":4200}}}""")!!
        assertEquals(listOf(Entry.Finished(null)), end.entries)
        assertEquals(AgentHost.Usage(4200, 0.0, 16_019, 36), end.usage)
        assertEquals(listOf(Entry.Finished("quota")), p.parse("""{"method":"turn/completed","params":{"turn":{"id":"u2","status":"failed","error":{"message":"quota"},"durationMs":null}}}""")!!.entries)
        assertNull(p.parse("""{"method":"error","params":{"error":{"message":"Reconnecting..."},"willRetry":true}}"""))
        assertNull(p.parse("not json"))
    }

    @Test fun codexApprovalRequestsWaitForTheUser() {
        val p = Codex.Protocol()
        val command = p.parse("""{"method":"item/commandExecution/requestApproval","id":0,"params":{"kind":"command","itemId":"e1","command":"/bin/bash -lc 'touch x'","cwd":"/w"}}""")!!.approval!!
        assertEquals(AgentHost.Approval("0", "Shell", "touch x", "touch x", """{"id":0}"""), command)
        assertEquals("""{"id":0,"result":{"decision":"accept"}}""", p.answer(command, allow = true))
        assertEquals("decline", json(p.answer(command, allow = false)).getJSONObject("result").getString("decision"))
        p.parse("""{"method":"item/started","params":{"item":{"type":"fileChange","id":"f1","changes":[{"path":"/w/a.kt","kind":{"type":"update"},"diff":"@@ -1 +1 @@\n-old\n+new\n"}],"status":"inProgress"}}}""")
        val edit = p.parse("""{"method":"item/fileChange/requestApproval","id":1,"params":{"itemId":"f1","reason":null}}""")!!.approval!!
        assertEquals("Edit", edit.tool)
        assertEquals("/w/a.kt", edit.detail)
        assertEquals("/w/a.kt\n-old\n+new", edit.input)
        val refused = json(p.parse("""{"method":"item/tool/requestUserInput","id":2,"params":{}}""")!!.reply.single())
        assertEquals(2, refused.getInt("id"))
        assertTrue(refused.has("error"))
        assertEquals("1", p.parse("""{"method":"serverRequest/resolved","params":{"requestId":1}}""")!!.resolved)
    }

    @Test fun aToolIdUsedAgainInALaterTurnIsANewCall() {
        val entries = mutableListOf<Entry>(Entry.Asked("one"), Entry.Used("item_0", "Shell", "ls", "ls", "a"), Entry.Finished(null), Entry.Asked("two"))
        assertFalse(AgentChats.merge(entries, Entry.Result("item_0", "late", false)))
        assertTrue(AgentChats.merge(entries, Entry.Used("item_0", "Shell", "pwd", "pwd")))
        assertTrue(AgentChats.merge(entries, Entry.Result("item_0", "/root", false)))
        assertEquals(Entry.Used("item_0", "Shell", "ls", "ls", "a"), entries[1])
        assertEquals(Entry.Used("item_0", "Shell", "pwd", "pwd", "/root"), entries.last())
    }

    @Test fun filesAttachedBeforeTheFirstMessageMoveIntoTheChat() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = AgentAttachments.hostDir(context)
        File(dir, "new").mkdirs()
        File(dir, "new/a.png").writeText("x")
        val moved = AgentAttachments.adopt(context, "42", listOf("/root/.cache/droiddeck/attachments/new/a.png", "/root/.cache/droiddeck/attachments/7/b.txt"))
        assertEquals(listOf("/root/.cache/droiddeck/attachments/42/a.png", "/root/.cache/droiddeck/attachments/7/b.txt"), moved)
        assertTrue(File(dir, "42/a.png").isFile)
        assertFalse(File(dir, "new/a.png").exists())
        AgentChats.delete(context, "42")
        assertFalse(File(dir, "42").exists())
    }

    @Test fun codexShellWrappersAreUnwrapped() {
        assertEquals("uname -m", shellCommand("/bin/bash -lc 'uname -m'"))
        assertEquals("echo 'hi'", shellCommand("/bin/bash -lc 'echo '\''hi'\'''"))
        assertEquals("ls", shellCommand("ls"))
    }

    @Test fun claudeCodeDefaultsToNoAttributionAndKeepsTheUsersChoice() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val settings = File(root, "root/.claude/settings.json")
        ClaudeCode.prepare(root)
        val written = org.json.JSONObject(settings.readText())
        assertEquals(false, written.getBoolean("includeCoAuthoredBy"))
        assertEquals("", written.getJSONObject("attribution").getString("commit"))
        assertEquals("", written.getJSONObject("attribution").getString("pr"))

        settings.writeText("""{"includeCoAuthoredBy":true,"model":"opus"}""")
        ClaudeCode.prepare(root)
        val kept = org.json.JSONObject(settings.readText())
        assertEquals(true, kept.getBoolean("includeCoAuthoredBy"))
        assertEquals("opus", kept.getString("model"))
        assertTrue(kept.has("attribution"))

        settings.writeText("not json")
        ClaudeCode.prepare(root)
        assertEquals("not json", settings.readText())
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
            Entry.Asked("hi", listOf("/root/x.png")), Entry.Said("hello"), Entry.Notice("service unavailable"), Entry.Thought("hm"), Entry.Used("t1", "Read", "/root/x", "{}"),
            Entry.Result("t1", "contents", false), Entry.Finished(null, usage = AgentHost.Usage(1, 0.5, 2, 3)), Entry.Finished("boom", stopped = true),
            Entry.Finished(null, usage = AgentHost.Usage(1, 0.5, 2, 3, context = 40_000, window = 200_000), planned = true),
        )
        written.forEach { AgentChats.append(file, it) }
        file.appendText("broken line\n")
        val loaded = AgentChats.read(file)
        assertEquals("claude", loaded.harness)
        assertEquals("abc", loaded.conversation)
        assertEquals(listOf(
            Entry.Asked("hi", listOf("/root/x.png")), Entry.Said("hello"), Entry.Notice("service unavailable"), Entry.Thought("hm"), Entry.Used("t1", "Read", "/root/x", "{}", "contents", false),
            Entry.Finished(null, usage = AgentHost.Usage(1, 0.5, 2, 3)), Entry.Finished("boom", stopped = true),
            Entry.Finished(null, usage = AgentHost.Usage(1, 0.5, 2, 3, context = 40_000, window = 200_000), planned = true),
        ), loaded.entries)
        assertEquals(emptyList<Entry>(), AgentChats.read(File(file.path + ".missing")).entries)
    }

    @Test fun aTurnThatEndsWithNothingIsAnswerless() {
        assertTrue(AgentHost.answerless(listOf(Entry.Said("x"), Entry.Asked("go on"), Entry.Finished(null))))
        assertTrue(AgentHost.answerless(listOf(Entry.Asked("go on"), Entry.Notice("service unavailable"), Entry.Finished(null))))
        assertFalse(AgentHost.answerless(listOf(Entry.Asked("go on"), Entry.Said("ok"), Entry.Finished(null))))
        assertFalse(AgentHost.answerless(listOf(Entry.Asked("go on"), Entry.Finished("boom"))))
        assertFalse(AgentHost.answerless(listOf(Entry.Asked("go on"), Entry.Finished(null, stopped = true))))
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

    @Test fun anEditBecomesALineDiffWithItsNearbyLines() {
        assertEquals(" a\n b\n-c\n+C\n+D\n e\n f", lineDiff("x\na\nb\nc\ne\nf\ny", "x\na\nb\nC\nD\ne\nf\ny"))
        assertEquals("+only", lineDiff("", "only"))
        assertEquals("-gone", lineDiff("gone", ""))
        assertEquals("+one\n+two", lineDiff("", "one\ntwo\n"))
    }

    @Test fun claudeRateLimitsAndContextWindowAreRead() {
        val event = ClaudeCode.parse("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","unifiedWindows":{"five_hour":{"utilization":0.07,"resetsAt":1794435600},"seven_day":{"utilization":0.85,"resetsAt":1794592800}}},"session_id":"s"}""")!!
        assertEquals(listOf(AgentHost.Limit(300, 7, 1794435600), AgentHost.Limit(10_080, 85, 1794592800)), event.limits)
        val result = ClaudeCode.parse("""{"type":"result","subtype":"success","is_error":false,"result":"done","usage":{},"modelUsage":{"claude-haiku":{"costUSD":0.01,"contextWindow":200000},"claude-sonnet":{"costUSD":0.7,"contextWindow":1000000}}}""")!!
        assertEquals(1_000_000L, result.window)
    }

    @Test fun limitsRoundTripThroughPrefs() {
        val limits = listOf(AgentHost.Limit(300, 7, 1794435600), AgentHost.Limit(10_080, 85, 1794592800))
        assertEquals(limits, AgentHost.decodeLimits(AgentHost.encodeLimits(limits)))
        assertEquals(emptyList<AgentHost.Limit>(), AgentHost.decodeLimits("not json"))
    }
}
