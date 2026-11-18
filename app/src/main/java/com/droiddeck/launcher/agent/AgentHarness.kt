package com.droiddeck.launcher.agent

import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One coding agent CLI in the Linux userland, run as a process that takes its turns over stdin (see [AgentProtocol]). */
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
    /**
     * APIs the CLI reaches with a key pasted into DroidDeck, for a CLI whose own sign-in is a terminal
     * prompt; empty when it signs in through [signIn].
     */
    val providers: List<Provider> get() = emptyList()
    /** Ids of the [providers] with a key saved under [rootfs]. */
    fun keys(rootfs: File): Set<String> = emptySet()
    /**
     * Saves [key] for [provider]. A [Provider.custom] one also takes the [endpoint] it is served from, and
     * [get] fetches a URL from the userland, with the key as a bearer token when it is not null.
     */
    fun saveKey(rootfs: File, provider: Provider, key: String, endpoint: String? = null, get: (String, String?) -> String = { url, _ -> error("Cannot reach $url") }) {}
    fun removeKey(rootfs: File, provider: Provider) {}
    /** Whether the CLI has models that need no account; [useFree] then counts as signed in. */
    val free: Boolean get() = false
    fun useFree(rootfs: File) {}
    /** Usage reports the conversation's whole cost rather than the turn's. */
    val sessionCost: Boolean get() = false
    /** Whether [Options.plan] means anything to this CLI. */
    val plans: Boolean
    /** Reasoning effort levels the CLI accepts, lowest first; empty when it has none. */
    val efforts: List<String>
    /** Models to offer besides the CLI's default; [rootfs] is where the CLI's own files can be read. */
    fun models(rootfs: File): List<Model>
    /** How the CLI may run its tools, the one that asks before each action first and the default. */
    val permissions: List<Permission>
    /** The guest command of the process that runs the conversation's turns; [conversation] is null for a new one. */
    fun turn(message: String, conversation: String?, options: Options): List<String>
    /** A new reader for one [turn] process; it keeps what that process has said so far. */
    fun protocol(): AgentProtocol
    /** The folder, under the userland's root, where the CLI loads user skills (`<name>/SKILL.md`). */
    val skills: String
    /** The CLI's global instructions file under the userland's root, where DroidDeck keeps its rules in a marked block. */
    val instructions: String
    /** Sets the CLI's own defaults in the userland before it runs; [rootfs] is the userland's root. */
    fun prepare(rootfs: File) {}
    /** Context and plan limits the CLI keeps on disk rather than printing, read once a turn of [conversation] is done. */
    fun usage(rootfs: File, conversation: String?): AgentEvent? = null

    data class Model(val id: String, val label: String)

    /** An API reached with a key; [keyPage] is where the key is made, and a [custom] one is any OpenAI-compatible server. */
    data class Provider(val id: String, val name: String, val keyPage: String? = null, val custom: Boolean = false)

    /** A permission mode; [id] is DroidDeck's own name for it, which each CLI maps to its flags. */
    data class Permission(val id: String, @StringRes val label: Int, @StringRes val hint: Int)

    data class Options(
        val model: String? = null, val plan: Boolean = false, val brief: String? = null, val effort: String? = null,
        val permission: String = ASK, val workdir: String = "/root",
    )

    companion object {
        const val ASK = "ask"
        const val EDITS = "edits"
        const val AUTO = "auto"
        const val DONT_ASK = "dontask"
        const val FULL = "full"

        val all: List<AgentHarness> = listOf(ClaudeCode, Codex, OpenCode)
        fun byId(id: String?): AgentHarness = all.firstOrNull { it.id == id } ?: ClaudeCode
    }
}

/** What goes to one CLI process's stdin, and what its output lines mean. */
interface AgentProtocol {
    /**
     * The lines that start a turn of [message]; [started] is true for a process that has just been started. The
     * process stays open between turns, so work it started in the background can wake it.
     */
    fun input(message: String, conversation: String?, options: AgentHarness.Options, started: Boolean): List<String>
    /** One line of the process's output, or null when it carries nothing to show. */
    fun parse(line: String): AgentEvent?
    /** The line that answers [approval]. */
    fun answer(approval: AgentHost.Approval, allow: Boolean): String
}

/** The message a CLI tells its model when the user denies a tool call. */
internal const val DENIED = "The user denied this tool call."

/** What one line of output carries: whole entries, or text still being written. */
data class AgentEvent(
    val conversation: String? = null,
    val entries: List<AgentHost.Entry> = emptyList(),
    val text: String? = null,
    val thinking: String? = null,
    /** Tokens the conversation's context holds after this step. */
    val context: Long? = null,
    /** Tokens the model's context can hold. */
    val window: Long? = null,
    val limits: List<AgentHost.Limit>? = null,
    val usage: AgentHost.Usage? = null,
    /** This ends a turn the CLI started itself, such as the answer to a finished background task. */
    val background: Boolean = false,
    /** The error a background turn ended with, if it failed. */
    val backgroundError: String? = null,
    /** Lines to write to the process's stdin right away, the next step of a request it has answered. */
    val reply: List<String> = emptyList(),
    /** A tool call that waits for the user to allow it. */
    val approval: AgentHost.Approval? = null,
    /** The id of an approval the CLI no longer waits for. */
    val resolved: String? = null,
)

/** Claude Code: `claude -p` with stream-json input and output, resumed by session id. */
object ClaudeCode : AgentHarness {
    override val id = "claude"
    override val name = "Claude Code"
    override val binary = "/root/.local/bin/claude"
    override val install = "curl -fsSL https://claude.ai/install.sh | bash"
    override val signedIn = listOf("sh", "-c", "$binary auth status | grep -q '\"loggedIn\": true'")
    override val signIn = listOf(binary, "auth", "login", "--claudeai")
    override val plans = true
    override val efforts = listOf("low", "medium", "high", "xhigh", "max")

    /**
     * Commits and pull requests the agent makes carry no Claude Code credit or co-author line by
     * default. Only keys the user has not set are written, so their own choice stays.
     */
    override val skills = "root/.claude/skills"
    override val instructions = "root/.claude/CLAUDE.md"
    override fun prepare(rootfs: File) {
        val file = File(rootfs, "root/.claude/settings.json")
        val settings = if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull() ?: return else JSONObject()
        var changed = false
        if (!settings.has("includeCoAuthoredBy")) {
            settings.put("includeCoAuthoredBy", false)
            changed = true
        }
        if (!settings.has("attribution")) {
            settings.put("attribution", JSONObject().put("commit", "").put("pr", ""))
            changed = true
        }
        if (changed) runCatching {
            file.parentFile?.mkdirs()
            file.writeText(settings.toString(2) + "\n")
        }
    }

    override fun models(rootfs: File) = listOf(
        AgentHarness.Model("opus", "Opus"), AgentHarness.Model("sonnet", "Sonnet"), AgentHarness.Model("haiku", "Haiku"),
    )

    override val permissions = listOf(
        AgentHarness.Permission(AgentHarness.ASK, R.string.agent_permission_ask, R.string.agent_permission_ask_hint),
        AgentHarness.Permission(AgentHarness.EDITS, R.string.agent_permission_edits, R.string.agent_permission_edits_hint),
        AgentHarness.Permission(AgentHarness.AUTO, R.string.agent_permission_auto, R.string.agent_permission_auto_hint),
        AgentHarness.Permission(AgentHarness.DONT_ASK, R.string.agent_permission_dont_ask, R.string.agent_permission_dont_ask_hint),
        AgentHarness.Permission(AgentHarness.FULL, R.string.agent_permission_full, R.string.agent_permission_full_hint),
    )

    private val MODES = mapOf(
        AgentHarness.ASK to "manual", AgentHarness.EDITS to "acceptEdits", AgentHarness.AUTO to "auto",
        AgentHarness.DONT_ASK to "dontAsk", AgentHarness.FULL to "bypassPermissions",
    )

    /**
     * Prompts reach DroidDeck as can_use_tool requests on stdout (the SDK's stdio prompt tool). Plan mode keeps
     * answering none, so a turn that would leave plan mode ends on its plan.
     */
    override fun turn(message: String, conversation: String?, options: AgentHarness.Options) = buildList {
        addAll(listOf(binary, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
        val mode = if (options.plan) "plan" else MODES[options.permission] ?: "manual"
        addAll(listOf("--permission-mode", mode))
        if (mode != "plan" && mode != "bypassPermissions") add("--permission-prompt-tool=stdio")
        options.model?.let { addAll(listOf("--model", it)) }
        options.effort?.let { addAll(listOf("--effort", it)) }
        options.brief?.let { addAll(listOf("--append-system-prompt", it)) }
        if (conversation != null) addAll(listOf("--resume", conversation))
    }

    fun input(message: String): String = JSONObject()
        .put("type", "user")
        .put("message", JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message))))
        .toString()

    override fun protocol(): AgentProtocol = object : AgentProtocol {
        override fun input(message: String, conversation: String?, options: AgentHarness.Options, started: Boolean) = listOf(this@ClaudeCode.input(message))
        override fun parse(line: String) = this@ClaudeCode.parse(line)
        override fun answer(approval: AgentHost.Approval, allow: Boolean) = this@ClaudeCode.answer(approval, allow)
    }

    @VisibleForTesting
    internal fun answer(approval: AgentHost.Approval, allow: Boolean): String {
        val decision = if (allow) JSONObject().put("behavior", "allow").put("updatedInput", runCatching { JSONObject(approval.payload) }.getOrDefault(JSONObject()))
        else JSONObject().put("behavior", "deny").put("message", DENIED)
        return JSONObject().put("type", "control_response")
            .put("response", JSONObject().put("subtype", "success").put("request_id", approval.id).put("response", decision)).toString()
    }

    fun parse(line: String): AgentEvent? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val conversation = o.optString("session_id").takeIf { it.isNotEmpty() }
        // A subagent's messages carry the call that started it; only its tool calls are shown, under that call.
        val parent = o.optString("parent_tool_use_id").takeIf { it.isNotEmpty() && it != "null" }
        return when (o.optString("type")) {
            "stream_event" -> {
                if (parent != null) return null
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
                        "text" -> if (parent != null) null else block.optString("text").trim().takeIf { it.isNotEmpty() }?.let { AgentHost.Entry.Said(it) }
                        "thinking" -> if (parent != null) null else block.optString("thinking").trim().takeIf { it.isNotEmpty() }?.let { AgentHost.Entry.Thought(it) }
                        "tool_use" -> {
                            val input = block.optJSONObject("input")
                            AgentHost.Entry.Used(
                                block.optString("id"), block.optString("name"), detail(input), fullInput(block.optString("name"), input),
                                parent = parent.orEmpty(),
                                background = input?.optBoolean("run_in_background") == true || block.optString("name") == "Monitor",
                            )
                        }
                        else -> null
                    }
                }
                val usage = message?.optJSONObject("usage")
                val context = usage?.let { it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens") + it.optLong("output_tokens") }
                AgentEvent(conversation, entries, context = context?.takeIf { it > 0 && parent == null })
            }
            "user" -> {
                val content = o.optJSONObject("message")?.optJSONArray("content") ?: return conversation?.let { AgentEvent(it) }
                val results = (0 until content.length()).mapNotNull { i ->
                    val block = content.optJSONObject(i)?.takeIf { it.optString("type") == "tool_result" } ?: return@mapNotNull null
                    AgentHost.Entry.Result(block.optString("tool_use_id"), resultText(block.opt("content")), block.optBoolean("is_error"))
                }
                AgentEvent(conversation, results)
            }
            "rate_limit_event" -> AgentEvent(conversation, limits = limits(o.optJSONObject("rate_limit_info")))
            "control_request" -> {
                val request = o.optJSONObject("request")?.takeIf { it.optString("subtype") == "can_use_tool" } ?: return null
                val tool = request.optString("tool_name")
                val input = request.optJSONObject("input") ?: JSONObject()
                AgentEvent(approval = AgentHost.Approval(o.optString("request_id"), tool, detail(input), fullInput(tool, input), input.toString()))
            }
            "control_cancel_request" -> AgentEvent(resolved = o.optString("request_id"))
            // The CLI starts turns of its own for background tasks (a resumed session first answers the ones the last
            // run left behind). Such a result is not the end of the user's turn; the host decides what it ends.
            "result" -> {
                val usage = AgentHost.Usage(
                    o.optLong("duration_ms"), o.optDouble("total_cost_usd", 0.0),
                    o.optJSONObject("usage")?.let { it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens") } ?: 0,
                    o.optJSONObject("usage")?.optLong("output_tokens") ?: 0,
                )
                if (o.optJSONObject("origin")?.optString("kind") == "task-notification") AgentEvent(
                    conversation, usage = usage, background = true,
                    backgroundError = if (o.optBoolean("is_error")) o.optString("result").ifEmpty { o.optString("subtype") } else null,
                )
                else AgentEvent(
                    conversation,
                    listOf(AgentHost.Entry.Finished(if (o.optBoolean("is_error")) o.optString("result").ifEmpty { o.optString("subtype") } else null)),
                    window = window(o.optJSONObject("modelUsage")),
                    usage = usage,
                )
            }
            else -> conversation?.let { AgentEvent(it) }
        }
    }

    private val WINDOW_MINUTES = mapOf("five_hour" to 300, "seven_day" to 10_080)

    @VisibleForTesting
    internal fun limits(info: JSONObject?): List<AgentHost.Limit>? {
        val windows = info?.optJSONObject("unifiedWindows") ?: return null
        return WINDOW_MINUTES.mapNotNull { (key, minutes) ->
            val w = windows.optJSONObject(key) ?: return@mapNotNull null
            AgentHost.Limit(minutes, (w.optDouble("utilization", 0.0) * 100).toInt(), w.optLong("resetsAt"))
        }.takeIf { it.isNotEmpty() }
    }

    /** The context window of the model that did most of the turn; subagents may run a smaller one. */
    private fun window(models: JSONObject?): Long? {
        models ?: return null
        return models.keys().asSequence().mapNotNull { models.optJSONObject(it) }
            .maxByOrNull { it.optDouble("costUSD", 0.0) }?.optLong("contextWindow")?.takeIf { it > 0 }
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
            tool == "Edit" -> input.optString("file_path") + "\n" + lineDiff(input.optString("old_string"), input.optString("new_string"))
            tool == "Write" -> input.optString("file_path") + "\n" + lineDiff("", input.optString("content"))
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

/** OpenAI Codex: `codex app-server`, its JSON-RPC protocol over stdio, resumed by thread id. */
object Codex : AgentHarness {
    override val id = "codex"
    override val name = "Codex"
    override val binary = "/root/.local/bin/codex"
    override val skills = "root/.codex/skills"
    override val instructions = "root/.codex/AGENTS.md"
    // Code mode runs in a helper that each release ships as its own download.
    private const val HOST = "/root/.local/bin/codex-code-mode-host"
    override val files get() = listOf(binary, HOST)
    override val install = """
        set -e
        release=${'$'}(curl -fsSL https://api.github.com/repos/openai/codex/releases/latest)
        mkdir -p /root/.local/bin
        for name in codex codex-code-mode-host; do
          url=${'$'}(printf '%s' "${'$'}release" | grep -o "https://[^\"]*/${'$'}name-aarch64-unknown-linux-musl.tar.gz" | head -1)
          echo "Downloading ${'$'}name"
          curl -fsSL "${'$'}url" | tar -xz -C /tmp
          install -m755 /tmp/${'$'}name-aarch64-unknown-linux-musl /root/.local/bin/${'$'}name
        done
    """.trimIndent()
    override val signedIn = listOf(binary, "login", "status")
    override val signIn = listOf(binary, "login")
    override val plans = false
    override val efforts = listOf("low", "medium", "high")

    // Codex's sandbox needs Landlock and seccomp, which proot does not provide, so commands run unsandboxed
    // and "untrusted" is what makes Codex ask before each one that is not known to be safe.
    override val permissions = listOf(
        AgentHarness.Permission(AgentHarness.ASK, R.string.agent_permission_ask, R.string.agent_permission_ask_hint),
        AgentHarness.Permission(AgentHarness.FULL, R.string.agent_permission_full, R.string.agent_permission_full_hint),
    )

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

    override fun turn(message: String, conversation: String?, options: AgentHarness.Options) = withoutStderr("codex", listOf(binary, "app-server"))

    override fun protocol(): AgentProtocol = Protocol()

    @VisibleForTesting
    internal class Protocol : AgentProtocol {
        private var next = 1
        private var thread: String? = null
        /** The thread/start or thread/resume request, and the turn that waits for its thread. */
        private var opening = -1
        private var waiting: JSONObject? = null
        private var starting = -1
        private var turnId = ""
        /** Each file change's paths and diffs, from its start; an approval for it names only the item. */
        private val changes = mutableMapOf<String, JSONArray>()
        private var input = 0L
        private var output = 0L

        override fun input(message: String, conversation: String?, options: AgentHarness.Options, started: Boolean): List<String> {
            val turn = JSONObject()
                .put("input", JSONArray().put(JSONObject().put("type", "text").put("text", message).put("text_elements", JSONArray())))
            options.model?.let { turn.put("model", it) }
            options.effort?.let { turn.put("effort", it) }
            val known = thread
            if (!started && known != null) return listOf(startTurn(turn.put("threadId", known)))
            val params = JSONObject()
                .put("approvalPolicy", if (options.permission == AgentHarness.FULL) "never" else "untrusted")
                .put("sandbox", "danger-full-access")
            options.model?.let { params.put("model", it) }
            options.brief?.let { params.put("developerInstructions", it) }
            if (conversation != null) params.put("threadId", conversation)
            waiting = turn
            opening = next++
            return listOf(
                JSONObject().put("id", next++).put("method", "initialize")
                    .put("params", JSONObject().put("clientInfo", JSONObject().put("name", "droiddeck").put("title", "DroidDeck").put("version", "1"))
                        .put("capabilities", JSONObject.NULL)).toString(),
                JSONObject().put("method", "initialized").toString(),
                JSONObject().put("id", opening).put("method", if (conversation != null) "thread/resume" else "thread/start").put("params", params).toString(),
            )
        }

        private fun startTurn(params: JSONObject): String {
            starting = next++
            return JSONObject().put("id", starting).put("method", "turn/start").put("params", params).toString()
        }

        override fun answer(approval: AgentHost.Approval, allow: Boolean): String =
            JSONObject(approval.payload).put("result", JSONObject().put("decision", if (allow) "accept" else "decline")).toString()

        override fun parse(line: String): AgentEvent? {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
            val method = o.optString("method")
            if (method.isEmpty()) return response(o)
            val params = o.optJSONObject("params") ?: JSONObject()
            if (o.has("id")) return request(o.get("id"), method, params)
            return notification(method, params)
        }

        private fun response(o: JSONObject): AgentEvent? {
            val id = o.optInt("id", -1)
            val error = o.optJSONObject("error")?.optString("message")?.ifEmpty { "error" }
            return when (id) {
                opening -> {
                    if (error != null) return AgentEvent(entries = listOf(AgentHost.Entry.Finished(error)))
                    val started = o.optJSONObject("result")?.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotEmpty() } ?: return null
                    thread = started
                    val turn = waiting ?: return AgentEvent(started)
                    waiting = null
                    AgentEvent(started, reply = listOf(startTurn(turn.put("threadId", started))))
                }
                starting -> error?.let { AgentEvent(entries = listOf(AgentHost.Entry.Finished(it))) }
                else -> null
            }
        }

        private fun request(id: Any, method: String, params: JSONObject): AgentEvent {
            val payload = JSONObject().put("id", id).toString()
            return when (method) {
                "item/commandExecution/requestApproval" -> {
                    val command = shellCommand(params.optString("command"))
                    val reason = params.optString("reason").takeIf { it.isNotEmpty() && it != "null" }
                    AgentEvent(approval = AgentHost.Approval(id.toString(), "Shell", firstLine(command), listOfNotNull(command, reason).joinToString("\n\n"), payload))
                }
                "item/fileChange/requestApproval" -> {
                    val list = changes[params.optString("itemId")] ?: JSONArray()
                    val files = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    AgentEvent(approval = AgentHost.Approval(
                        id.toString(), "Edit", files.joinToString(", ") { it.optString("path") }.take(400),
                        files.joinToString("\n\n") { it.optString("path") + "\n" + patchLines(it.optString("diff"), it.optJSONObject("kind")?.optString("type").orEmpty()) }, payload,
                    ))
                }
                // Everything else asks for something DroidDeck has no screen for; refusing keeps the turn going.
                else -> AgentEvent(reply = listOf(JSONObject(payload)
                    .put("error", JSONObject().put("code", -32601).put("message", "DroidDeck cannot answer $method")).toString()))
            }
        }

        private fun notification(method: String, params: JSONObject): AgentEvent? {
            val item = params.optJSONObject("item")
            val id = item?.optString("id").orEmpty()
            val entries: List<AgentHost.Entry> = when (method) {
                "thread/started" -> return params.optJSONObject("thread")?.optString("id")?.takeIf { it.isNotEmpty() }?.let { AgentEvent(it) }
                "turn/started" -> {
                    turnId = params.optJSONObject("turn")?.optString("id").orEmpty()
                    input = 0
                    output = 0
                    return null
                }
                "item/agentMessage/delta" -> return AgentEvent(text = params.optString("delta"))
                "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> return AgentEvent(thinking = params.optString("delta"))
                "item/started" -> when (item?.optString("type")) {
                    "commandExecution" -> shellCommand(item.optString("command")).let { listOf(AgentHost.Entry.Used(id, "Shell", firstLine(it), it)) }
                    "mcpToolCall" -> listOf(AgentHost.Entry.Used(id, item.optString("tool").ifEmpty { "Tool" }, item.optString("server"), item.opt("arguments")?.let(::pretty).orEmpty()))
                    "webSearch" -> listOf(AgentHost.Entry.Used(id, "Search", item.optString("query"), item.optString("query")))
                    "fileChange" -> {
                        val list = item.optJSONArray("changes") ?: JSONArray()
                        changes[id] = list
                        fileEntries(id, list)
                    }
                    else -> emptyList()
                }
                "item/completed" -> when (item?.optString("type")) {
                    "agentMessage" -> item.optString("text").trim().takeIf { it.isNotEmpty() }?.let { listOf(AgentHost.Entry.Said(it)) }.orEmpty()
                    "reasoning" -> {
                        val parts = (strings(item.optJSONArray("summary")) + strings(item.optJSONArray("content"))).joinToString("\n\n").trim()
                        if (parts.isEmpty()) emptyList() else listOf(AgentHost.Entry.Thought(parts))
                    }
                    "commandExecution" -> {
                        val status = item.optString("status")
                        val exit = if (item.isNull("exitCode")) 0 else item.optInt("exitCode")
                        val out = item.optString("aggregatedOutput").takeUnless { item.isNull("aggregatedOutput") }.orEmpty()
                        listOf(AgentHost.Entry.Result(id, if (status == "declined") out.ifEmpty { DENIED } else out, status != "completed" || exit != 0))
                    }
                    "mcpToolCall" -> listOf(AgentHost.Entry.Result(id,
                        item.optJSONObject("result")?.let(::pretty) ?: item.optJSONObject("error")?.optString("message").orEmpty(), item.optString("status") == "failed"))
                    "webSearch" -> listOf(AgentHost.Entry.Result(id, "", false))
                    "fileChange" -> {
                        val list = item.optJSONArray("changes") ?: changes[id] ?: JSONArray()
                        changes.remove(id)
                        val failed = item.optString("status") != "completed"
                        (0 until list.length()).map { AgentHost.Entry.Result("$id#$it", if (failed) item.optString("status") else "", failed) }
                    }
                    else -> emptyList()
                }
                "turn/plan/updated" -> listOf(plan(params.optJSONArray("plan")))
                "thread/tokenUsage/updated" -> {
                    val usage = params.optJSONObject("tokenUsage") ?: return null
                    val last = usage.optJSONObject("last")
                    input += last?.optLong("inputTokens") ?: 0
                    output += last?.optLong("outputTokens") ?: 0
                    return AgentEvent(
                        context = last?.optLong("totalTokens")?.takeIf { it > 0 },
                        window = usage.optLong("modelContextWindow").takeIf { it > 0 },
                    )
                }
                "account/rateLimits/updated" -> return rateLimits(params.optJSONObject("rateLimits"))?.let { AgentEvent(limits = it) }
                "serverRequest/resolved" -> return AgentEvent(resolved = params.opt("requestId")?.toString())
                "turn/completed" -> {
                    val turn = params.optJSONObject("turn") ?: JSONObject()
                    val error = when (turn.optString("status")) {
                        "failed" -> turn.optJSONObject("error")?.optString("message")?.ifEmpty { null } ?: "turn failed"
                        else -> null
                    }
                    return AgentEvent(
                        entries = listOf(AgentHost.Entry.Finished(error)),
                        usage = AgentHost.Usage(if (turn.isNull("durationMs")) 0 else turn.optLong("durationMs"), 0.0, input, output),
                    )
                }
                else -> emptyList()
            }
            return if (entries.isEmpty()) null else AgentEvent(entries = entries)
        }

        private fun plan(steps: JSONArray?): AgentHost.Entry.Used {
            val text = (0 until (steps?.length() ?: 0)).mapNotNull { i ->
                val step = steps?.optJSONObject(i) ?: return@mapNotNull null
                val mark = when (step.optString("status")) { "completed" -> "[x]"; "inProgress" -> "[~]"; else -> "[ ]" }
                "$mark ${step.optString("step")}"
            }.joinToString("\n")
            return AgentHost.Entry.Used("$turnId-todo", "TodoWrite", "", text, output = "")
        }
    }

    /** One Edit per changed file: its path, then the diff as lines led by ' ', '-' or '+'. */
    private fun fileEntries(id: String, changes: JSONArray) = (0 until changes.length()).mapNotNull { i ->
        val change = changes.optJSONObject(i) ?: return@mapNotNull null
        val path = change.optString("path")
        AgentHost.Entry.Used("$id#$i", "Edit", path, path + "\n" + patchLines(change.optString("diff"), change.optJSONObject("kind")?.optString("type").orEmpty()))
    }

    @VisibleForTesting
    internal fun rateLimits(snapshot: JSONObject?): List<AgentHost.Limit>? {
        snapshot ?: return null
        return listOf("primary", "secondary").mapNotNull { key ->
            val w = snapshot.optJSONObject(key) ?: return@mapNotNull null
            AgentHost.Limit(w.optInt("windowDurationMins"), w.optDouble("usedPercent", 0.0).toInt(), w.optLong("resetsAt"))
        }.takeIf { it.isNotEmpty() }
    }

    private fun strings(list: JSONArray?) = (0 until (list?.length() ?: 0)).mapNotNull { list?.optString(it)?.takeIf { s -> s.isNotBlank() } }

    private fun pretty(value: Any): String = when (value) {
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        JSONObject.NULL -> ""
        else -> value.toString()
    }
}

/** OpenCode: `opencode acp`, the Agent Client Protocol over stdio, resumed by session id. */
object OpenCode : AgentHarness {
    override val id = "opencode"
    override val name = "OpenCode"
    override val binary = "/root/.opencode/bin/opencode"
    override val skills = "root/.config/opencode/skills"
    override val instructions = "root/.config/opencode/AGENTS.md"
    override val install = "curl -fsSL https://opencode.ai/install | bash -s -- --no-modify-path"
    private const val AUTH = "root/.local/share/opencode/auth.json"
    private const val CONFIG = "root/.config/opencode/opencode.json"
    private const val FREE = "root/.config/opencode/.droiddeck-free"
    private const val MODELS = "root/.cache/droiddeck/opencode-models"
    /** The provider id DroidDeck gives a server the user names, in opencode's config and auth. */
    private const val CUSTOM = "droiddeck"

    // `opencode models` lists only what the saved keys reach, so the check that runs it keeps the model list current.
    override val signedIn = listOf("sh", "-c",
        "mkdir -p /${MODELS.substringBeforeLast('/')}; $binary models --verbose > /$MODELS.$$ 2>/dev/null && mv -f /$MODELS.$$ /$MODELS; rm -f /$MODELS.$$; " +
            "grep -q '\"type\"' /$AUTH 2>/dev/null || [ -e /$FREE ] || grep -q '\"$CUSTOM\"' /$CONFIG 2>/dev/null")
    // Ids are opencode's own (models.dev), so a saved key is what `opencode auth login` would save for that provider.
    override val providers = listOf(
        AgentHarness.Provider("opencode", "OpenCode Zen", "https://opencode.ai/auth"),
        AgentHarness.Provider("ollama-cloud", "Ollama Cloud", "https://ollama.com/settings/keys"),
        AgentHarness.Provider("openrouter", "OpenRouter", "https://openrouter.ai/settings/keys"),
        AgentHarness.Provider("openai", "OpenAI", "https://platform.openai.com/api-keys"),
        AgentHarness.Provider("anthropic", "Anthropic", "https://console.anthropic.com/settings/keys"),
        AgentHarness.Provider("google", "Gemini", "https://aistudio.google.com/apikey"),
        AgentHarness.Provider(CUSTOM, "Other server", custom = true),
    )
    override val signIn = listOf("/root/.local/bin/android-open", providers.first().keyPage!!)
    override val free = true
    override val plans = true
    override val efforts = listOf("low", "medium", "high")

    override fun keys(rootfs: File): Set<String> {
        val auth = runCatching { JSONObject(File(rootfs, AUTH).readText()) }.getOrNull()
        val custom = runCatching { JSONObject(File(rootfs, CONFIG).readText()).optJSONObject("provider")?.has(CUSTOM) }.getOrNull() == true
        return providers.filter { auth?.has(it.id) == true || (it.custom && custom) }.map { it.id }.toSet()
    }

    /**
     * Saves the key as opencode's own `auth login` would. A custom server also gets a provider in
     * opencode's config with the models its `/models` lists, since opencode does not list them itself.
     */
    override fun saveKey(rootfs: File, provider: AgentHarness.Provider, key: String, endpoint: String?, get: (String, String?) -> String) {
        val token = key.trim().takeIf { it.isNotEmpty() }
        // Both files are read before either is written, so one that cannot be read changes nothing.
        val authFile = File(rootfs, AUTH)
        val auth = readJson(authFile)
        if (token != null) auth.put(provider.id, JSONObject().put("type", "api").put("key", token)) else auth.remove(provider.id)
        if (!provider.custom) return writeJson(authFile, auth, private = true)
        val base = endpoint(endpoint ?: "")
        val configFile = File(rootfs, CONFIG)
        val before = configFile.takeIf { it.exists() }?.readText()
        val config = readJson(configFile)
        val models = modelIds(get("$base/models", token))
        check(models.isNotEmpty()) { "No models at $base" }
        if (!config.has("\$schema")) config.put("\$schema", "https://opencode.ai/config.json")
        val all = config.optJSONObject("provider") ?: JSONObject().also { config.put("provider", it) }
        all.put(CUSTOM, JSONObject()
            .put("npm", "@ai-sdk/openai-compatible")
            .put("name", runCatching { java.net.URI(base).host }.getOrNull() ?: provider.name)
            .put("options", JSONObject().put("baseURL", base))
            .put("models", JSONObject().apply { models.forEach { put(it, JSONObject().put("name", it)) } }))
        writeJson(configFile, config)
        try {
            writeJson(authFile, auth, private = true)
        } catch (e: Exception) {
            val undone = runCatching {
                if (before != null) writeFile(configFile, before) else java.nio.file.Files.deleteIfExists(configFile.toPath())
            }
            // First, since the setup row shows two lines of the message.
            undone.exceptionOrNull()?.let { throw IllegalStateException("The server config could not be put back (${it.message}): ${e.message}", e) }
            throw e
        }
    }

    override fun removeKey(rootfs: File, provider: AgentHarness.Provider) {
        if (File(rootfs, AUTH).exists()) editJson(File(rootfs, AUTH), private = true) { it.remove(provider.id) }
        if (provider.custom && File(rootfs, CONFIG).exists()) editJson(File(rootfs, CONFIG)) { it.optJSONObject("provider")?.remove(CUSTOM) }
    }

    /** A server address as typed, made into the OpenAI-compatible base URL opencode calls. */
    @VisibleForTesting
    internal fun endpoint(typed: String): String {
        val url = typed.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
        require(url.substringAfter("://").isNotEmpty()) { "No server address" }
        return if (url.substringAfter("://").contains('/')) url else "$url/v1"
    }

    @VisibleForTesting
    internal fun modelIds(body: String): List<String> {
        // The userland can print its own warnings ahead of the response.
        val json = body.substring(body.indexOf('{').coerceAtLeast(0), body.lastIndexOf('}') + 1)
        val data = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }.distinct()
    }

    // A file that cannot be read holds the user's other providers too, so it is never replaced.
    private fun readJson(file: File) = if (file.exists()) JSONObject(file.readText()) else JSONObject()

    private fun editJson(file: File, private: Boolean = false, edit: (JSONObject) -> Unit) =
        writeJson(file, readJson(file).also(edit), private)

    /** Writes beside [file] and moves it into place, so a write that stops partway leaves the old file. */
    private fun writeJson(file: File, json: JSONObject, private: Boolean = false) = writeFile(file, json.toString(2) + "\n", private)

    private fun writeFile(file: File, text: String, private: Boolean = false) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        try {
            temp.writeText(text)
            if (private) {
                temp.setReadable(false, false)
                temp.setReadable(true, true)
            }
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    override fun useFree(rootfs: File) {
        File(rootfs, FREE).apply { parentFile?.mkdirs() }.writeText("Free OpenCode Zen models, chosen in DroidDeck's agent setup.\n")
    }

    private class Listed(val modified: Long, val models: List<AgentHarness.Model>, val windows: Map<String, Long>)
    @Volatile private var listed: Listed? = null

    private fun listed(rootfs: File): Listed? {
        val file = File(rootfs, MODELS)
        val modified = file.lastModified().takeIf { it > 0 } ?: return null
        listed?.takeIf { it.modified == modified }?.let { return it }
        return runCatching { listModels(file.readText()) }.getOrNull()?.let { (models, windows) -> Listed(modified, models, windows) }?.also { listed = it }
    }

    override fun models(rootfs: File) = listed(rootfs)?.models.orEmpty()

    /** `opencode models --verbose` prints each model's id on a line of its own, then its details as JSON. */
    @VisibleForTesting
    internal fun listModels(output: String): Pair<List<AgentHarness.Model>, Map<String, Long>> {
        val models = mutableListOf<AgentHarness.Model>()
        val windows = mutableMapOf<String, Long>()
        var id: String? = null
        val json = StringBuilder()
        for (line in output.lineSequence()) {
            val current = id
            if (current == null) {
                if (MODEL_ID.matches(line)) id = line.trim()
                continue
            }
            json.appendLine(line)
            if (line != "}") continue
            runCatching { JSONObject(json.toString()) }.getOrNull()?.takeIf { it.optString("status") != "deprecated" }?.let { o ->
                val provider = o.optString("providerID")
                val label = o.optString("name").ifEmpty { current }
                val source = if (provider == "opencode" || provider == CUSTOM) "" else providers.firstOrNull { it.id == provider }?.name ?: provider
                models += AgentHarness.Model(current, if (source.isEmpty()) label else "$label · $source")
                o.optJSONObject("limit")?.optLong("context")?.takeIf { it > 0 }?.let { windows[current] = it }
            }
            id = null
            json.clear()
        }
        return models to windows
    }

    private val MODEL_ID = Regex("""^[\w.-]+/\S+$""")

    override val permissions = listOf(
        AgentHarness.Permission(AgentHarness.ASK, R.string.agent_permission_ask, R.string.agent_permission_ask_hint),
        AgentHarness.Permission(AgentHarness.EDITS, R.string.agent_permission_edits, R.string.agent_permission_edits_hint),
        AgentHarness.Permission(AgentHarness.FULL, R.string.agent_permission_full, R.string.agent_permission_full_hint),
    )

    /**
     * Rules merged over the user's opencode config. OpenCode applies the last rule that matches in key order, so the
     * asking modes name each permission rather than "*", which could land after a rule of the user's and override it.
     */
    @VisibleForTesting
    internal fun rules(permission: String): JSONObject {
        val asks = listOf("bash", "webfetch", "websearch", "codesearch")
        return when (permission) {
            AgentHarness.FULL -> JSONObject().put("*", "allow")
            AgentHarness.EDITS -> JSONObject().put("edit", "allow").apply { asks.forEach { put(it, "ask") } }
            else -> JSONObject().put("edit", "ask").apply { asks.forEach { put(it, "ask") } }
        }
    }

    override fun turn(message: String, conversation: String?, options: AgentHarness.Options) =
        withoutStderr("opencode", listOf("/usr/bin/env", "OPENCODE_PERMISSION=${rules(options.permission)}", binary, "acp"))

    /** The conversation's cost so far; DroidDeck takes the turn's share from what its chat has recorded. */
    override val sessionCost = true

    override fun protocol(): AgentProtocol = Protocol()

    /** `opencode acp`: the Agent Client Protocol, JSON-RPC over stdio. */
    @VisibleForTesting
    internal class Protocol : AgentProtocol {
        private enum class Stage { INIT, SESSION, CONFIG, PROMPT, IDLE }

        private var next = 1
        private var stage = Stage.IDLE
        private var pending = -1
        private var session: String? = null
        private var resume: String? = null
        private var cwd = "/root"
        /** Requests for the session still to send, each once the one before it has been answered. */
        private val queue = ArrayDeque<(String) -> JSONObject>()
        private val said = StringBuilder()
        private val thought = StringBuilder()
        /** Each tool call's name, which only its first update carries, and its latest entry. */
        private val names = mutableMapOf<String, String>()
        private val used = mutableMapOf<String, AgentHost.Entry.Used>()
        private var cost = 0.0
        private var started = 0L

        override fun input(message: String, conversation: String?, options: AgentHarness.Options, started: Boolean): List<String> {
            val text = if (conversation == null && options.brief != null) "${options.brief}\n\n$message" else message
            val prompt = { id: String ->
                JSONObject().put("sessionId", id).put("prompt", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            }
            queue.clear()
            val known = session
            if (!started && known != null) return listOf(send(Stage.PROMPT, "session/prompt", prompt(known)))
            fun option(config: String, value: String) = { id: String -> JSONObject().put("sessionId", id).put("configId", config).put("value", value) }
            options.model?.let { queue += option("model", it) }
            options.effort?.let { queue += option("effort", it) }
            queue += option("mode", if (options.plan) "plan" else "build")
            queue += prompt
            resume = conversation
            cwd = options.workdir
            return listOf(send(Stage.INIT, "initialize", JSONObject().put("protocolVersion", 1).put("clientCapabilities", JSONObject())))
        }

        private fun send(stage: Stage, method: String, params: JSONObject): String {
            this.stage = stage
            pending = next++
            if (stage == Stage.PROMPT) started = System.currentTimeMillis()
            return JSONObject().put("jsonrpc", "2.0").put("id", pending).put("method", method).put("params", params).toString()
        }

        /** The next queued request: the prompt once nothing else is left. */
        private fun advance(id: String): String {
            val make = queue.removeFirst()
            val params = make(id)
            return if (queue.isEmpty()) send(Stage.PROMPT, "session/prompt", params) else send(Stage.CONFIG, "session/set_config_option", params)
        }

        override fun answer(approval: AgentHost.Approval, allow: Boolean): String = JSONObject(approval.payload).put("jsonrpc", "2.0")
            .put("result", JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", if (allow) "once" else "reject"))).toString()

        override fun parse(line: String): AgentEvent? {
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
            val method = o.optString("method")
            val params = o.optJSONObject("params") ?: JSONObject()
            return when {
                method.isEmpty() -> response(o)
                o.has("id") -> request(o.get("id"), method, params)
                method == "session/update" -> update(params.optJSONObject("update") ?: return null)
                else -> null
            }
        }

        private fun response(o: JSONObject): AgentEvent? {
            if (o.optInt("id", -1) != pending) return null
            val error = o.optJSONObject("error")?.let { it.optJSONObject("data")?.optString("message")?.ifEmpty { null } ?: it.optString("message").ifEmpty { "error" } }
            val result = o.optJSONObject("result") ?: JSONObject()
            return when (stage) {
                Stage.INIT -> error?.let(::failed) ?: AgentEvent(reply = listOf(
                    resume?.let { send(Stage.SESSION, "session/resume", JSONObject().put("sessionId", it).put("cwd", cwd).put("mcpServers", JSONArray())) }
                        ?: send(Stage.SESSION, "session/new", JSONObject().put("cwd", cwd).put("mcpServers", JSONArray())),
                ))
                Stage.SESSION -> {
                    if (error != null) return failed(error)
                    val id = result.optString("sessionId").ifEmpty { resume.orEmpty() }.takeIf { it.isNotEmpty() } ?: return failed("no session")
                    session = id
                    AgentEvent(id, reply = listOf(advance(id)))
                }
                // A model without effort levels refuses one; the turn goes on with its default.
                Stage.CONFIG -> AgentEvent(reply = listOf(advance(session ?: return null)))
                Stage.PROMPT -> {
                    stage = Stage.IDLE
                    val usage = result.optJSONObject("usage")
                    val reason = result.optString("stopReason")
                    AgentEvent(
                        session,
                        flush() + AgentHost.Entry.Finished(error ?: if (reason == "refusal") "refused" else null),
                        usage = AgentHost.Usage(
                            System.currentTimeMillis() - started, cost,
                            (usage?.optLong("inputTokens") ?: 0) + (usage?.optLong("cachedReadTokens") ?: 0),
                            (usage?.optLong("outputTokens") ?: 0) + (usage?.optLong("thoughtTokens") ?: 0),
                        ),
                    )
                }
                Stage.IDLE -> null
            }
        }

        private fun failed(error: String): AgentEvent {
            stage = Stage.IDLE
            return AgentEvent(session, flush() + AgentHost.Entry.Finished(error))
        }

        private fun request(id: Any, method: String, params: JSONObject): AgentEvent {
            val payload = JSONObject().put("id", id).toString()
            if (method != "session/request_permission") {
                // File writes and terminals stay with OpenCode, which does them itself when the client declines.
                return AgentEvent(reply = listOf(JSONObject(payload).put("jsonrpc", "2.0")
                    .put("error", JSONObject().put("code", -32601).put("message", "DroidDeck cannot answer $method")).toString()))
            }
            val call = params.optJSONObject("toolCall") ?: JSONObject()
            val raw = call.optJSONObject("rawInput") ?: JSONObject()
            val name = names[call.optString("toolCallId")] ?: call.optString("kind")
            val kind = call.optString("kind")
            val approval = when {
                kind == "execute" -> AgentHost.Approval(id.toString(), "Bash", firstLine(raw.optString("command")), raw.optString("command"), payload)
                // OpenCode asks for a folder outside the workspace on its own, before the tool's own ask.
                raw.has("parentDir") -> AgentHost.Approval(
                    id.toString(), "External directory", raw.optString("parentDir"),
                    listOf(raw.optString("parentDir"), raw.optString("command")).filter { it.isNotEmpty() }.joinToString("\n\n"), payload,
                )
                raw.has("diff") -> {
                    val path = raw.optString("filepath")
                    AgentHost.Approval(id.toString(), "Edit", path, path + "\n" + patchLines(raw.optString("diff"), ""), payload)
                }
                else -> AgentHost.Approval(id.toString(), toolName(name), call.optString("title"), raw.toString(2), payload)
            }
            return AgentEvent(session, flush(), approval = approval)
        }

        private fun update(u: JSONObject): AgentEvent? = when (u.optString("sessionUpdate")) {
            "agent_message_chunk" -> chunk(u)?.let { said.append(it); AgentEvent(session, text = it) }
            "agent_thought_chunk" -> chunk(u)?.let { thought.append(it); AgentEvent(session, thinking = it) }
            "tool_call", "tool_call_update" -> AgentEvent(session, flush() + tool(u))
            "usage_update" -> {
                u.optJSONObject("cost")?.optDouble("amount")?.takeIf { !it.isNaN() }?.let { cost = it }
                AgentEvent(session, context = u.optLong("used").takeIf { it > 0 }, window = u.optLong("size").takeIf { it > 0 })
            }
            else -> null
        }

        private fun chunk(u: JSONObject) = u.optJSONObject("content")?.takeIf { it.optString("type") == "text" }?.optString("text")?.takeIf { it.isNotEmpty() }

        /** What was written since the last entry, as whole entries. */
        private fun flush(): List<AgentHost.Entry> = buildList {
            thought.toString().trim().takeIf { it.isNotEmpty() }?.let { add(AgentHost.Entry.Thought(it)) }
            said.toString().trim().takeIf { it.isNotEmpty() }?.let { add(AgentHost.Entry.Said(it)) }
            thought.clear()
            said.clear()
        }

        private fun tool(u: JSONObject): List<AgentHost.Entry> {
            val id = u.optString("toolCallId")
            if (u.optString("sessionUpdate") == "tool_call") names[id] = u.optString("title")
            val input = u.optJSONObject("rawInput")?.takeIf { it.length() > 0 }
            val entry = if (input != null || used[id] == null) tool(id, names[id].orEmpty(), input ?: JSONObject()) else used.getValue(id)
            used[id] = entry
            val raw = u.optJSONObject("rawOutput")
            return when (u.optString("status")) {
                "completed" -> {
                    val exit = raw?.optJSONObject("metadata")?.opt("exit")
                    listOf(entry, AgentHost.Entry.Result(id, raw?.optString("output")?.ifEmpty { null } ?: content(u), exit is Number && exit.toInt() != 0))
                }
                "failed" -> listOf(entry, AgentHost.Entry.Result(id, raw?.optString("error")?.ifEmpty { null } ?: content(u), true))
                else -> listOf(entry)
            }
        }

        private fun content(u: JSONObject): String {
            val list = u.optJSONArray("content") ?: return ""
            return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optJSONObject("content")?.optString("text") }.joinToString("\n")
        }
    }

    private fun toolName(name: String) = name.replaceFirstChar { it.uppercase() }.ifEmpty { "Tool" }

    private fun tool(id: String, name: String, input: JSONObject): AgentHost.Entry.Used {
        val path = input.optString("filePath")
        return when (name) {
            "bash" -> AgentHost.Entry.Used(id, "Bash", firstLine(input.optString("command")), input.optString("command"))
            "edit" -> AgentHost.Entry.Used(id, "Edit", path, path + "\n" + lineDiff(input.optString("oldString"), input.optString("newString")))
            "write" -> AgentHost.Entry.Used(id, "Write", path, path + "\n" + lineDiff("", input.optString("content")))
            "todowrite" -> AgentHost.Entry.Used(id, "TodoWrite", "", todos(input.optJSONArray("todos")))
            else -> AgentHost.Entry.Used(id, toolName(name), detail(input), if (input.length() == 0) "" else input.toString(2))
        }
    }

    private fun detail(input: JSONObject): String {
        for (key in listOf("filePath", "path", "pattern", "url", "query", "description", "prompt", "command")) {
            input.optString(key).takeIf { it.isNotEmpty() }?.let { return firstLine(it) }
        }
        return ""
    }

    private fun todos(list: JSONArray?) = (0 until (list?.length() ?: 0)).mapNotNull { i ->
        val todo = list?.optJSONObject(i) ?: return@mapNotNull null
        val mark = when (todo.optString("status")) { "completed" -> "[x]"; "in_progress" -> "[~]"; else -> "[ ]" }
        "$mark ${todo.optString("content")}"
    }.joinToString("\n")
}

@VisibleForTesting
internal fun firstLine(text: String) = text.lineSequence().first().take(160)

/**
 * [command] with its stderr in a log of its own under /root/.cache/droiddeck. The agent's stdout and stderr share one
 * pipe, and a log line written in the middle of a long JSON line would make that line unreadable.
 */
internal fun withoutStderr(log: String, command: List<String>) =
    listOf("/bin/sh", "-c", "mkdir -p /root/.cache/droiddeck; exec \"\$@\" 2>/root/.cache/droiddeck/$log.log", "sh") + command

private const val DIFF_CONTEXT = 2

/**
 * [old] against [new], one line per changed or nearby line, each led by ' ', '-' or '+'. Edits
 * replace a short span, so the lines both share at the start and end are cut to [DIFF_CONTEXT].
 */
@VisibleForTesting
internal fun lineDiff(old: String, new: String): String {
    val a = if (old.isEmpty()) emptyList() else old.removeSuffix("\n").lines()
    val b = if (new.isEmpty()) emptyList() else new.removeSuffix("\n").lines()
    var head = 0
    while (head < a.size && head < b.size && a[head] == b[head]) head++
    var tail = 0
    while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
    return buildList {
        a.subList(maxOf(0, head - DIFF_CONTEXT), head).forEach { add(" $it") }
        a.subList(head, a.size - tail).forEach { add("-$it") }
        b.subList(head, b.size - tail).forEach { add("+$it") }
        a.subList(a.size - tail, minOf(a.size, a.size - tail + DIFF_CONTEXT)).forEach { add(" $it") }
    }.joinToString("\n")
}

/** A unified diff's hunks without their headers; an added or deleted file's diff is its whole content. */
@VisibleForTesting
internal fun patchLines(diff: String, kind: String): String {
    val lines = diff.removeSuffix("\n").lines()
    when (kind) {
        "add" -> return lines.joinToString("\n") { "+$it" }
        "delete" -> return lines.joinToString("\n") { "-$it" }
    }
    val start = lines.indexOfFirst { it.startsWith("@@") }
    return (if (start < 0) lines else lines.drop(start)).filter { it.isNotEmpty() && !it.startsWith("@@") && !it.startsWith("\\") }.joinToString("\n")
}

private val SHELL_WRAPPER = Regex("^/bin/(?:ba)?sh -l?c '(.*)'$", RegexOption.DOT_MATCHES_ALL)

/** Codex runs each command as `/bin/bash -lc '<command>'`; the command is what the user wants to read. */
@VisibleForTesting
internal fun shellCommand(command: String) = SHELL_WRAPPER.find(command)?.groupValues?.get(1)?.replace("'\''", "'") ?: command
