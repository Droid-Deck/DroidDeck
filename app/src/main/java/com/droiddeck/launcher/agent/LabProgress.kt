package com.droiddeck.launcher.agent

import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.File

/** A Lab build in progress, as dd-lab-build reports it in `~/.cache/dd-lab-build.json`. */
data class LabProgress(
    val stage: String, val prepare: Boolean, val permille: Int, val task: String,
    val done: Int, val total: Int, val etaSeconds: Int, val elapsedSeconds: Int, val pid: Int,
) {
    val running get() = stage in RUNNING
    val failed get() = stage == "failed"

    companion object {
        private val RUNNING = setOf("checking", "fetching", "sdk", "planning", "building", "packaging", "restarting")
        private const val PATH = "root/.cache/dd-lab-build.json"
        /** A finished build stays on screen this long. */
        private const val SHOW_DONE_MS = 60_000L

        /** The build, while it runs and shortly after; null when there is none to show. */
        fun read(rootfs: File): LabProgress? {
            val file = File(rootfs, PATH)
            if (!file.isFile) return null
            val progress = parse(runCatching { file.readText() }.getOrNull() ?: return null) ?: return null
            if (progress.running) return progress.takeIf { AgentProcesses.alive(it.pid) } ?: progress.copy(stage = "failed", task = "stopped")
            return progress.takeIf { System.currentTimeMillis() - file.lastModified() < SHOW_DONE_MS }
        }

        /** Ends the build and Gradle's daemon, which keeps compiling after its client is gone. */
        fun cancel(rootfs: File, progress: LabProgress) {
            AgentProcesses.killTree(progress.pid)
            AgentProcesses.find("GradleDaemon").forEach(AgentProcesses::killTree)
            runCatching {
                File(rootfs, PATH).writeText(JSONObject().put("stage", "failed").put("task", "stopped").put("pid", 0).toString())
            }
        }

        @VisibleForTesting
        internal fun parse(json: String): LabProgress? = runCatching {
            val o = JSONObject(json)
            LabProgress(
                o.getString("stage"), o.optString("mode") == "prepare", o.optInt("permille"), o.optString("task"),
                o.optInt("done"), o.optInt("total"), o.optInt("eta", -1), o.optInt("elapsed"), o.optInt("pid"),
            )
        }.getOrNull()
    }
}
