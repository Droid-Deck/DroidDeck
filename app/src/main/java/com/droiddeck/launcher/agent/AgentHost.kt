package com.droiddeck.launcher.agent

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.nio.file.Files

/**
 * The launcher's agent: a coding agent CLI ([AgentHarness]) from the Linux userland, run headless
 * one process per turn, outside any game or desktop session. Conversations are kept on disk
 * ([AgentChats]), so a restart (a Lab build, a crash) continues the same one.
 */
object AgentHost {
    sealed interface Entry {
        data class Asked(val text: String, val files: List<String> = emptyList()) : Entry
        data class Said(val text: String) : Entry
        data class Thought(val text: String) : Entry
        /** A tool call; [output] stays null until its result arrives. */
        data class Used(val id: String, val tool: String, val detail: String, val input: String = "", val output: String? = null, val failed: Boolean = false) : Entry
        data class Result(val id: String, val output: String, val failed: Boolean) : Entry
        data class Finished(val error: String?, val stopped: Boolean = false, val usage: Usage? = null) : Entry
    }

    data class Usage(val durationMs: Long, val costUsd: Double, val inputTokens: Long, val outputTokens: Long)

    /** A message typed while the agent was working, sent when the turn ends. */
    data class Queued(val text: String, val files: List<String>)

    enum class State { MISSING, IDLE, WORKING }

    private const val TAG = "AgentHost"
    private const val WORKSPACE = "/root/DroidDeck"
    private const val TICK_MS = 1_000L
    private const val IDLE_TICK_MS = 5_000L

    /** Where the agent runs and what the user sees, which no file in the checkout tells it. */
    @VisibleForTesting
    internal const val BRIEF = "You run inside DroidDeck on the user's handheld, in its Linux userland (Ubuntu under proot, aarch64), " +
        "and the user talks to you from DroidDeck's Agent tab, often on a small screen, so keep replies short. " +
        "droiddeckctl (in the /root/DroidDeck checkout, tools/droiddeckctl) inspects and drives the app over loopback adb (\$ADB_SERIAL). " +
        "dd-lab-build builds the checkout as a Lab build and restarts DroidDeck on it (docs/lab-builds.md); the app shows the user its " +
        "progress and time left, so start it in the background and check on it instead of waiting in one long command. " +
        "The restart ends this process and the conversation continues afterwards. " +
        "Files the user attaches are listed at the end of their message, under /root/.cache/droiddeck/attachments."

    val entries = mutableStateListOf<Entry>()
    val queue = mutableStateListOf<Queued>()
    var state by mutableStateOf(State.IDLE)
        private set
    var installed by mutableStateOf(false)
        private set
    /** The Linux runtime is there, so the agent can be set up. */
    var available by mutableStateOf(false)
        private set
    var harness by mutableStateOf<AgentHarness>(ClaudeCode)
        private set
    /** The chosen model for [harness]; null is the CLI's default. */
    var model by mutableStateOf<String?>(null)
        private set
    var plan by mutableStateOf(false)
        private set
    var chat by mutableStateOf<String?>(null)
        private set
    /** A reply finished since the conversation was last on screen. */
    var unseen by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    /** Text and thinking of the reply being written. */
    var live by mutableStateOf("")
        private set
    var liveThinking by mutableStateOf("")
        private set
    /** When the current turn started, on [SystemClock.elapsedRealtime]. */
    var turnStarted by mutableLongStateOf(0L)
        private set
    var now by mutableLongStateOf(0L)
        private set
    /** Tokens in the conversation's context after its last step. */
    var context by mutableLongStateOf(0L)
        private set
    var lab by mutableStateOf<LabProgress?>(null)
        private set

    private class Turn(val process: Process, val pid: Int)

    private val main = Handler(Looper.getMainLooper())
    private var turn: Turn? = null
    /** Counts turns, so a process that starts after its turn was stopped is not adopted by the next one. */
    private var generation = 0
    private var conversation: String? = null
    private var loaded = false
    private var appContext: Context? = null

    val turnPid: Int get() = turn?.pid ?: 0

    fun seen() {
        unseen = false
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        appContext = app
        harness = AgentHarness.byId(prefs(app).getString(HARNESS_KEY, null))
        model = prefs(app).getString(modelKey(harness), null)
        plan = prefs(app).getBoolean(PLAN_KEY, false)
        available = LinuxRuntime.isInstalled(app)
        installed = guestFileExists(app, harness.binary)
        if (!installed) state = State.MISSING else if (state == State.MISSING) state = State.IDLE
        if (!loaded) {
            loaded = true
            val migrated = AgentChats.migrate(app, harness.id, prefs(app).getString(LEGACY_CONVERSATION_KEY, null))
            if (migrated != null) prefs(app).edit().remove(LEGACY_CONVERSATION_KEY).putString(CHAT_KEY, migrated).apply()
            prefs(app).getString(CHAT_KEY, null)?.let { load(app, it) }
            resumeAfterRestart(app)
            tick()
        }
    }

    private fun load(app: Context, id: String) {
        val file = AgentChats.file(app, id)
        if (!file.isFile) return
        val loaded = AgentChats.read(file)
        chat = id
        conversation = loaded.conversation
        entries.clear()
        entries.addAll(loaded.entries)
        context = 0
    }

    /** Opens a kept conversation, switching to the CLI it belongs to. */
    fun open(context: Context, id: String) {
        val app = context.applicationContext
        if (state == State.WORKING || id == chat) return
        val harnessId = AgentChats.read(AgentChats.file(app, id)).harness
        if (harnessId != null && harnessId != harness.id) {
            prefs(app).edit().putString(HARNESS_KEY, harnessId).apply()
            AgentSetup.forget()
            refresh(app)
        }
        load(app, id)
        prefs(app).edit().putString(CHAT_KEY, id).apply()
    }

    fun delete(context: Context, id: String) {
        if (id == chat) {
            if (state == State.WORKING) return
            forget(context)
        }
        AgentChats.delete(context.applicationContext, id)
    }

    /**
     * A Lab build restarts the app, which ends the agent's process mid-turn. dd-lab-build leaves
     * [RESUME] when the app's own agent ran it, so the same conversation picks up on the new build.
     */
    private fun resumeAfterRestart(app: Context) {
        val marker = File(app.filesDir, RESUME)
        val claimed = File(app.filesDir, "$RESUME.claimed")
        if (!marker.renameTo(claimed)) return
        claimed.delete()
        if (!installed || !interrupted(entries)) return
        send(app, app.getString(R.string.agent_resumed))
    }

    /** True when DroidDeck restarted in the middle of one of the agent's turns. */
    fun resuming(context: Context) = File(context.applicationContext.filesDir, RESUME).isFile

    @VisibleForTesting
    internal fun interrupted(entries: List<Entry>) = entries.isNotEmpty() && entries.last() !is Entry.Finished

    /** Switches the agent CLI; a conversation belongs to one CLI, so a new one starts. */
    fun choose(context: Context, choice: AgentHarness) {
        if (choice == harness || state == State.WORKING) return
        forget(context)
        AgentSetup.forget()
        prefs(context).edit().putString(HARNESS_KEY, choice.id).apply()
        refresh(context)
    }

    fun chooseModel(context: Context, id: String?) {
        model = id
        prefs(context).edit().putString(modelKey(harness), id).apply()
    }

    fun choosePlan(context: Context, on: Boolean) {
        plan = on
        prefs(context).edit().putBoolean(PLAN_KEY, on).apply()
    }

    fun models(context: Context) = harness.models(LinuxRuntime.rootDir(context.applicationContext))

    /** Sends [text] now, or after the current turn when the agent is working. */
    fun send(context: Context, text: String, files: List<String> = emptyList()) {
        val app = context.applicationContext
        val message = text.trim()
        if (message.isEmpty() && files.isEmpty()) return
        if (state == State.WORKING) {
            queue += Queued(message, files)
            return
        }
        if (state != State.IDLE) return
        val id = chat ?: AgentChats.newId(app).also { id ->
            AgentChats.start(AgentChats.file(app, id), harness.id)
            chat = id
            conversation = null
            prefs(app).edit().putString(CHAT_KEY, id).apply()
        }
        record(app, Entry.Asked(message, files))
        state = State.WORKING
        val gen = ++generation
        live = ""
        liveThinking = ""
        turnStarted = SystemClock.elapsedRealtime()
        AgentHostService.start(app)
        val current = harness
        val prompt = AgentAttachments.prompt(message, files)
        val command = current.turn(prompt, conversation, AgentHarness.Options(model, plan && current.plans, BRIEF))
        val workdir = workspace(app)
        Thread({
            val child = try {
                guest(app, command, workdir).start()
            } catch (e: Exception) {
                Log.w(TAG, "could not start ${current.name}", e)
                main.post { if (generation == gen) finish(app, e.message ?: "could not start") }
                return@Thread
            }
            val started = Turn(child, AgentProcesses.pidOf(child))
            main.post { if (generation == gen && state == State.WORKING && turn == null) turn = started else AgentProcesses.killTree(started.pid) }
            child.outputStream.close()
            var ended = false
            runCatching {
                child.inputStream.bufferedReader().forEachLine { line ->
                    val event = current.parse(line) ?: return@forEachLine
                    if (event.entries.any { it is Entry.Finished }) ended = true
                    main.post { if (turn === started) apply(app, event) }
                }
            }
            val status = runCatching { child.waitFor() }.getOrDefault(-1)
            main.post {
                if (turn === started) {
                    turn = null
                    if (state == State.WORKING && !ended) finish(app, app.getString(R.string.agent_exited, current.name, status))
                }
            }
        }, "agent-output").start()
    }

    /** Ends the turn now: its process and everything it started, a build included. */
    fun stop(context: Context) {
        val app = context.applicationContext
        val running = turn
        turn = null
        queue.clear()
        if (running != null) {
            AgentProcesses.killTree(running.pid)
            running.process.destroy()
        }
        if (state == State.WORKING) finish(app, null, stopped = true)
    }

    fun unqueue(item: Queued) {
        queue.remove(item)
    }

    /** Starts a new conversation; the current one stays in the history. */
    fun forget(context: Context) {
        stop(context)
        entries.clear()
        chat = null
        conversation = null
        this.context = 0
        prefs(context).edit().remove(CHAT_KEY).apply()
        state = if (installed) State.IDLE else State.MISSING
    }

    /**
     * A process for [command] in the Linux userland, run the way the store helpers run theirs:
     * proot over the installed rootfs, without a session. Loopback adb is connected first so
     * droiddeckctl reaches the app.
     */
    fun guest(app: Context, command: List<String>, workdir: String = "/root", connectAdb: Boolean = true): ProcessBuilder {
        val adb = AgentAdb.prepare(app)
        val env = listOf(
            "/usr/bin/env", "HOME=/root", "PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8",
            "ADB_SERIAL=${adb.serial}", "ADB_VENDOR_KEYS=${adb.vendorKeys}",
            "DD_AGENT=1", "BROWSER=/root/.local/bin/android-open", "DD_BROWSER_DISPLAY=${AgentAdb.browserDisplay}",
        )
        val shell = listOf(
            "/bin/sh", "-c",
            // proot ends the adb server with the command, so each command that needs adb connects it.
            (if (connectAdb) "adb start-server >/dev/null 2>&1; adb connect \"\$ADB_SERIAL\" >/dev/null 2>&1; " else "") +
                "cd \"$1\" 2>/dev/null; shift; exec \"$@\"", "sh", workdir,
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

    private fun apply(app: Context, event: AgentEvent) {
        event.conversation?.takeIf { it != conversation }?.let { id ->
            conversation = id
            chat?.let { AgentChats.appendConversation(AgentChats.file(app, it), id) }
        }
        event.text?.let { live += it }
        event.thinking?.let { liveThinking += it }
        event.context?.let { context = it }
        for (entry in event.entries) when (entry) {
            is Entry.Finished -> finish(app, entry.error, usage = event.usage)
            else -> {
                if (entry is Entry.Said) live = ""
                if (entry is Entry.Thought) liveThinking = ""
                record(app, entry)
            }
        }
        AgentHostService.update(app)
    }

    private fun finish(app: Context, error: String?, stopped: Boolean = false, usage: Usage? = null) {
        if (state != State.WORKING) return
        turn = null
        live = ""
        liveThinking = ""
        record(app, Entry.Finished(error, stopped, usage))
        state = State.IDLE
        unseen = true
        failed = error != null
        AgentHostService.stop(app)
        if (!stopped && queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            send(app, next.text, next.files)
        }
    }

    private fun record(app: Context, entry: Entry) {
        if (!AgentChats.merge(entries, entry)) return
        chat?.let { AgentChats.append(AgentChats.file(app, it), entry) }
    }

    /** The tool call still running in this turn, if any. */
    fun running(): Entry.Used? {
        if (state != State.WORKING) return null
        val start = entries.indexOfLast { it is Entry.Asked }
        return entries.withIndex().lastOrNull { (i, e) -> i > start && e is Entry.Used && e.output == null }?.value as? Entry.Used
    }

    /** The latest todo list the agent wrote in this conversation. */
    fun todos(): String? = entries.lastOrNull { it is Entry.Used && it.tool == "TodoWrite" }?.let { (it as Entry.Used).input }

    private fun tick() {
        now = SystemClock.elapsedRealtime()
        appContext?.let { app ->
            lab = LabProgress.read(LinuxRuntime.rootDir(app))
            if (state == State.WORKING) AgentHostService.update(app)
        }
        main.postDelayed(::tick, if (state == State.WORKING || lab != null) TICK_MS else IDLE_TICK_MS)
    }

    fun cancelLabBuild(context: Context) {
        val progress = lab ?: return
        LabProgress.cancel(LinuxRuntime.rootDir(context.applicationContext), progress)
        lab = LabProgress.read(LinuxRuntime.rootDir(context.applicationContext))
    }

    private const val LEGACY_CONVERSATION_KEY = "session_id"
    private const val HARNESS_KEY = "harness"
    private const val CHAT_KEY = "chat"
    private const val PLAN_KEY = "plan"
    private const val RESUME = "agent/resume"

    private fun modelKey(harness: AgentHarness) = "model_${harness.id}"

    internal fun prefs(context: Context) = context.getSharedPreferences("agent", Context.MODE_PRIVATE)
}
