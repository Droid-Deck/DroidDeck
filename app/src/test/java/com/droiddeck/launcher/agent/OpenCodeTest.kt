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

    private fun json(line: String) = JSONObject(line)
    private fun update(u: String) = """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"$session","update":$u}}"""

    /** Drives a new process through initialize and session/new to the prompt, returning it and the prompt's id. */
    private fun opened(options: AgentHarness.Options = AgentHarness.Options()): Pair<OpenCode.Protocol, Int> {
        val p = OpenCode.Protocol()
        var line = p.input("hi", null, options, started = true).single()
        var result = "{}"
        while (true) {
            val request = json(line)
            if (request.getString("method") == "session/prompt") return p to request.getInt("id")
            if (request.getString("method") == "session/new") result = """{"sessionId":"$session"}"""
            line = p.parse("""{"jsonrpc":"2.0","id":${request.getInt("id")},"result":$result}""")!!.reply.single()
        }
    }

    @Test fun turnsServeAcpUnderTheChosenRules() {
        val command = OpenCode.turn("hi", null, AgentHarness.Options()).dropWhile { it != "/usr/bin/env" }
        assertEquals(listOf("/usr/bin/env", OpenCode.binary, "acp"), listOf(command[0]) + command.drop(2))
        val ask = json(command[1].removePrefix("OPENCODE_PERMISSION="))
        assertEquals("ask", ask.getString("edit"))
        assertEquals("ask", ask.getString("bash"))
        assertFalse(ask.has("*"))
        assertEquals("allow", OpenCode.rules(AgentHarness.EDITS).getString("edit"))
        assertEquals("ask", OpenCode.rules(AgentHarness.EDITS).getString("bash"))
        assertEquals("""{"*":"allow"}""", OpenCode.rules(AgentHarness.FULL).toString())
    }

    @Test fun aNewProcessOpensItsSessionAndSetsModelModeAndEffortBeforeThePrompt() {
        val p = OpenCode.Protocol()
        val init = json(p.input("hi", null, AgentHarness.Options(model = "opencode/big-pickle", effort = "high", brief = "brief", workdir = "/root/DroidDeck"), started = true).single())
        assertEquals("initialize", init.getString("method"))
        val new = json(p.parse("""{"jsonrpc":"2.0","id":${init.getInt("id")},"result":{"protocolVersion":1}}""")!!.reply.single())
        assertEquals("session/new", new.getString("method"))
        assertEquals("/root/DroidDeck", new.getJSONObject("params").getString("cwd"))
        val created = p.parse("""{"jsonrpc":"2.0","id":${new.getInt("id")},"result":{"sessionId":"$session","configOptions":[]}}""")!!
        assertEquals(session, created.conversation)
        val sent = mutableListOf<JSONObject>()
        var line = created.reply.single()
        while (true) {
            val request = json(line)
            sent += request
            if (request.getString("method") == "session/prompt") break
            // A refused effort, one the model does not have, does not stop the turn.
            val refused = request.getJSONObject("params").getString("configId") == "effort"
            val answer = if (refused) """"error":{"code":-32602,"message":"no"}""" else """"result":{}"""
            line = p.parse("""{"jsonrpc":"2.0","id":${request.getInt("id")},$answer}""")!!.reply.single()
        }
        assertEquals(listOf("model" to "opencode/big-pickle", "effort" to "high", "mode" to "build"),
            sent.dropLast(1).map { it.getJSONObject("params").let { o -> o.getString("configId") to o.getString("value") } })
        assertEquals("brief\n\nhi", sent.last().getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text"))
        val again = json(p.input("two", session, AgentHarness.Options(brief = "brief"), started = false).single())
        assertEquals("session/prompt", again.getString("method"))
        assertEquals("two", again.getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text"))
    }

    @Test fun aResumedSessionPlansWhenAsked() {
        val p = OpenCode.Protocol()
        val init = json(p.input("hi", "ses_9", AgentHarness.Options(plan = true), started = true).single())
        val resume = json(p.parse("""{"jsonrpc":"2.0","id":${init.getInt("id")},"result":{}}""")!!.reply.single())
        assertEquals("session/resume", resume.getString("method"))
        assertEquals("ses_9", resume.getJSONObject("params").getString("sessionId"))
        val resumed = p.parse("""{"jsonrpc":"2.0","id":${resume.getInt("id")},"result":{"configOptions":[]}}""")!!
        assertEquals("ses_9", resumed.conversation)
        val mode = json(resumed.reply.single())
        assertEquals("plan", mode.getJSONObject("params").getString("value"))
        assertEquals(listOf(Entry.Finished("Invalid mode")), p.parse("""{"jsonrpc":"2.0","id":${mode.getInt("id")},"error":{"code":-32602,"message":"Invalid mode"}}""")!!.entries)
        // The next message on the same process asks for the mode again rather than prompting in whatever mode the session has.
        val retry = json(p.input("again", "ses_9", AgentHarness.Options(plan = true), started = false).single())
        assertEquals("session/set_config_option", retry.getString("method"))
        assertEquals("plan", retry.getJSONObject("params").getString("value"))
        val prompt = json(p.parse("""{"jsonrpc":"2.0","id":${retry.getInt("id")},"result":{}}""")!!.reply.single())
        assertEquals("session/prompt", prompt.getString("method"))
        p.parse("""{"jsonrpc":"2.0","id":${prompt.getInt("id")},"result":{"stopReason":"end_turn"}}""")
        assertEquals("session/prompt", json(p.input("third", "ses_9", AgentHarness.Options(plan = true), started = false).single()).getString("method"))
        val gone = OpenCode.Protocol()
        val id = json(gone.input("hi", "ses_x", AgentHarness.Options(), started = true).single()).getInt("id")
        val resumeId = json(gone.parse("""{"jsonrpc":"2.0","id":$id,"result":{}}""")!!.reply.single()).getInt("id")
        assertEquals(listOf(Entry.Finished("Session not found")), gone.parse("""{"jsonrpc":"2.0","id":$resumeId,"error":{"code":-32603,"message":"Internal error","data":{"message":"Session not found"}}}""")!!.entries)
    }

    @Test fun chunksAndToolCallsBecomeEntries() {
        val (p, prompt) = opened()
        assertEquals("We need", p.parse(update("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"We need"}}"""))!!.thinking)
        val pending = p.parse(update("""{"sessionUpdate":"tool_call","toolCallId":"c1","title":"bash","kind":"execute","status":"pending","locations":[],"rawInput":{"cwd":"/work"}}"""))!!
        assertEquals(listOf(Entry.Thought("We need"), Entry.Used("c1", "Bash", "", "")), pending.entries)
        assertEquals(listOf(Entry.Used("c1", "Bash", "touch /work/probe.txt", "touch /work/probe.txt")), p.parse(update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"in_progress","kind":"execute","title":"touch /work/probe.txt","rawInput":{"command":"touch /work/probe.txt","cwd":"/work"}}"""))!!.entries)
        assertEquals(listOf(Entry.Used("c1", "Bash", "touch /work/probe.txt", "touch /work/probe.txt"), Entry.Result("c1", "(no output)", false)), p.parse(update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","title":"touch /work/probe.txt","content":[{"type":"content","content":{"type":"text","text":"(no output)"}}],"rawOutput":{"output":"(no output)","metadata":{"exit":0}}}"""))!!.entries)
        p.parse(update("""{"sessionUpdate":"tool_call","toolCallId":"c2","title":"write","kind":"edit","status":"pending","rawInput":{}}"""))
        assertEquals(Entry.Used("c2", "Write", "/work/note.txt", "/work/note.txt\n+hi"), p.parse(update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"c2","status":"in_progress","kind":"edit","title":"write","rawInput":{"content":"hi","filePath":"/work/note.txt"}}"""))!!.entries.single())
        assertEquals(Entry.Result("c2", "The user rejected permission to use this specific tool call.", true), p.parse(update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"c2","status":"failed","rawOutput":{"error":"The user rejected permission to use this specific tool call."}}"""))!!.entries[1])
        assertEquals("done", p.parse(update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"done"}}"""))!!.text)
        val usage = p.parse(update("""{"sessionUpdate":"usage_update","used":7760,"size":200000,"cost":{"amount":0.25,"currency":"USD"}}"""))!!
        assertEquals(7760L, usage.context)
        assertEquals(200_000L, usage.window)
        val end = p.parse("""{"jsonrpc":"2.0","id":$prompt,"result":{"stopReason":"end_turn","usage":{"inputTokens":592,"outputTokens":3,"totalTokens":7763,"cachedReadTokens":7168}}}""")!!
        assertEquals(listOf(Entry.Said("done"), Entry.Finished(null)), end.entries)
        assertEquals(0.25, end.usage!!.costUsd, 0.0)
        assertEquals(592L + 7168, end.usage!!.inputTokens)
        assertTrue(OpenCode.sessionCost)
    }

    @Test fun permissionRequestsWaitForTheUser() {
        val (p, prompt) = opened()
        p.parse(update("""{"sessionUpdate":"tool_call","toolCallId":"c1","title":"bash","kind":"execute","status":"pending","rawInput":{}}"""))
        val bash = p.parse("""{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"$session","toolCall":{"toolCallId":"c1","title":"touch /w/x","kind":"execute","status":"pending","rawInput":{"command":"touch /w/x"}},"options":[{"optionId":"once","kind":"allow_once","name":"Allow once"},{"optionId":"reject","kind":"reject_once","name":"Reject"}]}}""")!!.approval!!
        assertEquals(AgentHost.Approval("0", "Bash", "touch /w/x", "touch /w/x", """{"id":0}"""), bash)
        val outside = p.parse("""{"jsonrpc":"2.0","id":5,"method":"session/request_permission","params":{"sessionId":"$session","toolCall":{"toolCallId":"c1","title":"touch /root/x","kind":"other","rawInput":{"command":"touch /root/x","parentDir":"/root"}}}}""")!!.approval!!
        assertEquals("External directory", outside.tool)
        assertEquals("/root", outside.detail)
        val allow = json(p.answer(bash, allow = true))
        assertEquals(0, allow.getInt("id"))
        assertEquals("once", allow.getJSONObject("result").getJSONObject("outcome").getString("optionId"))
        assertEquals("reject", json(p.answer(bash, allow = false)).getJSONObject("result").getJSONObject("outcome").getString("optionId"))
        val edit = p.parse("""{"jsonrpc":"2.0","id":1,"method":"session/request_permission","params":{"sessionId":"$session","toolCall":{"toolCallId":"c2","title":"/w/a.txt","kind":"edit","rawInput":{"filepath":"/w/a.txt","diff":"Index: /w/a.txt\n===\n--- /w/a.txt\n+++ /w/a.txt\n@@ -1,1 +1,1 @@\n-old\n+new\n"}}}}""")!!.approval!!
        assertEquals("Edit", edit.tool)
        assertEquals("/w/a.txt\n-old\n+new", edit.input)
        val write = json(p.parse("""{"jsonrpc":"2.0","id":2,"method":"fs/write_text_file","params":{"sessionId":"$session","path":"/w/a.txt","content":"new"}}""")!!.reply.single())
        assertEquals(2, write.getInt("id"))
        assertTrue(write.has("error"))
        assertEquals(listOf(Entry.Finished("Invalid credential")), p.parse(
            """{"jsonrpc":"2.0","id":$prompt,"error":{"code":-32603,"message":"Internal error","data":{"message":"Invalid credential"}}}""")!!.entries)
        assertNull(p.parse("not json"))
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

    @Test fun modelsComeFromTheCachedListing() {
        val root = Files.createTempDirectory("rootfs").toFile()
        assertEquals(emptyList<AgentHarness.Model>(), OpenCode.models(root))
        File(root, "root/.cache/droiddeck/opencode-models").apply { parentFile!!.mkdirs() }
            .writeText("opencode/big-pickle\n{\n  \"providerID\": \"opencode\",\n  \"name\": \"Big Pickle\",\n  \"limit\": {\"context\": 200000}\n}\n")
        assertEquals(listOf(AgentHarness.Model("opencode/big-pickle", "Big Pickle")), OpenCode.models(root))
    }

    private fun provider(id: String) = OpenCode.providers.first { it.id == id }

    @Test fun aSavedKeyKeepsOtherProvidersAndFreeIsItsOwnMark() {
        val root = Files.createTempDirectory("rootfs").toFile()
        assertEquals(emptySet<String>(), OpenCode.keys(root))
        val auth = File(root, "root/.local/share/opencode/auth.json").apply { parentFile!!.mkdirs() }
        auth.writeText("""{"openai-codex":{"type":"oauth","refresh":"r"}}""")
        assertEquals(emptySet<String>(), OpenCode.keys(root))
        OpenCode.saveKey(root, provider("opencode"), "  sk-zen \n")
        OpenCode.saveKey(root, provider("ollama-cloud"), "ol-key")
        val saved = JSONObject(auth.readText())
        assertEquals("r", saved.getJSONObject("openai-codex").getString("refresh"))
        assertEquals("sk-zen", saved.getJSONObject("opencode").getString("key"))
        assertEquals("api", saved.getJSONObject("ollama-cloud").getString("type"))
        assertEquals("ol-key", saved.getJSONObject("ollama-cloud").getString("key"))
        assertEquals(setOf("opencode", "ollama-cloud"), OpenCode.keys(root))
        OpenCode.removeKey(root, provider("opencode"))
        assertEquals(setOf("ollama-cloud"), OpenCode.keys(root))
        assertTrue(JSONObject(auth.readText()).has("openai-codex"))
        OpenCode.useFree(root)
        assertTrue(File(root, "root/.config/opencode/.droiddeck-free").exists())
    }

    @Test fun anUnreadableAuthFileIsNotReplaced() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val auth = File(root, "root/.local/share/opencode/auth.json").apply { parentFile!!.mkdirs() }
        auth.writeText("{not json")
        assertTrue(runCatching { OpenCode.saveKey(root, provider("opencode"), "sk-zen") }.isFailure)
        assertEquals("{not json", auth.readText())
        assertEquals(emptySet<String>(), OpenCode.keys(root))
        val config = File(root, "root/.config/opencode/opencode.json").apply { parentFile!!.mkdirs() }
        config.writeText("""{"provider":{"droiddeck":{"options":{"baseURL":"http://a/v1"}}}}""")
        assertTrue(runCatching { OpenCode.saveKey(root, provider("droiddeck"), "k", "b:1") { _, _ -> """{"data":[{"id":"m"}]}""" } }.isFailure)
        assertEquals("""{"provider":{"droiddeck":{"options":{"baseURL":"http://a/v1"}}}}""", config.readText())
        assertEquals(setOf("droiddeck"), OpenCode.keys(root))
    }

    @Test fun aCustomServerBecomesAProviderWithTheModelsItLists() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val config = File(root, "root/.config/opencode/opencode.json").apply { parentFile!!.mkdirs() }
        config.writeText("""{"theme":"tokyonight","provider":{"mine":{"npm":"x"}}}""")
        val asked = mutableListOf<Pair<String, String?>>()
        OpenCode.saveKey(root, provider("droiddeck"), "", "192.168.1.20:11434/") { url, key ->
            asked += url to key
            """{"object":"list","data":[{"id":"qwen3:8b"},{"id":"gpt-oss:20b"},{"id":"qwen3:8b"}]}"""
        }
        assertEquals(listOf("http://192.168.1.20:11434/v1/models" to null), asked)
        val saved = JSONObject(config.readText())
        assertEquals("tokyonight", saved.getString("theme"))
        assertTrue(saved.getJSONObject("provider").has("mine"))
        val custom = saved.getJSONObject("provider").getJSONObject("droiddeck")
        assertEquals("@ai-sdk/openai-compatible", custom.getString("npm"))
        assertEquals("192.168.1.20", custom.getString("name"))
        assertEquals("http://192.168.1.20:11434/v1", custom.getJSONObject("options").getString("baseURL"))
        assertEquals(setOf("qwen3:8b", "gpt-oss:20b"), custom.getJSONObject("models").keys().asSequence().toSet())
        assertEquals(setOf("droiddeck"), OpenCode.keys(root))
        assertFalse(JSONObject(File(root, "root/.local/share/opencode/auth.json").readText()).has("droiddeck"))

        OpenCode.saveKey(root, provider("droiddeck"), "sk-lan", "https://llm.example/api/v1") { url, key -> asked += url to key; """{"data":[{"id":"m"}]}""" }
        assertEquals("https://llm.example/api/v1/models" to "sk-lan", asked.last())
        assertEquals("sk-lan", JSONObject(File(root, "root/.local/share/opencode/auth.json").readText()).getJSONObject("droiddeck").getString("key"))

        OpenCode.removeKey(root, provider("droiddeck"))
        assertEquals(emptySet<String>(), OpenCode.keys(root))
        assertTrue(JSONObject(config.readText()).getJSONObject("provider").has("mine"))
    }

    @Test fun modelIdsSkipWhatTheUserlandPrintsFirst() {
        assertEquals(listOf("m"), OpenCode.modelIds("proot warning: can't sanitize binding \"/proc/self/fd/1\"\n{\"data\":[{\"id\":\"m\"}]}\n"))
        assertEquals(emptyList<String>(), OpenCode.modelIds("curl: (7) Failed to connect"))
    }

    @Test fun aFailedKeyWritePutsTheServerConfigBack() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val config = File(root, "root/.config/opencode/opencode.json").apply { parentFile!!.mkdirs() }
        val previous = """{"provider":{"droiddeck":{"options":{"baseURL":"http://a/v1"}}}}"""
        config.writeText(previous)
        // A folder where the key file's temporary copy goes makes that write fail after the config is written.
        File(root, "root/.local/share/opencode/.auth.json.tmp").mkdirs()
        assertTrue(runCatching { OpenCode.saveKey(root, provider("droiddeck"), "k", "b:1") { _, _ -> """{"data":[{"id":"m"}]}""" } }.isFailure)
        assertEquals(previous, config.readText())
        assertFalse(File(root, "root/.config/opencode/.opencode.json.tmp").exists())

        config.delete()
        File(root, "root/.local/share/opencode/.auth.json.tmp").mkdirs()
        assertTrue(runCatching { OpenCode.saveKey(root, provider("droiddeck"), "k", "b:1") { _, _ -> """{"data":[{"id":"m"}]}""" } }.isFailure)
        assertFalse(config.exists())
    }

    @Test fun aServerWithoutModelsIsNotSaved() {
        val root = Files.createTempDirectory("rootfs").toFile()
        val error = runCatching { OpenCode.saveKey(root, provider("droiddeck"), "", "localhost:1234") { _, _ -> """{"data":[]}""" } }.exceptionOrNull()
        assertEquals("No models at http://localhost:1234/v1", error?.message)
        assertFalse(File(root, "root/.config/opencode/opencode.json").exists())
    }

    @Test fun providerModelsAreLabelledWithTheProvider() {
        val (models, _) = OpenCode.listModels("""
            ollama-cloud/gpt-oss:120b
            {
              "providerID": "ollama-cloud",
              "name": "gpt-oss:120b"
            }
            droiddeck/qwen3:8b
            {
              "providerID": "droiddeck",
              "name": "qwen3:8b"
            }
        """.trimIndent())
        assertEquals(listOf(AgentHarness.Model("ollama-cloud/gpt-oss:120b", "gpt-oss:120b · Ollama Cloud"), AgentHarness.Model("droiddeck/qwen3:8b", "qwen3:8b")), models)
    }

    @Test fun signInIsAKeyTheFreeMarkOrACustomServer() {
        val probe = OpenCode.signedIn.last()
        assertTrue(probe.contains("/root/.local/share/opencode/auth.json"))
        assertTrue(probe.contains("[ -e /root/.config/opencode/.droiddeck-free ]"))
        assertTrue(probe.contains("grep -q '\"droiddeck\"' /root/.config/opencode/opencode.json"))
        assertTrue(probe.contains("models --verbose > /root/.cache/droiddeck/opencode-models.$$"))
        assertEquals(listOf("/root/.local/bin/android-open", "https://opencode.ai/auth"), OpenCode.signIn)
    }
}
