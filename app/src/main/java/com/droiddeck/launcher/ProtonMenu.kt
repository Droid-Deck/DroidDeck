package com.droiddeck.launcher

import android.os.Handler
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ExperimentalProtons
import com.droiddeck.launcher.session.Onboarding
import com.droiddeck.launcher.session.ProtonExtras
import com.droiddeck.launcher.session.SteamClientPrefetch
import com.droiddeck.launcher.ui.ProtonRow
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.frontend.AutoComponents
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller

internal class ProtonMenu(
    private val activity: android.app.Activity,
    private val ui: Handler,
    private val onInstalled: () -> Unit = {},
    private val onRemoved: () -> Unit = {},
    private val idle: () -> Boolean = { true },
) {
    private object Operation {
        val rows = mutableStateOf<List<ProtonRow>>(emptyList())
        val busyId = mutableStateOf<String?>(null)
        val stage = mutableStateOf<String?>(null)
        val percent = mutableIntStateOf(-1)
        val problem = mutableStateOf<String?>(null)
        val experimental = mutableStateOf<List<String>>(emptyList())
        val experimentalReleases = mutableStateOf<List<ExperimentalProtons.Release>>(emptyList())
        var fetchedReleases = emptyList<ExperimentalProtons.Release>()
        val checking = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    var protonRows by Operation.rows
    var protonBusyId by Operation.busyId
    var protonStage by Operation.stage
    var protonPercent by Operation.percent
    var protonProblem by Operation.problem
    var experimentalDirs by Operation.experimental
    var experimentalReleases by Operation.experimentalReleases
    companion object {
        const val EXPERIMENTAL = "experimental"
    }
    @VisibleForTesting internal var latestCheck: (Boolean) -> Boolean = { force -> ProtonExtras.checkLatest(activity, force) }
    @VisibleForTesting internal var experimentalRefresh: () -> List<ExperimentalProtons.Release>? = { ExperimentalProtons.refresh(activity) }
    @VisibleForTesting internal var installer: (ProtonExtras.Tool, (String, Int) -> Unit) -> String? =
        { tool, progress -> ProtonExtras.install(activity, tool, progress) }

    fun installProton(id: String, automatic: Boolean = false) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || ProtonExtras.installInProgress || SessionState.running) return
        ProtonExtras.unqueue(activity, tool)
        protonBusyId = id
        protonProblem = null
        protonStage = activity.getString(R.string.store_starting)
        protonPercent = -1
        Thread({
            val problem = runCatching {
                installer(tool) { label, value ->
                    if (tool.id == ProtonExtras.OWN) Onboarding.report(Onboarding.Step.PROTON, label, value)
                    ui.post { protonStage = label; protonPercent = value }
                }
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (problem == null && !automatic && tool.id == ProtonExtras.OWN) ProtonExtras.setDeclined(activity, false)
            if (tool.id == ProtonExtras.OWN) {
                if (problem == null) Onboarding.finish(Onboarding.Step.PROTON) else Onboarding.fail(Onboarding.Step.PROTON, problem)
                Onboarding.recheck(activity)
            }
            ui.post {
                protonProblem = problem
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                if (problem != null) {
                    if (automatic) Log.w("ProtonMenu", "auto-update ${tool.id}: $problem")
                    else android.widget.Toast.makeText(activity, problem, android.widget.Toast.LENGTH_LONG).show()
                }
                if (problem == null) onInstalled()
                if (automatic) ensureAutoUpdates()
            }
        }, "install-proton-$id").start()
    }

    fun removeProton(id: String, dir: String? = null, older: Boolean = false) {
        val tool = ProtonExtras.tools.firstOrNull { it.id == id } ?: return
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = id
        protonProblem = null
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

    fun installExperimental(work: ((String, Int) -> Unit) -> String?) {
        if (protonBusyId != null || ProtonExtras.installInProgress || SessionState.running) return
        protonBusyId = EXPERIMENTAL
        protonProblem = null
        protonStage = activity.getString(R.string.store_starting)
        protonPercent = -1
        Thread({
            val problem = runCatching {
                work { label, value -> ui.post { protonStage = label; protonPercent = value } }
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            ui.post {
                protonProblem = problem
                protonBusyId = null
                protonStage = null
                protonPercent = -1
                refreshProtons()
                android.widget.Toast.makeText(activity, problem ?: activity.getString(R.string.xproton_installed), android.widget.Toast.LENGTH_LONG).show()
                if (problem == null) onInstalled()
            }
        }, "install-proton-experimental").start()
    }

    fun installExperimentalRelease(release: ExperimentalProtons.Release) {
        installExperimental { progress -> ExperimentalProtons.installRelease(activity, release, progress) }
    }

    fun removeExperimental(dir: String) {
        if (protonBusyId != null || SessionState.running) return
        protonBusyId = EXPERIMENTAL
        protonProblem = null
        protonStage = activity.getString(R.string.main_removing_named, dir)
        protonPercent = -1
        Thread({
            val removed = runCatching { ExperimentalProtons.remove(activity, dir) }.getOrDefault(false)
            ui.post {
                protonBusyId = null
                protonStage = null
                refreshProtons()
                if (!removed) android.widget.Toast.makeText(activity, activity.getString(R.string.xproton_remove_failed, dir), android.widget.Toast.LENGTH_LONG).show()
            }
        }, "remove-proton-experimental").start()
    }

    private fun refreshExperimental() {
        val installed = ExperimentalProtons.installed(activity)
        experimentalDirs = installed.map { it.dir }
        experimentalReleases = ExperimentalProtons.available(Operation.fetchedReleases, installed)
    }

    fun refreshProtons() {
        refreshExperimental()
        protonRows = ProtonExtras.tools.map {
            val all = ProtonExtras.installedAll(activity, it)
            ProtonRow(it.id, it.name, all.lastOrNull(), ProtonExtras.queued(activity, it), all, ProtonExtras.update(activity, it))
        }
    }

    fun checkUpdates(force: Boolean = false) {
        if (!Operation.checking.compareAndSet(false, true)) return
        Thread({
            val asked = runCatching { latestCheck(force) }.getOrDefault(false)
            val experimental = runCatching { experimentalRefresh() }.getOrNull()
            val published = ui.post {
                try {
                    val before = experimentalDirs
                    Operation.fetchedReleases = experimental ?: emptyList()
                    if (asked) refreshProtons() else refreshExperimental()
                    if (!experimentalDirs.containsAll(before)) onRemoved()
                } finally {
                    Operation.checking.set(false)
                }
                ensureAutoUpdates()
                ensureSteamClient()
            }
            if (!published) Operation.checking.set(false)
        }, "proton-updates").start()
    }

    /** Downloads the DroidDeck Proton archive while the runtime installs; the install step later finds it whole. */
    fun prefetchOwn() {
        val tool = ProtonExtras.tools.first { it.id == ProtonExtras.OWN }
        if (ProtonExtras.declined(activity) || ProtonExtras.installedAll(activity, tool).isNotEmpty() ||
            ProtonExtras.installInProgress || !AutoComponents.online(activity) || AutoComponents.firstRunHeldBack(activity)) return
        Thread({
            val problem = runCatching {
                ProtonExtras.prefetch(activity, tool) { label, value -> Onboarding.report(Onboarding.Step.PROTON, label, value) }
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (problem == null) Onboarding.finish(Onboarding.Step.PROTON) else Onboarding.fail(Onboarding.Step.PROTON, problem)
            Onboarding.recheck(activity)
            ui.post { ensureAutoUpdates() }
        }, "proton-prefetch").start()
    }

    /** Fetches Steam's client in the background once the runtime exists, so the first Play does not wait for it. */
    fun ensureSteamClient() {
        if (activity.isFinishing || activity.isDestroyed || !idle() || SessionState.running || LinuxRuntimeInstaller.isBusy() ||
            SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) || !LinuxRuntime.isInstalled(activity) ||
            !AutoComponents.online(activity) || AutoComponents.firstRunHeldBack(activity) || SteamClientPrefetch.running()) return
        SteamClientPrefetch.start(
            activity,
            { mb -> activity.getString(if (mb > 0) R.string.onboarding_steam_downloading_mb else R.string.onboarding_steam_downloading, mb) },
            activity.getString(R.string.onboarding_steam_failed),
        )
    }

    internal fun ensureAutoUpdates(now: Long = System.currentTimeMillis()) {
        if (activity.isFinishing || activity.isDestroyed || !idle() || protonBusyId != null ||
            ProtonExtras.installInProgress || ProtonExtras.prefetching || SessionState.running || LinuxRuntimeInstaller.isBusy() ||
            SessionState.phase !in setOf(SessionPhase.IDLE, SessionPhase.FAILED) ||
            !LinuxRuntime.isInstalled(activity) || !AutoComponents.online(activity)) return
        val firstInstall = !ProtonExtras.declined(activity) && ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == ProtonExtras.OWN }).isEmpty()
        if (if (firstInstall) AutoComponents.firstRunHeldBack(activity) else AutoComponents.unfavourable(activity)) return
        if (ProtonExtras.installedAll(activity, ProtonExtras.tools.first { it.id == ProtonExtras.OWN }).size > 1) {
            protonBusyId = ProtonExtras.OWN
            protonProblem = null
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
        val (tool, update) = ProtonExtras.tools.firstNotNullOfOrNull { tool ->
            val update = ProtonExtras.update(activity, tool) ?: ProtonExtras.missing(activity, tool) ?: return@firstNotNullOfOrNull null
            val previous = attempts.getLong("${tool.id}.at", 0L)
            if (attempts.getString("${tool.id}.tag", null) != update || now - previous !in 0 until 15L * 60 * 1000) tool to update else null
        } ?: return
        attempts.edit().putString("${tool.id}.tag", update).putLong("${tool.id}.at", now).apply()
        installProton(tool.id, automatic = true)
    }
}
