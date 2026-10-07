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
 * The optional ARM64 Protons (GE-Proton, proton-cachyos): the rows the page lists and an install or
 * removal in progress. The launcher screen keeps one and shows it.
 */
internal class ProtonMenu(private val activity: android.app.Activity, private val ui: android.os.Handler) {
    var protonRows by mutableStateOf<List<ProtonRow>>(emptyList())
    var protonBusyId by mutableStateOf<String?>(null)
    var protonStage by mutableStateOf<String?>(null)
    var protonPercent by mutableIntStateOf(-1)

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

    fun removeProton(id: String) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonStage = activity.getString(R.string.main_removing_named, tool.name)
        protonPercent = -1
        Thread({
            ProtonExtras.remove(activity, tool)
            ui.post {
                protonBusyId = null
                protonStage = null
                refreshProtons()
            }
        }, "remove-proton-$id").start()
    }

    fun refreshProtons() {
        protonRows = ProtonExtras.tools.map { ProtonRow(it.id, it.name, ProtonExtras.installed(activity, it), ProtonExtras.queued(activity, it)) }
    }
}
