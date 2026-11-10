package com.droiddeck.launcher.agent

import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One coding agent CLI in the Linux userland, run one process per turn. */
interface AgentHarness {
    val id: String
    val name: String
    /** Absolute guest path of the CLI. */
    val binary: String
    /** Guest paths of everything the CLI needs installed, [binary] included. */
    val files: List<String> get() = listOf(binary)
    /** A guest shell script that installs the CLI. */
    val install: String
    /** A guest command whose exit status is 0 when the CLI is signed in. */
    val signedIn: List<String>
    /** A guest command that signs in through $BROWSER. */
    val signIn: List<String>
    /** Whether [Options.plan] means anything to this CLI. */
    val plans: Boolean
    /** Models to offer besides the CLI's default; [rootfs] is where the CLI's own files can be read. */
    fun models(rootfs: File): List<Model>
    /** The guest command for one turn; [conversation] is null for the first one. */
    fun turn(message: String, conversation: String?, options: Options): List<String>
    /** One line of the CLI's output, or null when it carries nothing to show. */
    fun parse(line: String): AgentEvent?

    data class Model(val id: String, val label: String)

    data class Options(val model: String? = null, val plan: Boolean = false, val brief: String? = null)

    companion object {
        val all: List<AgentHarness> = listOf(ClaudeCode, Codex)
        fun byId(id: String?): AgentHarness = all.firstOrNull { it.id == id } ?: ClaudeCode
    }
}

/** What one line of output carries: whole entries, or text still being written. */
data class AgentEvent(
    val conversation: String? = null,
    val entries: List<AgentHost.Entry> = emptyList(),
    val text: String? = null,
    val thinking: String? = null,
    /** Tokens the conversation's context holds after this step. */
    val context: Long? = null,
    val usage: AgentHost.Usage? = null,
)

/** Claude Code: `claude -p` with stream-json output, resumed by session id. */
object ClaudeCode : AgentHarness {
    override val id = "claude"
    override val name = "Claude Code"
    override val binary = "/root/.local/bin/claude"
    override val install = "curl -fsSL https://claude.ai/install.sh | bash"
    override val signedIn = listOf("sh", "-c", "$binary auth status | grep -q '\"loggedIn\": true'")
    override val signIn = listOf(binary, "auth", "login", "--claudeai")
    override val plans = true

    override fun models(rootfs: File) = listOf(
        AgentHarness.Model("opus", "Opus"), AgentHarness.Model("sonnet", "Sonnet"), AgentHarness.Model("haiku", "Haiku"),
    )

    // DroidDeck runs the agent unattended on the user's own device; permission prompts have no one to answer them.
    override fun turn(message: String, conversation: String?, options: AgentHarness.Options) = buildList {
        addAll(listOf(binary, "-p", message, "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
        addAll(listOf("--permission-mode", if (options.plan) "plan" else "bypassPermissions"))
        options.model?.let { addAll(listOf("--model", it)) }
        options.brief?.let { addAll(listOf("--append-system-prompt", it)) }
        if (conversation != null) addAll(listOf("--resume", conversation))
    }

    override fun parse(line: String): AgentEvent? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val conversation = o.optString("session_id").takeIf { it.isNotEmpty() }
        return when (o.optString("type")) {
            "stream_event" -> {
                val delta = o.optJSONObject("event")?.takeIf { it.optString("type") == "content_block_delta" }?.optJSONObject("delta") ?: return null
                when (delta.optString("type")) {
                    "text_delta" -> AgentEvent(conversation, text = delta.optString("text"))
                    "thinking_delta" -> AgentEvent(conversation, thinking = delta.optString("thinking"))
                    else -> null
                }
            }
            "assistant" -> {
                val message = o.optJSONObject("message")
                val content = message?.optJSONArray("content") ?: JSONArray()
                val entries = (0 until content.length()).mapNotNull { i ->
                    val block = content.optJSONObject(i) ?: return@mapNotNull null
                    when (block.optString("type")) {
                        "text" -> block.optString("text").trim().takeIf { it.isNotEmpty() }?.let { AgentHost.Entry.Said(it) }
                        "thinking" -> block.optString("thinking").trim().takeIf { it.isNotEmpty() }?.let { AgentHost.Entry.Thought(it) }
                        "tool_use" -> {
                            val input = block.optJSONObject("input")
                            AgentHost.Entry.Used(block.optString("id"), block.optString("name"), detail(input), fullInput(block.optString("name"), input))
                        }
                        else -> null
                    }
                }
                val usage = message?.optJSONObject("usage")
                val context = usage?.let { it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens") + it.optLong("output_tokens") }
                AgentEvent(conversation, entries, context = context?.takeIf { it > 0 })
            }
            "user" -> {
                val content = o.optJSONObject("message")?.optJSONArray("content") ?: return conversation?.let { AgentEvent(it) }
                val results = (0 until content.length()).mapNotNull { i ->
                    val block = content.optJSONObject(i)?.takeIf { it.optString("type") == "tool_result" } ?: return@mapNotNull null
                    AgentHost.Entry.Result(block.optString("tool_use_id"), resultText(block.opt("content")), block.optBoolean("is_error"))
                }
                AgentEvent(conversation, results)
            }
            // A resumed session first answers background tasks the last run left behind; that result is not the turn's.
            "result" -> if (o.optJSONObject("origin")?.optString("kind") == "task-notification") conversation?.let { AgentEvent(it) } else AgentEvent(
                conversation,
                listOf(AgentHost.Entry.Finished(if (o.optBoolean("is_error")) o.optString("result").ifEmpty { o.optString("subtype") } else null)),
                usage = AgentHost.Usage(
                    o.optLong("duration_ms"), o.optDouble("total_cost_usd", 0.0),
                    o.optJSONObject("usage")?.let { it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens") } ?: 0,
                    o.optJSONObject("usage")?.optLong("output_tokens") ?: 0,
                ),
            )
            else -> conversation?.let { AgentEvent(it) }
        }
    }

    private fun resultText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> (0 until content.length()).mapNotNull { i ->
            val part = content.optJSONObject(i) ?: return@mapNotNull null
            when (part.optString("type")) {
                "text" -> part.optString("text")
                "image" -> "[image]"
                else -> null
            }
        }.joinToString("\n")
        null, JSONObject.NULL -> ""
        else -> content.toString()
    }

    private fun detail(input: JSONObject?): String {
        input ?: return ""
        for (key in listOf("command", "file_path", "pattern", "url", "query", "description", "prompt")) {
            input.optString(key).takeIf { it.isNotEmpty() }?.let { return firstLine(it) }
        }
        return ""
    }

    @VisibleForTesting
    internal fun fullInput(tool: String, input: JSONObject?): String {
        input ?: return ""
        return when {
            tool == "Bash" -> input.optString("command")
            tool == "Edit" -> "${input.optString("file_path")}\n\n- ${input.optString("old_string").replace("\n", "\n- ")}\n\n+ ${input.optString("new_string").replace("\n", "\n+ ")}"
            tool == "Write" -> "${input.optString("file_path")}\n\n${input.optString("content")}"
            tool == "TodoWrite" -> todos(input.optJSONArray("todos"))
            else -> input.toString(2)
        }
    }

    private fun todos(list: JSONArray?) = (0 until (list?.length() ?: 0)).mapNotNull { i ->
        val todo = list?.optJSONObject(i) ?: return@mapNotNull null
        val mark = when (todo.optString("status")) { "completed" -> "[x]"; "in_progress" -> "[~]"; else -> "[ ]" }
        "$mark ${todo.optString("content")}"
    }.joinToString("\n")
}

/** OpenAI Codex: `codex exec --json`, resumed by thread id. */
object Codex : AgentHarness {
    override val id = "codex"
    override val name = "Codex"
    override val binary = "/root/.local/bin/codex"
    // Code mode runs in a helper that each release ships as its own download.
    private const val HOST = "/root/.local/bin/codex-code-mode-host"
    override val files get() = listOf(binary, HOST)
    override val install = """
        set -e
        release=${'$'}(curl -fsSL https://api.github.com/repos/openai/codex/releases/latest)
        mkdir -p /root/.local/bin
        for name in codex codex-code-mode-host; do
          url=${'$'}(printf '%s' "${'$'}release" | grep -o "https://[^\"]*/${'$'}name-aarch64-unknown-linux-musl.tar.gz" | head -1)
          curl -fsSL "${'$'}url" | tar -xz -C /tmp
          install -m755 /tmp/${'$'}name-aarch64-unknown-linux-musl /root/.local/bin/${'$'}name
        done
    """.trimIndent()
    override val signedIn = listOf(binary, "login", "status")
    override val signIn = listOf(binary, "login")
    override val plans = false

    /** Codex keeps the models its account can use in `~/.codex/models_cache.json` once it has run. */
    override fun models(rootfs: File): List<AgentHarness.Model> = runCatching {
        val o = JSONObject(File(rootfs, "root/.codex/models_cache.json").readText())
        val list = o.optJSONArray("models") ?: JSONArray()
        (0 until list.length()).mapNotNull { i ->
            val m = list.optJSONObject(i) ?: return@mapNotNull null
            val slug = m.optString("slug").ifEmpty { m.optString("id") }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (m.has("visibility") && m.optString("visibility") != "list") return@mapNotNull null
            AgentHarness.Model(slug, m.optString("display_name").ifEmpty { slug })
        }
    }.getOrDefault(emptyList())

    // Codex's own sandbox needs Landlock and seccomp, which proot does not provide.
    override fun turn(message: String, conversation: String?, options: AgentHarness.Options) = buildList {
        addAll(listOf(binary, "exec"))
        if (conversation != null) addAll(listOf("resume", conversation))
        addAll(listOf("--json", "--dangerously-bypass-approvals-and-sandbox", "--skip-git-repo-check"))
        options.model?.let { addAll(listOf("-m", it)) }
        // codex exec has no system prompt option, so a new conversation starts with the brief.
        add(if (conversation == null && options.brief != null) "${options.brief}\n\n$message" else message)
    }

    override fun parse(line: String): AgentEvent? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val item = o.optJSONObject("item")
        val id = item?.optString("id").orEmpty()
        val entries = when (o.optString("type")) {
            "thread.started" -> return AgentEvent(o.optString("thread_id").takeIf { it.isNotEmpty() })
            "item.started" -> when (item?.optString("type")) {
                "command_execution" -> shellCommand(item.optString("command")).let { listOf(AgentHost.Entry.Used(id, "Shell", firstLine(it), it)) }
                "mcp_tool_call" -> listOf(AgentHost.Entry.Used(id, item.optString("tool").ifEmpty { "Tool" }, item.optString("server"), item.optJSONObject("arguments")?.toString(2).orEmpty()))
                "web_search" -> listOf(AgentHost.Entry.Used(id, "Search", item.optString("query"), item.optString("query")))
                else -> emptyList()
            }
            "item.updated" -> when (item?.optString("type")) {
                "todo_list" -> listOf(todoEntry(id, item))
                else -> emptyList()
            }
            "item.completed" -> when (item?.optString("type")) {
                "agent_message" -> item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { listOf(AgentHost.Entry.Said(it)) }.orEmpty()
                "reasoning" -> item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { listOf(AgentHost.Entry.Thought(it)) }.orEmpty()
                "command_execution" -> listOf(AgentHost.Entry.Result(id, item.optString("aggregated_output"),
                    item.has("exit_code") && !item.isNull("exit_code") && item.optInt("exit_code") != 0))
                "mcp_tool_call" -> listOf(AgentHost.Entry.Result(id, item.optJSONObject("result")?.toString(2) ?: item.optJSONObject("error")?.optString("message").orEmpty(),
                    item.optString("status") == "failed"))
                "web_search" -> listOf(AgentHost.Entry.Result(id, "", false))
                "file_change" -> listOf(
                    AgentHost.Entry.Used(id, "Edit", changedFiles(item), changedFiles(item).replace(", ", "\n")),
                    AgentHost.Entry.Result(id, item.optString("status"), item.optString("status") == "failed"),
                )
                "todo_list" -> listOf(todoEntry(id, item))
                "error" -> listOf(AgentHost.Entry.Said("⚠ " + item.optString("message")))
                else -> emptyList()
            }
            "turn.completed" -> return AgentEvent(
                entries = listOf(AgentHost.Entry.Finished(null)),
                usage = o.optJSONObject("usage")?.let { AgentHost.Usage(0, 0.0, it.optLong("input_tokens") + it.optLong("cached_input_tokens"), it.optLong("output_tokens")) },
            )
            "turn.failed" -> listOf(AgentHost.Entry.Finished(o.optJSONObject("error")?.optString("message") ?: "turn failed"))
            "error" -> listOf(AgentHost.Entry.Finished(o.optString("message").ifEmpty { "error" }))
            else -> emptyList()
        }
        return if (entries.isEmpty()) null else AgentEvent(entries = entries)
    }

    private fun todoEntry(id: String, item: JSONObject): AgentHost.Entry.Used {
        val items = item.optJSONArray("items") ?: JSONArray()
        val text = (0 until items.length()).mapNotNull { i ->
            val todo = items.optJSONObject(i) ?: return@mapNotNull null
            "${if (todo.optBoolean("completed")) "[x]" else "[ ]"} ${todo.optString("text")}"
        }.joinToString("\n")
        return AgentHost.Entry.Used("$id-todo", "TodoWrite", "", text, output = "")
    }

    private fun changedFiles(item: JSONObject): String {
        val changes = item.optJSONArray("changes") ?: return ""
        return (0 until changes.length()).mapNotNull { changes.optJSONObject(it)?.optString("path") }.joinToString(", ").take(400)
    }
}

@VisibleForTesting
internal fun firstLine(text: String) = text.lineSequence().first().take(160)

private val SHELL_WRAPPER = Regex("^/bin/(?:ba)?sh -l?c '(.*)'$", RegexOption.DOT_MATCHES_ALL)

/** Codex runs each command as `/bin/bash -lc '<command>'`; the command is what the user wants to read. */
@VisibleForTesting
internal fun shellCommand(command: String) = SHELL_WRAPPER.find(command)?.groupValues?.get(1)?.replace("'\''", "'") ?: command
