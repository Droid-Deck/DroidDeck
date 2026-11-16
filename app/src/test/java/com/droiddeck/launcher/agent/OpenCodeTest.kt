package com.droiddeck.launcher.agent

import com.droiddeck.launcher.agent.AgentHost.Entry
import org.json.JSONObject
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
class OpenCodeTest {
    private val session = "ses_1"

    private fun event(type: String, part: String) = """{"type":"$type","timestamp":1,"sessionID":"$session","part":$part}"""

    @Test fun turnsResumeByIdAndCarryModelPlanAndVariant() {
        assertEquals(listOf(OpenCode.binary, "run", "--format", "json", "--auto", "--thinking", "--", "brief\n\nhi"),
            OpenCode.turn("hi", null, AgentHarness.Options(brief = "brief")))
        assertEquals(listOf(OpenCode.binary, "run", "--format", "json", "--auto", "--thinking", "-s", "ses_1", "--agent", "plan",
            "-m", "opencode/big-pickle", "--variant", "high", "--", "-hi"),
            OpenCode.turn("-hi", "ses_1", AgentHarness.Options(model = "opencode/big-pickle", plan = true, brief = "brief", effort = "high")))
    }

    @Test fun textReasoningAndStepsBecomeEntries() {
        OpenCode.turn("hi", null, AgentHarness.Options())
        assertEquals(session, OpenCode.parse(event("step_start", """{"type":"step-start"}"""))!!.conversation)
        assertEquals(listOf(Entry.Said("x86_64")), OpenCode.parse(event("text", """{"type":"text","text":" x86_64\n"}"""))!!.entries)
        assertEquals(listOf(Entry.Thought("why")), OpenCode.parse(event("reasoning", """{"type":"reasoning","text":"why"}"""))!!.entries)
        val step = OpenCode.parse(event("step_finish", """{"reason":"tool-calls","tokens":{"total":7894,"input":6013,"output":89,"reasoning":0,"cache":{"write":0,"read":1792}},"cost":0.01}"""))!!
        assertEquals(emptyList<Entry>(), step.entries)
        assertEquals(7894L, step.context)
        val end = OpenCode.parse(event("step_finish", """{"reason":"stop","tokens":{"total":7927,"input":241,"output":6,"reasoning":2,"cache":{"write":0,"read":7680}},"cost":0.02}"""))!!
        assertEquals(listOf(Entry.Finished(null)), end.entries)
        assertEquals(AgentHost.Usage(0, 0.03, 6013L + 1792 + 241 + 7680, 89L + 6 + 2), end.usage!!.copy(costUsd = Math.round(end.usage!!.costUsd * 100) / 100.0))
    }

    @Test fun eachTurnCountsOnlyItsOwnSteps() {
        OpenCode.turn("one", null, AgentHarness.Options())
        OpenCode.parse(event("step_finish", """{"reason":"stop","tokens":{"input":100,"output":10,"cache":{}},"cost":1}"""))
        OpenCode.turn("two", session, AgentHarness.Options())
        val end = OpenCode.parse(event("step_finish", """{"reason":"stop","tokens":{"input":5,"output":1,"cache":{}},"cost":0}"""))!!
        assertEquals(AgentHost.Usage(0, 0.0, 5, 1), end.usage)
    }

    @Test fun toolsBecomeCallsWithTheirResults() {
        val bash = OpenCode.parse(event("tool_use", """{"type":"tool","tool":"bash","callID":"c1","state":{"status":"completed","input":{"command":"uname -m"},"output":"aarch64\n","metadata":{"exit":0}}}"""))!!
        assertEquals(listOf(Entry.Used("c1", "Bash", "uname -m", "uname -m"), Entry.Result("c1", "aarch64\n", false)), bash.entries)
        val failed = OpenCode.parse(event("tool_use", """{"type":"tool","tool":"bash","callID":"c2","state":{"status":"completed","input":{"command":"false"},"output":"(no output)","metadata":{"exit":1}}}"""))!!
        assertTrue((failed.entries[1] as Entry.Result).failed)
        val edit = OpenCode.parse(event("tool_use", """{"type":"tool","tool":"edit","callID":"c3","state":{"status":"completed","input":{"filePath":"/w/a.txt","oldString":"hi","newString":"bye"},"output":"Edit applied successfully."}}"""))!!
        assertEquals(Entry.Used("c3", "Edit", "/w/a.txt", "/w/a.txt\n-hi\n+bye"), edit.entries[0])
        val write = OpenCode.parse(event("tool_use", """{"type":"tool","tool":"write","callID":"c4","state":{"status":"completed","input":{"filePath":"/w/b.txt","content":"one\n"},"output":"Wrote file successfully."}}"""))!!
        assertEquals("/w/b.txt\n+one", (write.entries[0] as Entry.Used).input)
        val todo = OpenCode.parse(event("tool_use", """{"type":"tool","tool":"todowrite","callID":"c5","state":{"status":"completed","input":{"todos":[{"content":"A","status":"completed"},{"content":"B","status":"in_progress"},{"content":"C","status":"pending"}]},"output":"[]"}}"""))!!
        assertEquals("[x] A\n[~] B\n[ ] C", (todo.entries[0] as Entry.Used).input)
        val read = OpenCode.parse(event("tool_use", """{"type":"tool","tool":"read","callID":"c6","state":{"status":"error","input":{"filePath":"/nope"},"error":"File not found"}}"""))!!
        assertEquals("Read", (read.entries[0] as Entry.Used).tool)
        assertEquals("/nope", (read.entries[0] as Entry.Used).detail)
        assertEquals(Entry.Result("c6", "File not found", true), read.entries[1])
        val running = OpenCode.parse(event("tool_use", """{"type":"tool","tool":"bash","callID":"c7","state":{"status":"running","input":{"command":"sleep 9"}}}"""))!!
        assertEquals(listOf(Entry.Used("c7", "Bash", "sleep 9", "sleep 9")), running.entries)
    }

    @Test fun errorsEndTheTurn() {
        assertEquals(listOf(Entry.Finished("Invalid credential")), OpenCode.parse(
            """{"type":"error","sessionID":"ses_1","error":{"name":"APIError","data":{"message":"Invalid credential","statusCode":401}}}""")!!.entries)
        assertEquals(listOf(Entry.Finished("UnknownError")), OpenCode.parse("""{"type":"error","error":{"name":"UnknownError"}}""")!!.entries)
        assertNull(OpenCode.parse("not json"))
    }

    @Test fun verboseModelListingGivesLabelsAndWindows() {
        val output = """
            opencode/big-pickle
            {
              "id": "big-pickle",
              "providerID": "opencode",
              "name": "Big Pickle",
              "status": "active",
              "limit": {
                "context": 200000,
                "output": 128000
              }
            }
            opencode/old-free
            {
              "providerID": "opencode",
              "name": "Old",
              "status": "deprecated"
            }
            opencode-go/kimi-k3
            {
              "providerID": "opencode-go",
              "name": "Kimi K3",
              "limit": {
                "context": 262144
              }
            }
        """.trimIndent()
        val (models, windows) = OpenCode.listModels(output)
        assertEquals(listOf(AgentHarness.Model("opencode/big-pickle", "Big Pickle"), AgentHarness.Model("opencode-go/kimi-k3", "Kimi K3 · opencode-go")), models)
        assertEquals(mapOf("opencode/big-pickle" to 200000L, "opencode-go/kimi-k3" to 262144L), windows)
    }

    @Test fun modelsAndWindowComeFromTheCachedListing() {
        val root = Files.createTempDirectory("rootfs").toFile()
        assertEquals(emptyList<AgentHarness.Model>(), OpenCode.models(root))
        File(root, "root/.cache/droiddeck/opencode-models").apply { parentFile!!.mkdirs() }
            .writeText("opencode/big-pickle\n{\n  \"providerID\": \"opencode\",\n  \"name\": \"Big Pickle\",\n  \"limit\": {\"context\": 200000}\n}\n")
        assertEquals(listOf(AgentHarness.Model("opencode/big-pickle", "Big Pickle")), OpenCode.models(root))
        OpenCode.turn("hi", null, AgentHarness.Options(model = "opencode/big-pickle"))
        assertEquals(200000L, OpenCode.usage(root, session)!!.window)
        OpenCode.turn("hi", null, AgentHarness.Options())
        assertNull(OpenCode.usage(root, session))
    }

    @Test fun aSavedKeyKeepsOtherProvidersAndFreeIsItsOwnMark() {
        val root = Files.createTempDirectory("rootfs").toFile()
        assertFalse(OpenCode.hasKey(root))
        val auth = File(root, "root/.local/share/opencode/auth.json").apply { parentFile!!.mkdirs() }
        auth.writeText("""{"openai":{"type":"oauth","refresh":"r"}}""")
        assertFalse(OpenCode.hasKey(root))
        OpenCode.saveKey(root, "  sk-zen \n")
        val saved = JSONObject(auth.readText())
        assertEquals("r", saved.getJSONObject("openai").getString("refresh"))
        assertEquals("sk-zen", saved.getJSONObject("opencode").getString("key"))
        assertEquals("api", saved.getJSONObject("opencode").getString("type"))
        assertTrue(OpenCode.hasKey(root))
        OpenCode.useFree(root)
        assertTrue(File(root, "root/.config/opencode/.droiddeck-free").exists())
    }

    @Test fun anUnreadableAuthFileIsNotReplaced() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val auth = File(root, "root/.local/share/opencode/auth.json").apply { parentFile!!.mkdirs() }
        auth.writeText("{not json")
        assertTrue(runCatching { OpenCode.saveKey(root, "sk-zen") }.isFailure)
        assertEquals("{not json", auth.readText())
        assertFalse(OpenCode.hasKey(root))
    }

    @Test fun signInIsTheKeyOrTheFreeMark() {
        val probe = OpenCode.signedIn.last()
        assertTrue(probe.contains("/root/.local/share/opencode/auth.json"))
        assertTrue(probe.contains("[ -e /root/.config/opencode/.droiddeck-free ]"))
        assertTrue(probe.contains("models --verbose > /root/.cache/droiddeck/opencode-models.$$"))
        assertEquals(listOf("/root/.local/bin/android-open", "https://opencode.ai/auth"), OpenCode.signIn)
    }
}
