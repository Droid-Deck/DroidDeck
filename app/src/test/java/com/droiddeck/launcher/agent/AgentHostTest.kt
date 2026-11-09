package com.droiddeck.launcher.agent

import com.droiddeck.launcher.agent.AgentHost.Entry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AgentHostTest {
    @Test fun theInitLineCarriesOnlyTheConversation() {
        val event = AgentHost.parse("""{"type":"system","subtype":"init","session_id":"abc","tools":[]}""")!!
        assertEquals("abc", event.session)
        assertEquals(emptyList<Entry>(), event.entries)
    }

    @Test fun assistantTextAndToolUseBecomeEntries() {
        val event = AgentHost.parse(
            """{"type":"assistant","session_id":"abc","message":{"content":[
                {"type":"text","text":"  Looking at the log.  "},
                {"type":"tool_use","name":"Bash","input":{"command":"droiddeckctl state\nmore"}},
                {"type":"thinking","thinking":"hidden"}]}}""",
        )!!
        assertEquals(listOf(Entry.Said("Looking at the log."), Entry.Used("Bash", "droiddeckctl state")), event.entries)
    }

    @Test fun aResultEndsTheTurn() {
        assertEquals(listOf(Entry.Finished(null)), AgentHost.parse("""{"type":"result","subtype":"success","is_error":false,"result":"done"}""")!!.entries)
        assertEquals(listOf(Entry.Finished("Invalid API key")),
            AgentHost.parse("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key"}""")!!.entries)
    }

    @Test fun linesWithNothingToShowAreSkipped() {
        assertNull(AgentHost.parse("not json"))
        assertNull(AgentHost.parse("""{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}"""))
    }

    @Test fun aMessageIsSentAsOneStreamJsonUserLine() {
        val o = JSONObject(AgentHost.userMessage("why did it crash?"))
        assertEquals("user", o.getString("type"))
        assertEquals("why did it crash?", o.getJSONObject("message").getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun theTranscriptSurvivesARestart() {
        val entries = listOf(Entry.Asked("hi"), Entry.Said("hello"), Entry.Used("Read", "/root/x"), Entry.Finished(null), Entry.Finished("stopped"))
        val file = Files.createTempFile("transcript", ".jsonl").toFile()
        file.writeText(entries.joinToString("\n") { AgentHost.encode(it) } + "\nbroken line\n")
        assertEquals(entries, AgentHost.readTranscript(file))
        assertEquals(emptyList<Entry>(), AgentHost.readTranscript(File(file.path + ".missing")))
    }
}
