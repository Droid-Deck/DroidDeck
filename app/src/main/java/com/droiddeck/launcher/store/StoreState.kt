package com.droiddeck.launcher.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.FlatpakManager

/**
 * The store's state for the life of the process, so an install carries on - and its progress
 * shows - while the user moves around the launcher or plays something meanwhile.
 */
object StoreState {
    private val main = Handler(Looper.getMainLooper())

    var ready by mutableStateOf(false)
        private set
    var installed by mutableStateOf<List<FlatpakManager.App>>(emptyList())
        private set
    var updates by mutableStateOf<Set<String>>(emptySet())
        private set
    var checkingUpdates by mutableStateOf(false)
        private set
    /** Flathub has been asked this session, so an empty [updates] means up to date. */
    var updatesChecked by mutableStateOf(false)
        private set
    /** What is running: "setup", an app id, or "update-all"; null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set
    var stage by mutableStateOf<String?>(null)
        private set
    var percent by mutableStateOf(-1)
        private set

    /** The front page's sections, by collection name; absent until loaded, empty list on failure. */
    val sections = mutableStateMapOf<String, List<FlathubApi.AppSummary>>()
    var sectionsFailed by mutableStateOf(false)
        private set
    val details = mutableStateMapOf<String, FlathubApi.AppDetails>()
    val detailsFailed = mutableStateMapOf<String, Boolean>()

    var searchResults by mutableStateOf<List<FlathubApi.AppSummary>?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchSeq = 0

    val SECTIONS = listOf("popular" to R.string.store_section_popular, "trending" to R.string.store_section_trending, "recently-added" to R.string.store_section_new, "recently-updated" to R.string.store_section_updated)

    fun refresh(context: Context) {
        val app = context.applicationContext
        Thread({
            val r = FlatpakManager.ready(app)
            val list = if (r) FlatpakManager.installedApps(app) else emptyList()
            main.post { ready = r; installed = list; updates = updates.filter { id -> list.any { it.id == id } }.toSet() }
        }, "store-refresh").start()
    }

    fun loadSections(force: Boolean = false) {
        if (!force && sections.isNotEmpty()) return
        sectionsFailed = false
        Thread({
            var failed = false
            for ((key, _) in SECTIONS) {
                val apps = FlathubApi.collection(key, 18)
                if (apps == null) failed = true
                main.post { sections[key] = apps ?: emptyList() }
            }
            main.post { sectionsFailed = failed }
        }, "store-sections").start()
    }

    fun loadDetails(id: String) {
        if (details.containsKey(id)) return
        detailsFailed.remove(id)
        Thread({
            val d = FlathubApi.details(id)
            main.post { if (d != null) details[id] = d else detailsFailed[id] = true }
        }, "store-details").start()
    }

    fun search(query: String, category: String?) {
        val seq = ++searchSeq
        searching = true
        Thread({
            val r = FlathubApi.search(query, category)
            main.post { if (seq == searchSeq) { searchResults = r ?: emptyList(); searching = false } }
        }, "store-search").start()
    }

    fun clearSearch() { searchSeq++; searchResults = null; searching = false }

    private fun run(context: Context, what: String, label: String, work: (Context, (String, Int) -> Unit) -> String?) {
        if (busy != null) return
        val app = context.applicationContext
        busy = what; stage = app.getString(R.string.store_starting); percent = -1
        Thread({
            val problem = try {
                work(app) { s, p -> main.post { stage = s; percent = p } }
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            val r = FlatpakManager.ready(app)
            val list = if (r) FlatpakManager.installedApps(app) else emptyList()
            main.post {
                busy = null; stage = null; percent = -1
                ready = r; installed = list
                if (problem != null) Toast.makeText(app, app.getString(R.string.store_problem, label, problem), Toast.LENGTH_LONG).show()
            }
        }, "store-$what").start()
    }

    fun setup(context: Context) = run(context, "setup", context.getString(R.string.store_flatpak_setup)) { c, p -> FlatpakManager.setup(c, p) }

    fun install(context: Context, id: String, name: String) =
        run(context, id, name) { c, p -> FlatpakManager.install(c, id, p) }

    fun uninstall(context: Context, id: String, name: String) =
        run(context, id, name) { c, p -> FlatpakManager.uninstall(c, id, p).also { if (it == null) main.post { updates = updates - id } } }

    fun update(context: Context, id: String?, name: String) =
        run(context, id ?: "update-all", name) { c, p ->
            FlatpakManager.update(c, id, p).also { if (it == null) main.post { updates = if (id == null) emptySet() else updates - id } }
        }

    fun checkUpdates(context: Context) {
        if (checkingUpdates || busy != null) return
        val app = context.applicationContext
        checkingUpdates = true
        Thread({
            val u = FlatpakManager.updates(app)
            main.post {
                checkingUpdates = false
                if (u != null) { updates = u; updatesChecked = true } else Toast.makeText(app, R.string.store_updates_check_failed, Toast.LENGTH_SHORT).show()
            }
        }, "store-updates").start()
    }
}
