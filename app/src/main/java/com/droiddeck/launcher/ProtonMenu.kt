package com.droiddeck.launcher

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.ui.ProtonRow
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.artifact.GitHubArtifacts

internal class ProtonMenu(private val activity: android.app.Activity, private val ui: android.os.Handler) {
    var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    var protonBusyId by mutableStateOf<String?>(null)
    var protonStage by mutableStateOf<String?>(null)
    var protonPercent by mutableIntStateOf(-1)
    private val inFlightChecks = java.util.concurrent.atomic.AtomicInteger(0)
    private val checkRequest = java.util.concurrent.atomic.AtomicInteger(0)

    fun installProton(id: String) {
        val tool = ProtonExtras.availableTools(activity).firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        ProtonExtras.unqueue(activity, tool)
        protonBusyId = id
        protonStage = activity.getString(R.string.store_starting)
        protonPercent = -1
        Thread({
            val problem = ProtonExtras.install(activity, tool) { label, value ->
                ui.post { protonStage = label; protonPercent = value }
            }
            ui.post {
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                if (problem != null) android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
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
            }
        }, "proton-updates").start()
    }

    fun sourcesChanged() {
        checkRequest.incrementAndGet()
        refreshProtons()
        checkUpdates(force = true)
    }
}
