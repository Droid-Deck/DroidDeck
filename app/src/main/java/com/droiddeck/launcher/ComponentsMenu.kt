package com.droiddeck.launcher

import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.ProtonDefault

/**
 * The Components page's state and work: the Protons and their DXVK/VKD3D sets, the Nightlies
 * catalog, downloads and the swap/restore actions. The launcher screen keeps one and shows it.
 */
internal class ComponentsMenu(private val activity: android.app.Activity, private val ui: android.os.Handler) {
    var compSnapshot by mutableStateOf<ComponentsManager.Snapshot?>(null)
    var compCatalog by mutableStateOf<List<ComponentsManager.CatalogItem>>(emptyList())
    var compCatalogAt by mutableStateOf(0L)
    var compProton by mutableStateOf<String?>(null)
    var compComp by mutableStateOf(com.droiddeck.launcher.ui.GPU_TAB)
    var compChecking by mutableStateOf(false)
    var compBusy by mutableStateOf<String?>(null)
    var compDownloads by mutableStateOf<Map<String, Int>>(emptyMap())
    private var shownDefault: String? = null

    /** Reads the Protons (and on first open saves their originals) off the UI thread; the Nightlies list comes from its cache. */
    fun refreshComponents(snapshotFirst: Boolean = false) {
        Thread({
            if (snapshotFirst) runCatching { ComponentsManager.snapshotAll(activity) }
            runCatching { ComponentsManager.applyQueued(activity) }
            val snap = runCatching { ComponentsManager.snapshot(activity) }.onFailure { Log.w(TAG, "components", it) }.getOrNull()
            val cat = runCatching { ComponentsManager.catalog(activity, false) }.getOrNull()
            val chosen = snap?.let { s -> runCatching { ProtonDefault.selectedId(activity, s.protons.map { it.proton }) }.getOrNull() }
            ui.post {
                compSnapshot = snap ?: ComponentsManager.Snapshot(emptyList(), emptyList())
                if (cat != null && cat.fetchedAt > 0) { compCatalog = cat.items; compCatalogAt = cat.fetchedAt }
                if (chosen != null && chosen != shownDefault) {
                    shownDefault = chosen
                    compProton = chosen
                } else if (compProton == null || snap?.protons?.none { it.proton.id == compProton } == true) {
                    compProton = chosen ?: snap?.protons?.firstOrNull()?.proton?.id
                }
            }
        }, "components").start()
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

    fun refreshComponentCatalog() {
        if (compChecking) return
        compChecking = true
        Thread({
            val cat = runCatching { ComponentsManager.catalog(activity, true) }.getOrNull()
            ui.post {
                compChecking = false
                if (cat == null || cat.items.isEmpty()) android.widget.Toast.makeText(activity, activity.getString(R.string.comp_nightlies_unreachable), android.widget.Toast.LENGTH_LONG).show()
                else { compCatalog = cat.items; compCatalogAt = cat.fetchedAt }
            }
        }, "components-catalog").start()
    }

    fun downloadComponent(item: ComponentsManager.CatalogItem) {
        if (compDownloads.containsKey(item.file)) return
        compDownloads = compDownloads + (item.file to -1)
        Thread({
            val message = runCatching {
                val pkg = ComponentsManager.download(activity, item) { pc -> ui.post { if (compDownloads.containsKey(item.file)) compDownloads = compDownloads + (item.file to pc) } }
                activity.getString(R.string.comp_stored, pkg.version)
            }.getOrElse { e -> activity.getString(R.string.comp_download_failed, e.message ?: e.javaClass.simpleName) }
            ui.post {
                compDownloads = compDownloads - item.file
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show()
                refreshComponents()
            }
        }, "components-download").start()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
