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
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * The launcher's agent: a coding agent CLI ([AgentHarness]) from the Linux userland, run headless
 * one process per turn, outside any game or desktop session. The conversation id and transcript
 * are kept on disk, so a restart (a Lab build, a crash) continues the same conversation.
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
    private const val WORKSPACE = "/root/DroidDeck"

    val entries = mutableStateListOf<Entry>()
    var state by mutableStateOf(State.IDLE)
        private set
    var installed by mutableStateOf(false)
        private set
    /** The Linux runtime is there, so the agent can be set up. */
    var available by mutableStateOf(false)
        private set
    var harness by mutableStateOf<AgentHarness>(ClaudeCode)
        private set
    /** A reply finished since the conversation was last on screen. */
    var unseen by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var loaded = false

    fun seen() {
        unseen = false
    }

    fun refresh(context: Context) {
        harness = AgentHarness.byId(prefs(context).getString(HARNESS_KEY, null))
        available = LinuxRuntime.isInstalled(context)
        installed = guestFileExists(context, harness.binary)
        if (!installed) state = State.MISSING else if (state == State.MISSING) state = State.IDLE
        if (!loaded) {
            loaded = true
            entries.addAll(readTranscript(transcript(context)))
        }
    }

    fun choose(context: Context, choice: AgentHarness) {
        if (choice == harness || state == State.WORKING) return
        prefs(context).edit().putString(HARNESS_KEY, choice.id).remove(CONVERSATION_KEY).apply()
        refresh(context)
    }

    fun send(context: Context, text: String) {
        val app = context.applicationContext
        val message = text.trim()
        if (message.isEmpty() || state != State.IDLE) return
        record(app, Entry.Asked(message))
        state = State.WORKING
        AgentHostService.start(app)
        val current = harness
        val conversation = prefs(app).getString(CONVERSATION_KEY, null)
        val workdir = workspace(app)
        Thread({
            val child = try {
                guest(app, current.turn(message, conversation), workdir).start()
            } catch (e: Exception) {
                Log.w(TAG, "could not start ${current.name}", e)
                main.post { finish(app, e.message ?: "could not start") }
                return@Thread
            }
            main.post { process = child }
            child.outputStream.close()
            var ended = false
            runCatching {
                child.inputStream.bufferedReader().forEachLine { line ->
                    val event = current.parse(line) ?: return@forEachLine
                    if (event.entries.any { it is Entry.Finished }) ended = true
                    main.post { apply(app, child, event) }
                }
            }
            val status = runCatching { child.waitFor() }.getOrDefault(-1)
            main.post {
                if (process === child) {
                    process = null
                    if (state == State.WORKING && !ended) finish(app, "${current.name} stopped (exit $status)")
                }
            }
        }, "agent-output").start()
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    fun forget(context: Context) {
        stop()
        entries.clear()
        transcript(context).delete()
        prefs(context).edit().remove(CONVERSATION_KEY).apply()
        state = if (installed) State.IDLE else State.MISSING
    }

    /**
     * A process for [command] in the Linux userland, run the way the store helpers run theirs:
     * proot over the installed rootfs, without a session. Loopback adb is connected first so
     * droiddeckctl reaches the app.
     */
    fun guest(app: Context, command: List<String>, workdir: String = "/root"): ProcessBuilder {
        val adb = AgentAdb.prepare(app)
        val env = listOf(
            "/usr/bin/env", "HOME=/root", "PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8",
            "ADB_SERIAL=${adb.serial}", "ADB_VENDOR_KEYS=${adb.vendorKeys}",
            "BROWSER=/root/.local/bin/android-open", "DD_BROWSER_DISPLAY=${AgentAdb.browserDisplay}",
        )
        val shell = listOf(
            "/bin/sh", "-c",
            "adb connect \"\$ADB_SERIAL\" >/dev/null 2>&1; cd \"$1\" 2>/dev/null; shift; exec \"$@\"", "sh", workdir,
        )
        val proot = LinuxRuntime.command(app, null, null, Environment.getExternalStorageDirectory(), env + shell + command)
        return ProcessBuilder(proot).redirectErrorStream(true).apply {
            environment().apply {
                remove("LD_PRELOAD"); remove("LD_AUDIT"); remove("LD_LIBRARY_PATH")
                put("PROOT_LOADER", LinuxRuntime.prootLoader(app).path)
                put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(app).path)
                if (SessionPrefs.prootNoSeccomp(app)) put("PROOT_NO_SECCOMP", "1")
                LinuxRuntime.prootLibraryPath(app).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
            }
        }
    }

    fun guestFileExists(context: Context, path: String): Boolean {
        val file = File(LinuxRuntime.rootDir(context), path.removePrefix("/"))
        return file.exists() || Files.isSymbolicLink(file.toPath())
    }

    private fun workspace(app: Context) = if (guestFileExists(app, WORKSPACE)) WORKSPACE else "/root"

    private fun apply(app: Context, child: Process, event: AgentEvent) {
        if (process !== child) return
        event.conversation?.let { prefs(app).edit().putString(CONVERSATION_KEY, it).apply() }
        event.entries.forEach { if (it is Entry.Finished) finish(app, it.error) else record(app, it) }
    }

    private fun finish(app: Context, error: String?) {
        if (state != State.WORKING) return
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

    private const val CONVERSATION_KEY = "session_id"
    private const val HARNESS_KEY = "harness"

    internal fun prefs(context: Context) = context.getSharedPreferences("agent", Context.MODE_PRIVATE)

    private fun transcript(context: Context) = File(context.filesDir, "agent/transcript.jsonl")

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
