package com.droiddeck.launcher

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.ui.ProtonRow
import com.droiddeck.launcher.session.SessionState

/**
 * The optional ARM64 Protons (GE-Proton, proton-cachyos, DroidDeck-Proton): the rows the page lists,
 * an install or removal in progress, and whether a newer release is out. The launcher screen keeps
 * one and shows it.
 */
internal class ProtonMenu(private val activity: android.app.Activity, private val ui: android.os.Handler) {
    var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    var protonBusyId by mutableStateOf<String?>(null)
    var protonStage by mutableStateOf<String?>(null)
    var protonPercent by mutableIntStateOf(-1)
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    fun installProton(id: String) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
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

    /** Removes the build in [dir], or with [older] every build of it but the newest. */
    fun removeProton(id: String, dir: String? = null, older: Boolean = false) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
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
        protonRows = ProtonExtras.tools.map {
            val all = ProtonExtras.installedAll(activity, it)
            ProtonRow(it.id, it.name, all.lastOrNull(), ProtonExtras.queued(activity, it), all, ProtonExtras.update(activity, it))
        }
    }

    /** Looks for newer releases, at most every few hours (see ProtonExtras.checkLatest); quiet offline. */
    fun checkUpdates(force: Boolean = false) {
        if (!checking.compareAndSet(false, true)) return
        Thread({
            val asked = try { ProtonExtras.checkLatest(activity, force) } catch (_: Throwable) { false } finally { checking.set(false) }
            if (asked) ui.post { refreshProtons() }
        }, "proton-updates").start()
    }
}
