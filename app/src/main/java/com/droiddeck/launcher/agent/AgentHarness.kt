package com.droiddeck.launcher.agent

import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject

/** One coding agent CLI in the Linux userland, run one process per turn. */
interface AgentHarness {
    val id: String
    val name: String
    /** Absolute guest path of the CLI. */
    val binary: String
    /** A guest shell script that installs the CLI. */
    val install: String
    /** A guest command whose exit status is 0 when the CLI is signed in. */
    val signedIn: List<String>
    /** A guest command that signs in through $BROWSER. */
    val signIn: List<String>
    /** The guest command for one turn; [conversation] is null for the first one. */
    fun turn(message: String, conversation: String?): List<String>
    /** One line of the CLI's output, or null when it carries nothing to show. */
    fun parse(line: String): AgentEvent?

    companion object {
        val all: List<AgentHarness> = listOf(ClaudeCode, Codex)
        fun byId(id: String?): AgentHarness = all.firstOrNull { it.id == id } ?: ClaudeCode
    }
}

data class AgentEvent(val conversation: String?, val entries: List<AgentHost.Entry>)

/** Claude Code: `claude -p` with stream-json output, resumed by session id. */
object ClaudeCode : AgentHarness {
    override val id = "claude"
    override val name = "Claude Code"
    override val binary = "/root/.local/bin/claude"
    override val install = "curl -fsSL https://claude.ai/install.sh | bash"
    override val signedIn = listOf("sh", "-c", "$binary auth status | grep -q '\"loggedIn\": true'")
    override val signIn = listOf(binary, "auth", "login", "--claudeai")

    // DroidDeck runs the agent unattended on the user's own device; permission prompts have no one to answer them.
    override fun turn(message: String, conversation: String?) = buildList {
        addAll(listOf(binary, "-p", message, "--output-format", "stream-json", "--verbose", "--permission-mode", "bypassPermissions"))
        if (conversation != null) addAll(listOf("--resume", conversation))
    }

    override fun parse(line: String): AgentEvent? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val conversation = o.optString("session_id").takeIf { it.isNotEmpty() }
        val entries = when (o.optString("type")) {
            "assistant" -> {
                val content = o.optJSONObject("message")?.optJSONArray("content") ?: JSONArray()
                (0 until content.length()).mapNotNull { i ->
                    val block = content.optJSONObject(i) ?: return@mapNotNull null
                    when (block.optString("type")) {
                        "text" -> block.optString("text").trim().takeIf { it.isNotEmpty() }?.let { AgentHost.Entry.Said(it) }
                        "tool_use" -> AgentHost.Entry.Used(block.optString("name"), detail(block.optJSONObject("input")))
                        else -> null
                    }
                }
            }
            "result" -> listOf(AgentHost.Entry.Finished(
                if (o.optBoolean("is_error")) o.optString("result").ifEmpty { o.optString("subtype") } else null,
            ))
            else -> emptyList()
        }
        return if (conversation == null && entries.isEmpty()) null else AgentEvent(conversation, entries)
    }

    private fun detail(input: JSONObject?): String {
        input ?: return ""
        for (key in listOf("command", "file_path", "pattern", "url", "description")) {
            input.optString(key).takeIf { it.isNotEmpty() }?.let { return firstLine(it) }
        }
        return ""
    }
}

/** OpenAI Codex: `codex exec --json`, resumed by thread id. */
object Codex : AgentHarness {
    override val id = "codex"
    override val name = "Codex"
    override val binary = "/root/.local/bin/codex"
    override val install = """
        set -e
        url=${'$'}(curl -fsSL https://api.github.com/repos/openai/codex/releases/latest | grep -o 'https://[^"]*codex-aarch64-unknown-linux-musl.tar.gz' | head -1)
        mkdir -p /root/.local/bin
        curl -fsSL "${'$'}url" | tar -xz -C /tmp
        install -m755 /tmp/codex-aarch64-unknown-linux-musl $binary
    """.trimIndent()
    override val signedIn = listOf(binary, "login", "status")
    override val signIn = listOf(binary, "login")

    // Codex's own sandbox needs Landlock and seccomp, which proot does not provide.
    override fun turn(message: String, conversation: String?) = buildList {
        addAll(listOf(binary, "exec"))
        if (conversation != null) addAll(listOf("resume", conversation))
        addAll(listOf("--json", "--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check", message))
    }

    override fun parse(line: String): AgentEvent? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val item = o.optJSONObject("item")
        val entries = when (o.optString("type")) {
            "thread.started" -> return AgentEvent(o.optString("thread_id").takeIf { it.isNotEmpty() }, emptyList())
            "item.started" -> when (item?.optString("type")) {
                "command_execution" -> listOf(AgentHost.Entry.Used("Shell", firstLine(item.optString("command"))))
                else -> emptyList()
            }
            "item.completed" -> when (item?.optString("type")) {
                "agent_message" -> item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { listOf(AgentHost.Entry.Said(it)) }.orEmpty()
                "file_change" -> listOf(AgentHost.Entry.Used("Edit", changedFiles(item)))
                else -> emptyList()
            }
            "turn.completed" -> listOf(AgentHost.Entry.Finished(null))
            "turn.failed" -> listOf(AgentHost.Entry.Finished(o.optJSONObject("error")?.optString("message") ?: "turn failed"))
            "error" -> listOf(AgentHost.Entry.Finished(o.optString("message").ifEmpty { "error" }))
            else -> emptyList()
        }
        return if (entries.isEmpty()) null else AgentEvent(null, entries)
    }

    private fun changedFiles(item: JSONObject): String {
        val changes = item.optJSONArray("changes") ?: return ""
        return (0 until changes.length()).mapNotNull { changes.optJSONObject(it)?.optString("path") }.joinToString(", ").take(160)
    }
}

@VisibleForTesting
internal fun firstLine(text: String) = text.lineSequence().first().take(160)
