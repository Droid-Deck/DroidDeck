package com.droiddeck.launcher

import android.os.Handler
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.ui.ProtonRow
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.frontend.AutoComponents
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller

internal class ProtonMenu(
    private val activity: android.app.Activity,
    private val ui: Handler,
    private val idle: () -> Boolean = { true },
) {
    var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    var protonBusyId by mutableStateOf<String?>(null)
    var protonStage by mutableStateOf<String?>(null)
    var protonPercent by mutableIntStateOf(-1)
    private val inFlightChecks = java.util.concurrent.atomic.AtomicInteger(0)
    private val checkRequest = java.util.concurrent.atomic.AtomicInteger(0)
    @VisibleForTesting internal var installer: (ProtonExtras.Tool, (String, Int) -> Unit) -> String? =
        { tool, progress -> ProtonExtras.install(activity, tool, progress) }

    fun installProton(id: String, automatic: Boolean = false) {
        val tool = ProtonExtras.availableTools(activity).firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || ProtonExtras.installInProgress || SessionState.running) return
        ProtonExtras.unqueue(activity, tool)
        protonBusyId = id
        protonStage = activity.getString(R.string.store_starting)
        protonPercent = -1
        Thread({
            val problem = runCatching {
                installer(tool) { label, value ->
                    ui.post { protonStage = label; protonPercent = value }
                }
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (problem == null && !automatic && tool.id == ProtonExtras.OWN) ProtonExtras.setDeclined(activity, false)
            ui.post {
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                if (problem != null) {
                    if (automatic) Log.w("ProtonMenu", "auto-update ${tool.id}: $problem")
                    else android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
                }
                if (automatic) ensureAutoUpdates()
            }
        }, "install-proton-$id").start()
    }

    fun removeProton(id: String, dir: String? = null, older: Boolean = false) {
        val tool = ProtonExtras.displayTools(activity).firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonStage = activity.getString(R.string.main_removing_named, dir ?: tool.name)
        protonPercent = -1
        Thread({
            if (older) ProtonExtras.removeOlder(activity, tool) else ProtonExtras.remove(activity, tool, dir)
            if (tool.id == ProtonExtras.OWN && ProtonExtras.installedAll(activity, tool).isEmpty()) ProtonExtras.setDeclined(activity, true)
            ui.post {
                protonBusyId = null
                protonStage = null
                refreshProtons()
            }
        }, "remove-proton-$id").start()
    }

    fun refreshProtons() {
        val available = ProtonExtras.availableTools(activity).map { it.id }.toSet()
        protonRows = ProtonExtras.displayTools(activity).map {
            val all = ProtonExtras.installedAll(activity, it)
            ProtonRow(it.id, it.name, all.lastOrNull(), ProtonExtras.queued(activity, it), all,
                if (it.id in available) ProtonExtras.update(activity, it) else null)
        }
    }

    fun checkUpdates(force: Boolean = false) {
        if (!force && inFlightChecks.get() > 0) return
        val id = checkRequest.incrementAndGet()
        inFlightChecks.incrementAndGet()
        Thread({
            val result = try { runCatching { ProtonExtras.checkLatest(activity, force) } }
                finally { inFlightChecks.decrementAndGet() }
            ui.post {
                if (id != checkRequest.get()) return@post
                if (result.getOrDefault(false) || result.isFailure) refreshProtons()
                if (force) result.exceptionOrNull()?.let {
                    android.widget.Toast.makeText(activity, GitHubArtifacts.failureMessage(activity, it), android.widget.Toast.LENGTH_LONG).show()
                }
                ensureAutoUpdates()
            }
        }, "proton-updates").start()
    }

    fun sourcesChanged() {
        checkRequest.incrementAndGet()
        refreshProtons()
        checkUpdates(force = true)
    }

    internal fun ensureAutoUpdates(now: Long = System.currentTimeMillis()) {
        if (activity.isFinishing || activity.isDestroyed || !idle() || protonBusyId != null ||
            ProtonExtras.installInProgress || SessionState.running || LinuxRuntimeInstaller.isBusy() ||
            SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) ||
            !LinuxRuntime.isInstalled(activity) || !AutoComponents.online(activity) || AutoComponents.unfavourable(activity)) return
        if (ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == ProtonExtras.OWN }).size > 1) {
            protonBusyId = ProtonExtras.OWN
            Thread({
                ProtonExtras.tidy(activity)
                val tidied = ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == ProtonExtras.OWN }).size <= 1
                ui.post {
                    protonBusyId = null
                    refreshProtons()
                    if (tidied) ensureAutoUpdates()
                }
            }, "proton-tidy").start()
            return
        }
        val attempts = activity.getSharedPreferences("proton-auto-updates", android.content.Context.MODE_PRIVATE)
        val (tool, update) = ProtonExtras.availableTools(activity).firstNotNullOfOrNull { tool ->
            val update = ProtonExtras.update(activity, tool) ?: ProtonExtras.missing(activity, tool) ?: return@firstNotNullOfOrNull null
            val previous = attempts.getLong("${tool.id}.at", 0L)
            if (attempts.getString("${tool.id}.tag", null) != update || now - previous !in 0 until 15L * 60 * 1000) tool to update else null
        } ?: return
        attempts.edit().putString("${tool.id}.tag", update).putLong("${tool.id}.at", now).apply()
        installProton(tool.id, automatic = true)
    }
}
