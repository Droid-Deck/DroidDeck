package com.droiddeck.launcher.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentToolsTest {
    @Test fun serversAreReadFromTheListOutput() {
        val output = "Checking MCP server health…\n\nclaude.ai Claude Docs: https://api.anthropic.com/v1/pages/mcp - ✔ Connected\n" +
            "files: npx -y @modelcontextprotocol/server-filesystem /root - ✗ Failed to connect\n"
        val servers = AgentTools.parseServers(output)
        assertEquals(listOf("claude.ai Claude Docs", "files"), servers.map { it.name })
        assertEquals("npx -y @modelcontextprotocol/server-filesystem /root", servers[1].target)
        assertTrue(servers[0].account)
    }

    @Test fun aUrlIsAddedOverHttpAndACommandOverStdio() {
        assertEquals(listOf("mcp", "add", "--scope", "user", "--transport", "http", "sentry", "https://mcp.sentry.dev/mcp"),
            AgentTools.addCommand("sentry", " https://mcp.sentry.dev/mcp ").drop(1))
        assertEquals(listOf("mcp", "add", "--scope", "user", "files", "--", "npx", "-y", "server", "/root"),
            AgentTools.addCommand("files", "npx  -y server /root").drop(1))
    }

    @Test fun aSkillsDescriptionComesFromItsFrontMatter() {
        val file = Files.createTempFile("skill", ".md").toFile()
        file.writeText("---\nname: deploy\ndescription: \"Ship the build\"\n---\nBody\n")
        assertEquals("Ship the build", AgentTools.description(file))
        file.writeText("No front matter\ndescription: nope\n")
        assertEquals("", AgentTools.description(file))
        file.delete()
        assertEquals("", AgentTools.description(File(file.path)))
    }
}
