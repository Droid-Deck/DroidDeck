package com.droiddeck.launcher.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * First-run setup for the agent, as steps with a check and an action each. The first three are
 * needed to talk to the agent; GitHub and the build tools are only needed to open pull requests
 * and to make Lab builds on the device.
 */
object AgentSetup {
    enum class Step(val required: Boolean) { CONNECTION(true), INSTALL(true), SIGN_IN(true), GITHUB(false), BUILD_TOOLS(false), PREPARE(false) }
    enum class Status { UNKNOWN, DONE, TODO, WORKING, FAILED }

    val status = mutableStateMapOf<Step, Status>()
    /** The last line of output from the step that is running or just failed. */
    val detail = mutableStateMapOf<Step, String>()
    var runtimeInstalled by mutableStateOf(true)
        private set

    val ready: Boolean get() = Step.entries.filter { it.required }.all { status[it] == Status.DONE }

    /** Set once the required steps have all passed, so the chat opens without waiting for the checks. */
    fun completed(context: Context) = AgentHost.prefs(context).getBoolean(completedKey(), false)

    private fun completedKey() = "setup_completed_${AgentHost.harness.id}"

    private val main = Handler(Looper.getMainLooper())
    private const val REPO = "https://github.com/Droid-Deck/DroidDeck.git"
    private const val CHECKOUT = "/root/DroidDeck"
    private val HELPERS = listOf("android-open", "dd-gh-login")

    fun check(context: Context) {
        val app = context.applicationContext
        runtimeInstalled = LinuxRuntime.isInstalled(app)
        if (!runtimeInstalled) return
        AgentHost.refresh(app)
        val harness = AgentHost.harness
        val probes = mutableListOf<Pair<Step, List<String>>>()
        for (step in Step.entries) if (status[step] != Status.WORKING) {
            when (step) {
                Step.INSTALL -> set(step, if (AgentHost.guestFileExists(app, harness.binary)) Status.DONE else Status.TODO)
                Step.BUILD_TOOLS -> set(step, if (buildToolsInstalled(app)) Status.DONE else Status.TODO)
                Step.PREPARE -> set(step, if (prepared(app)) Status.DONE else Status.TODO)
                Step.CONNECTION -> probes += step to listOf("sh", "-c",
                    "for i in 1 2 3; do adb connect \"\$ADB_SERIAL\" >/dev/null 2>&1; " +
                        "adb -s \"\$ADB_SERIAL\" get-state 2>&1 | tee /dev/stderr | grep -q device && exit 0; sleep 1; done; exit 1")
                Step.SIGN_IN -> if (AgentHost.guestFileExists(app, harness.binary)) probes += step to harness.signedIn else set(step, Status.TODO)
                Step.GITHUB -> probes += step to listOf("gh", "auth", "status")
            }
        }
        Thread({ for ((step, command) in probes) probe(app, step, command) }, "agent-checks").start()
    }

    /** Runs [step]'s action. [CONNECTION] is the app's own pairing flow, so the caller opens it. */
    fun run(context: Context, step: Step) {
        val app = context.applicationContext
        val harness = AgentHost.harness
        val command = when (step) {
            Step.CONNECTION -> return
            Step.INSTALL -> {
                listOf("sh", "-c", "set -e; export DEBIAN_FRONTEND=noninteractive; " +
                    "apt-get install -y --no-install-recommends git gh adb curl ca-certificates python3; ${harness.install}")
            }
            Step.SIGN_IN -> harness.signIn
            Step.GITHUB -> listOf("dd-gh-login")
            Step.BUILD_TOOLS -> listOf("sh", "-c", "set -e; [ -d $CHECKOUT/.git ] || git clone --filter=blob:none $REPO $CHECKOUT; " +
                "cd $CHECKOUT; [ -x tools/agent/setup ] || { echo 'This DroidDeck version has no tools/agent/setup yet'; exit 1; }; tools/agent/setup")
            Step.PREPARE -> listOf("sh", "-c", "cd $CHECKOUT && dd-lab-build --prepare")
        }
        set(step, Status.WORKING)
        detail.remove(step)
        AgentHostService.setupStarted(app)
        Thread({
            val result = runCatching {
                if (step == Step.INSTALL) installHelpers(app)
                val child = AgentHost.guest(app, command).start()
                child.outputStream.close()
                child.inputStream.bufferedReader().forEachLine { line ->
                    val text = line.trim()
                    if (text.isNotEmpty()) main.post { detail[step] = text.take(160) }
                }
                child.waitFor()
            }
            main.post {
                AgentHostService.setupEnded(app)
                if (result.getOrNull() == 0) {
                    detail.remove(step)
                    status[step] = Status.UNKNOWN
                    check(app)
                } else {
                    status[step] = Status.FAILED
                    if (result.isFailure) detail[step] = result.exceptionOrNull()?.message ?: "failed"
                }
            }
        }, "agent-setup-${step.name.lowercase()}").start()
    }

    fun buildToolsInstalled(app: Context) = AgentHost.guestFileExists(app, "/root/aapt2/aapt2") && AgentHost.guestFileExists(app, "$CHECKOUT/.git")

    /** dd-lab-build compiled this install's commit, so a Lab build compiles only what changed. */
    private fun prepared(app: Context) = runCatching {
        File(LinuxRuntime.rootDir(app), "root/.cache/dd-lab-build-base").readText().trim()
            .startsWith(com.droiddeck.launcher.lab.LabOverlay.apkCommit.take(7))
    }.getOrDefault(false)

    /** Drops what the checks found, which belonged to the previous harness. */
    fun forget() {
        status.clear()
        detail.clear()
    }

    private fun probe(app: Context, step: Step, command: List<String>) {
        var output = ""
        val ok = runCatching {
            val child = AgentHost.guest(app, command, connectAdb = step == Step.CONNECTION).start()
            child.outputStream.close()
            output = child.inputStream.bufferedReader().readText()
            child.waitFor(60, TimeUnit.SECONDS) && child.exitValue() == 0
        }.onFailure { output = it.message.orEmpty() }.getOrDefault(false)
        main.post {
            if (status[step] != Status.WORKING) status[step] = if (ok) Status.DONE else Status.TODO
            val last = output.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(160)
            if (!ok && step.required && last != null) detail[step] = last else if (ok) detail.remove(step)
            if (ready) AgentHost.prefs(app).edit().putBoolean(completedKey(), true).apply()
        }
    }

    private fun set(step: Step, value: Status) {
        if (Looper.myLooper() == Looper.getMainLooper()) status[step] = value else main.post { status[step] = value }
    }

    private fun installHelpers(app: Context) {
        val bin = File(LinuxRuntime.rootDir(app), "root/.local/bin").apply { mkdirs() }
        for (name in HELPERS) {
            val target = File(bin, name)
            app.assets.open("agent/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            target.setExecutable(true, false)
        }
    }
}
