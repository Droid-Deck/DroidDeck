package com.droiddeck.launcher

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ComponentUpdater
import com.droiddeck.launcher.session.ComponentUpdates
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.ProtonDefault
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.session.SessionPrefs

internal var componentsCatalogReader: (
    android.content.Context, Boolean, Set<String>,
) -> ComponentsManager.Catalog = ComponentsManager::catalog

internal class ComponentsMenu(private val activity: android.app.Activity, private val ui: Handler) {
    var compSnapshot by mutableStateOf<ComponentsManager.Snapshot?>(null)
    var compCatalog by mutableStateOf<List<ComponentsManager.CatalogItem>>(emptyList())
    var compCatalogAt by mutableStateOf(0L)
    var compProton by mutableStateOf<String?>(null)
    var compComp by mutableStateOf(com.droiddeck.launcher.ui.GPU_TAB)
    var compChecking by mutableStateOf(false)
    var compBusy by mutableStateOf<String?>(null)
    var compDownloads: Map<String, Int>
        get() = downloads
        private set(value) { downloads = value }
    val compDownloadRevision: Long get() = downloadRevision
    var compModes by mutableStateOf<Map<String, String>>(emptyMap())
    var compAuto by mutableStateOf<Map<String, List<ComponentUpdater.Status>>>(emptyMap())
    var compAutoHeld by mutableStateOf<Set<String>>(emptySet())
    var compAutoChecking by mutableStateOf(false)
    private var shownDefault: String? = null
    private var nextCatalogRequest = 0
    private val catalogRequestByComponent = ComponentsManager.COMPONENTS.associateWith { 0 }.toMutableMap()
    private val catalogAppliedByComponent = ComponentsManager.COMPONENTS.associateWith { 0 }.toMutableMap()
    private val catalogAtByComponent = ComponentsManager.COMPONENTS.associateWith { 0L }.toMutableMap()
    private val activeCatalogRequests = HashSet<Int>()

    fun refreshComponents(snapshotFirst: Boolean = false) {
        val requested = catalogRequestByComponent.toMap()
        val applied = catalogAppliedByComponent.toMap()
        Thread({
            if (snapshotFirst) runCatching { ComponentsManager.snapshotAll(activity) }
            runCatching { ComponentsManager.applyQueued(activity) }
            val snap = runCatching { ComponentsManager.snapshot(activity) }.onFailure { Log.w(TAG, "components", it) }.getOrNull()
            val cat = runCatching { componentsCatalogReader(activity, false, ComponentsManager.COMPONENTS.toSet()) }.getOrNull()
            val chosen = snap?.let { s -> runCatching { ProtonDefault.selectedId(activity, s.protons.map { it.proton }) }.getOrNull() }
            runCatching { ComponentUpdater.settle(activity) }
            val modes = ComponentsManager.COMPONENTS.associateWith { SessionPrefs.componentMode(activity, it) }
            val auto = ComponentsManager.COMPONENTS.associateWith { runCatching { ComponentUpdater.status(activity, it) }.getOrDefault(emptyList()) }
            val held = if (runCatching { ComponentsManager.hardwareDxvk(activity) }.getOrDefault(false)) setOf("dxvk") else emptySet()
            ui.post {
                compModes = modes
                compAuto = auto
                compAutoHeld = held
                compSnapshot = snap ?: ComponentsManager.Snapshot(emptyList(), emptyList())
                if (cat != null) {
                    val current = applied.keys.filterTo(LinkedHashSet()) {
                        catalogRequestByComponent[it] == requested[it] && catalogAppliedByComponent[it] == applied[it]
                    }
                    mergeCatalog(cat, current)
                }
                if (chosen != null && chosen != shownDefault) {
                    shownDefault = chosen
                    compProton = chosen
                } else if (compProton == null || snap?.protons?.none { it.proton.id == compProton } == true) {
                    compProton = chosen ?: snap?.protons?.firstOrNull()?.proton?.id
                }
            }
        }, "components").start()
    }

    fun chooseComponentMode(comp: String, mode: String) {
        SessionPrefs.setComponentMode(activity, comp, mode)
        compModes = compModes + (comp to mode)
        if (mode != ComponentUpdates.AUTO) return
        checkComponentUpdates(force = true)
    }

    private var checkAgain: Boolean? = null

    fun checkComponentUpdates(force: Boolean) {
        if (compAutoChecking) {
            checkAgain = force || checkAgain == true
            return
        }
        compAutoChecking = true
        Thread({
            val notes = runCatching { ComponentUpdater.ensure(activity, force) }.onFailure { Log.w(TAG, "component updates", it) }.getOrDefault(emptyList())
            ui.post {
                compAutoChecking = false
                checkAgain?.let { again ->
                    checkAgain = null
                    checkComponentUpdates(again)
                }
                if (notes.isNotEmpty()) android.widget.Toast.makeText(activity, notes.joinToString("\n"), android.widget.Toast.LENGTH_LONG).show()
                refreshComponents()
            }
        }, "component-updates").start()
    }

    fun chooseProton(id: String) {
        val proton = compSnapshot?.protons?.firstOrNull { it.proton.id == id }?.proton ?: return
        compProton = id
        Thread({
            val message = runCatching {
                when (ProtonDefault.request(activity, proton)) {
                    ProtonDefault.Outcome.LIVE -> activity.getString(R.string.comp_default_live, proton.name)
                    ProtonDefault.Outcome.NEXT_START -> activity.getString(R.string.comp_default_next, proton.name)
                    ProtonDefault.Outcome.NOT_RUNNABLE -> activity.getString(R.string.comp_default_not_runnable, proton.name)
                }
            }.getOrElse { e -> activity.getString(R.string.comp_default_failed, e.message ?: e.javaClass.simpleName) }
            ui.post { android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show() }
        }, "components-default").start()
    }

    fun componentAction(label: String, work: () -> String) {
        if (compBusy != null) return
        compBusy = label
        Thread({
            val message = runCatching(work).getOrElse { e -> activity.getString(R.string.main_action_failed, label, e.message ?: e.javaClass.simpleName) }
            ui.post {
                compBusy = null
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
                refreshComponents()
            }
        }, "components-action").start()
    }

    fun refreshComponentCatalog(force: Boolean = true, components: Set<String> = ComponentsManager.COMPONENTS.toSet()) {
        val wanted = components.filterTo(LinkedHashSet()) { it in ComponentsManager.COMPONENTS }
        if (wanted.isEmpty()) return
        val id = ++nextCatalogRequest
        wanted.forEach { catalogRequestByComponent[it] = id }
        activeCatalogRequests.add(id)
        compChecking = true
        Thread({
            val result = runCatching { componentsCatalogReader(activity, force, wanted) }
            val cat = result.getOrNull()
            ui.post {
                activeCatalogRequests.remove(id)
                compChecking = activeCatalogRequests.isNotEmpty()
                val current = wanted.filterTo(LinkedHashSet()) { catalogRequestByComponent[it] == id }
                if (cat != null && current.isNotEmpty()) {
                    mergeCatalog(cat, current)
                    current.forEach { catalogAppliedByComponent[it] = id }
                }
                val currentFailures = cat?.failed.orEmpty().filter { failure ->
                    ':' !in failure || current.any { failure.startsWith("$it:") }
                }
                if (force && current.isNotEmpty() && (cat == null || currentFailures.isNotEmpty())) {
                    val rateLimited = cat?.let {
                        it.rateLimitedComponents.any(current::contains) ||
                            (it.rateLimited && it.rateLimitedComponents.isEmpty() && currentFailures.isNotEmpty())
                    } == true || result.exceptionOrNull()?.let(GitHubArtifacts::isRateLimited) == true
                    val message = activity.getString(if (rateLimited) R.string.sources_github_rate_limited else R.string.comp_nightlies_unreachable)
                    android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }, "components-catalog").start()
    }

    fun sourcesChanged(component: String) {
        if (component !in ComponentsManager.COMPONENTS) return
        compCatalog = compCatalog.filterNot { it.comp == component }
        refreshComponentCatalog(force = true, components = setOf(component))
    }

    private fun mergeCatalog(catalog: ComponentsManager.Catalog, current: Set<String>) {
        if (current.isEmpty()) return
        compCatalog = (compCatalog.filterNot { it.comp in current } + catalog.items.filter { it.comp in current })
            .distinctBy { it.id }.sortedWith(ComponentsManager.CATALOG_ORDER)
        current.forEach { catalogAtByComponent[it] = catalog.fetchedAt }
        compCatalogAt = catalogAtByComponent.values.maxOrNull() ?: 0L
    }

    fun downloadComponent(item: ComponentsManager.CatalogItem) {
        if (compDownloads.containsKey(item.id)) return
        compDownloads = compDownloads + (item.id to -1)
        Thread({
            val message = runCatching {
                val pkg = ComponentsManager.download(activity, item) { pc -> ui.post { if (compDownloads.containsKey(item.id)) compDownloads = compDownloads + (item.id to pc) } }
                activity.getString(R.string.comp_stored, pkg.version)
            }.getOrElse { e -> activity.getString(R.string.comp_download_failed, e.message ?: e.javaClass.simpleName) }
            ui.post {
                compDownloads = compDownloads - item.id
                downloadRevision++
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
                refreshComponents()
            }
        }, "components-download").start()
    }

    private companion object {
        const val TAG = "ComponentsMenu"
        var downloads by mutableStateOf<Map<String, Int>>(emptyMap())
        var downloadRevision by mutableStateOf(0L)
    }
}
