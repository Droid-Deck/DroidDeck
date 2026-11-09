package com.droiddeck.launcher.agent

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer
import java.nio.file.Files

/**
 * The launcher's agent: Claude Code from the Linux userland, run headless with stream-json on
 * stdin and stdout, outside any game or desktop session. The conversation id and transcript are
 * kept on disk, so a restart (a Lab build, a crash) continues the same conversation.
 */
object AgentHost {
    sealed interface Entry {
        data class Asked(val text: String) : Entry
        data class Said(val text: String) : Entry
        data class Used(val tool: String, val detail: String) : Entry
        data class Finished(val error: String?) : Entry
    }

    enum class State { MISSING, IDLE, WORKING }

    private const val TAG = "AgentHost"
    private const val CLAUDE = "/root/.local/bin/claude"
    private const val WORKSPACE = "/root/DroidDeck"

    val entries = mutableStateListOf<Entry>()
    var state by mutableStateOf(State.IDLE)
        private set
    var installed by mutableStateOf(false)
        private set
    /** A reply finished since the conversation was last on screen. */
    var unseen by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun seen() {
        unseen = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var input: Writer? = null
    private var loaded = false

    fun refresh(context: Context) {
        val claude = File(LinuxRuntime.rootDir(context), CLAUDE.removePrefix("/"))
        installed = claude.exists() || Files.isSymbolicLink(claude.toPath())
        if (!installed) state = State.MISSING else if (state == State.MISSING) state = State.IDLE
        if (!loaded) {
            loaded = true
            entries.addAll(readTranscript(transcript(context)))
        }
    }

    fun send(context: Context, text: String) {
        val app = context.applicationContext
        val message = text.trim()
        if (message.isEmpty() || state != State.IDLE) return
        val writer = input?.takeIf { process?.isAlive == true } ?: start(app) ?: return
        record(app, Entry.Asked(message))
        state = State.WORKING
        AgentHostService.start(app)
        Thread({
            runCatching {
                writer.write(userMessage(message))
                writer.write("\n")
                writer.flush()
            }.onFailure { e ->
                Log.w(TAG, "could not reach the agent", e)
                main.post { finish(app, e.message ?: "agent stopped") }
            }
        }, "agent-send").start()
    }

    fun stop() {
        process?.destroy()
        process = null
        input = null
    }

    fun forget(context: Context) {
        stop()
        entries.clear()
        transcript(context).delete()
        prefs(context).edit().remove(SESSION_KEY).apply()
        state = if (installed) State.IDLE else State.MISSING
    }

    private fun start(app: Context): Writer? {
        val workspace = if (File(LinuxRuntime.rootDir(app), WORKSPACE.removePrefix("/")).isDirectory) WORKSPACE else "/root"
        val resume = prefs(app).getString(SESSION_KEY, null)
        val claude = buildList {
            addAll(listOf(CLAUDE, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"))
            if (resume != null) addAll(listOf("--resume", resume))
        }
        val guest = listOf(
            "/usr/bin/env", "HOME=/root", "PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin",
            "ADB_SERIAL=127.0.0.1:5555", "BROWSER=/root/.local/bin/android-open",
            // A fresh adb server lists the device's own adbd as emulator-5554; droiddeckctl needs the serial connected.
            "/bin/sh", "-c", "adb connect \"\$ADB_SERIAL\" >/dev/null 2>&1; cd \"$1\" && shift && exec \"$@\"", "sh", workspace,
        ) + claude
        val command = LinuxRuntime.command(app, null, null, Environment.getExternalStorageDirectory(), guest)
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().apply {
            remove("LD_PRELOAD"); remove("LD_AUDIT"); remove("LD_LIBRARY_PATH")
            put("PROOT_LOADER", LinuxRuntime.prootLoader(app).path)
            put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(app).path)
            if (SessionPrefs.prootNoSeccomp(app)) put("PROOT_NO_SECCOMP", "1")
            LinuxRuntime.prootLibraryPath(app).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
        }
        val child = try {
            builder.start()
        } catch (e: Exception) {
            Log.w(TAG, "could not start the agent", e)
            record(app, Entry.Finished(e.message ?: "could not start"))
            return null
        }
        process = child
        val writer = child.outputStream.bufferedWriter()
        input = writer
        Thread({
            runCatching {
                child.inputStream.bufferedReader().forEachLine { line ->
                    val event = parse(line) ?: return@forEachLine
                    main.post { apply(app, child, event) }
                }
            }
            main.post {
                if (process === child) {
                    process = null
                    input = null
                    if (state == State.WORKING) finish(app, "agent stopped")
                }
            }
        }, "agent-output").start()
        return writer
    }

    private fun apply(app: Context, child: Process, event: Event) {
        if (process !== child) return
        event.session?.let { prefs(app).edit().putString(SESSION_KEY, it).apply() }
        event.entries.forEach { if (it is Entry.Finished) finish(app, it.error) else record(app, it) }
    }

    private fun finish(app: Context, error: String?) {
        record(app, Entry.Finished(error))
        state = State.IDLE
        unseen = true
        failed = error != null
        AgentHostService.stop(app)
    }

    private fun record(app: Context, entry: Entry) {
        entries += entry
        runCatching { transcript(app).apply { parentFile?.mkdirs() }.appendText(encode(entry) + "\n") }
    }

    private const val SESSION_KEY = "session_id"

    private fun prefs(context: Context) = context.getSharedPreferences("agent", Context.MODE_PRIVATE)

    private fun transcript(context: Context) = File(context.filesDir, "agent/transcript.jsonl")

    @VisibleForTesting
    internal data class Event(val session: String?, val entries: List<Entry>)

    @VisibleForTesting
    internal fun userMessage(text: String): String = JSONObject()
        .put("type", "user")
        .put("message", JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
        .toString()

    /** One stream-json line from Claude Code, or null when it carries nothing to show. */
    @VisibleForTesting
    internal fun parse(line: String): Event? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val session = o.optString("session_id").takeIf { it.isNotEmpty() }
        val entries = when (o.optString("type")) {
            "assistant" -> {
                val content = o.optJSONObject("message")?.optJSONArray("content") ?: JSONArray()
                (0 until content.length()).mapNotNull { i ->
                    val block = content.optJSONObject(i) ?: return@mapNotNull null
                    when (block.optString("type")) {
                        "text" -> block.optString("text").trim().takeIf { it.isNotEmpty() }?.let { Entry.Said(it) }
                        "tool_use" -> Entry.Used(block.optString("name"), toolDetail(block.optJSONObject("input")))
                        else -> null
                    }
                }
            }
            "result" -> listOf(Entry.Finished(
                if (o.optBoolean("is_error")) o.optString("result").ifEmpty { o.optString("subtype") } else null,
            ))
            else -> emptyList()
        }
        return if (session == null && entries.isEmpty()) null else Event(session, entries)
    }

    private fun toolDetail(input: JSONObject?): String {
        input ?: return ""
        for (key in listOf("command", "file_path", "pattern", "url", "description")) {
            input.optString(key).takeIf { it.isNotEmpty() }?.let { return it.lineSequence().first().take(160) }
        }
        return ""
    }

    @VisibleForTesting
    internal fun encode(entry: Entry): String = when (entry) {
        is Entry.Asked -> JSONObject().put("asked", entry.text)
        is Entry.Said -> JSONObject().put("said", entry.text)
        is Entry.Used -> JSONObject().put("used", entry.tool).put("detail", entry.detail)
        is Entry.Finished -> JSONObject().put("finished", true).put("error", entry.error ?: JSONObject.NULL)
    }.toString()

    @VisibleForTesting
    internal fun readTranscript(file: File): List<Entry> = runCatching {
        file.readLines().mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            when {
                o.has("asked") -> Entry.Asked(o.getString("asked"))
                o.has("said") -> Entry.Said(o.getString("said"))
                o.has("used") -> Entry.Used(o.getString("used"), o.optString("detail"))
                o.has("finished") -> Entry.Finished(if (o.isNull("error")) null else o.optString("error"))
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}
