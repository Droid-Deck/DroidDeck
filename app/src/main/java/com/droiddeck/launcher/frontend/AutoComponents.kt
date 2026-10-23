package com.droiddeck.launcher.frontend

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.util.AtomicFile
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.session.WinComponents
import com.droiddeck.launcher.stores.StoreGameSidecar
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.PriorityBlockingQueue

object AutoComponents {
    private const val TAG = "AutoComponents"

    fun appKey(game: Library.SteamGame): String? =
        if (game.library == Library.ADDED) Integer.toUnsignedString(game.appId) else game.appId.takeIf { it > 0 }?.toString()

    fun autoPicks(findings: List<WinCompSources.Finding>, catalog: Map<String, WinComponents.Component>): Map<String, WinComponents.AutoReason> {
        val out = LinkedHashMap<String, WinComponents.AutoReason>()
        for (f in findings) {
            if (!f.auto || f.component == null) continue
            val id = WinComponents.installable(f.component, catalog)
            val c = catalog[id] ?: continue
            if (WinComponents.support(c, catalog) != WinComponents.Support.READY) continue
            if (id !in out) out[id] = WinComponents.AutoReason(f.origin.name, listOf(f.original) + f.args)
        }
        return out
    }

    @VisibleForTesting internal var catalogOf: (Context, Boolean) -> List<WinComponents.Component>? =
        { c, network -> if (network) WinComponents.fetch(c) else WinComponents.cached(c) }
    @VisibleForTesting internal var installedOf: (Context) -> Set<String> = { WinComponents.installedIds(it).toSet() }
    @VisibleForTesting internal var installer: (Context, WinComponents.Component, Map<String, WinComponents.Component>, (WinComponents.Progress) -> Unit) -> String? =
        { c, comp, all, progress -> WinComponents.install(c, comp, all, progress) }
    @VisibleForTesting internal var sessionRunning: () -> Boolean = { SessionState.running }
    @VisibleForTesting internal var unfavourable: (Context) -> Boolean = { c -> constrained(c) }
    @VisibleForTesting internal var online: (Context) -> Boolean = { c -> isOnline(c) }
    @VisibleForTesting @Volatile internal var runNow = false

    val changes = MutableStateFlow(0)

    val downloading = MutableStateFlow<Map<String, WinComponents.Progress>>(emptyMap())

    val pendingGames = MutableStateFlow<Set<String>>(emptySet())

    val pendingComponents = MutableStateFlow<Set<String>>(emptySet())

    private fun mark(job: Job, on: Boolean) {
        if (job.pics != null) return
        val flow = if (job.component != null) pendingComponents else pendingGames
        val id = job.component ?: job.game?.let { appKey(it) } ?: return
        flow.value = if (on) flow.value + id else flow.value - id
    }

    internal class Job(
        val game: Library.SteamGame?, val component: String?, val asked: Boolean, val seq: Long, val pics: List<Int>? = null,
        val inSession: Boolean = false,
    ) {
        val key get() = when {
            pics != null -> "pics"
            component != null -> "c:$component"
            else -> "g:" + (game?.let { appKey(it) } ?: "")
        }
        val online get() = component != null || pics != null
    }

    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val jobs = PriorityBlockingQueue<Job>(16, compareByDescending<Job> { it.asked }.thenBy { it.seq })
    @Volatile private var draining = false
    private val queuedKeys = java.util.Collections.synchronizedSet(HashSet<String>())
    private val waiting = HashMap<String, MutableList<Library.SteamGame>>()
    @Volatile private var worker: Thread? = null
    private const val PAUSE_MS = 15_000L

    fun queueGame(context: Context, game: Library.SteamGame, asked: Boolean) {
        if (appKey(game) == null) return
        enqueue(context, Job(game, null, asked, seq.incrementAndGet()))
    }

    private fun queueDownload(context: Context, id: String, asked: Boolean, inSession: Boolean = false) =
        enqueue(context, Job(null, id, asked, seq.incrementAndGet(), inSession = inSession))

    private fun enqueue(context: Context, job: Job) {
        if (!queuedKeys.add(job.key)) {
            if (job.asked && jobs.removeIf { it.key == job.key && !it.asked }) jobs.add(job)
            if (runNow && !draining) drain(context)
            return
        }
        if (job.component != null) Log.i(TAG, "wincomp: queued ${job.component}")
        mark(job, true)
        jobs.add(job)
        if (runNow) { if (!draining) drain(context) } else ensureWorker(context.applicationContext)
    }

    internal fun mustWait(context: Context, job: Job): Boolean =
        if (job.inSession && sessionRunning()) gameRunning() || (job.online && !online(context))
        else sessionRunning() || (job.online && !online(context)) || (!job.asked && unfavourable(context))

    @VisibleForTesting internal var gameRunning: () -> Boolean = { gameProcessRunning() }

    private fun gameProcessRunning(): Boolean = File("/proc").listFiles()?.any { p ->
        p.name.all(Char::isDigit) && runCatching { String(File(p, "cmdline").readBytes()).replace('\u0000', ' ') }.getOrNull()
            ?.let { it.contains("SteamLaunch AppId=") || it.contains("proton waitforexitandrun") } == true
    } == true

    fun queueInSession(context: Context, game: Library.SteamGame) {
        if (appKey(game) == null) return
        enqueue(context, Job(game, null, true, seq.incrementAndGet(), inSession = true))
    }

    @VisibleForTesting
    internal fun drain(context: Context) {
        draining = true
        try {
            while (true) {
                val job = next(context) ?: return
                queuedKeys.remove(job.key)
                run(context, job)
            }
        } finally {
            draining = false
        }
    }

    private fun next(context: Context): Job? = synchronized(jobs) {
        val job = jobs.sortedWith(order).firstOrNull { !mustWait(context, it) } ?: return null
        jobs.remove(job)
        job
    }

    private val order = compareByDescending<Job> { it.asked }.thenBy { it.seq }

    @VisibleForTesting internal fun queued(): List<String> = jobs.sortedWith(compareByDescending<Job> { it.asked }.thenBy { it.seq }).map { it.key }

    @Synchronized
    private fun ensureWorker(app: Context) {
        if (worker?.isAlive == true) return
        worker = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            while (true) {
                try { jobs.put(jobs.take()) } catch (e: InterruptedException) { return@Thread }
                val job = next(app)
                if (job == null) {
                    try { Thread.sleep(PAUSE_MS) } catch (e: InterruptedException) { return@Thread }
                    continue
                }
                queuedKeys.remove(job.key)
                WinComponents.background { run(app, job) }
            }
        }, "auto-components").apply { isDaemon = true; start() }
    }

    private fun run(context: Context, job: Job) {
        try {
            when {
                job.pics != null -> SteamAppInfo.lookup(context, job.pics)
                job.component != null -> download(context, job.component, job.asked, job.inSession)
                else -> job.game?.let { apply(context, it, job.asked, job.inSession) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "wincomp: ${job.key}: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            mark(job, false)
            changes.value = changes.value + 1
        }
    }

    private fun apply(context: Context, game: Library.SteamGame, asked: Boolean, inSession: Boolean = false) {
        val key = appKey(game) ?: return
        val folder = game.gameFiles
        if (game.library == Library.ADDED && game.source == Library.ADDED && folder != null && SteamMatch.get(context, folder.path) == null) {
            AddedGames.single(context, folder.path)?.let { SteamMatch.resolve(context, folder.path, it.exe, it.name) }
        }
        val store = com.droiddeck.launcher.stores.Store.byId(game.source)
        if (folder != null && (store == com.droiddeck.launcher.stores.Store.GOG || store == com.droiddeck.launcher.stores.Store.EPIC)) StoreListBackfill.fill(context, folder)
        if (asked && online(context)) steamAppOf(context, game)?.let { SteamAppInfo.lookup(context, listOf(it)) }
        val findings = findings(context, game)
        val catalog = (catalogOf(context, false) ?: catalogOf(context, true))?.associateBy { it.name } ?: return
        val wanted = autoPicks(findings, catalog)
        val user = WinComponents.selection(context, key).user
        val installed = installedOf(context)
        val kept = LinkedHashMap<String, WinComponents.AutoReason>()
        val missing = ArrayList<String>()
        for ((id, reason) in wanted) {
            if (user[id] == false) { kept[id] = reason; continue }
            if (id in installed) { kept[id] = reason; continue }
            synchronized(waiting) { waiting.getOrPut(id) { ArrayList() }.let { if (it.none { g -> appKey(g) == key }) it.add(game) } }
            queueDownload(context, id, asked, inSession)
            missing.add(id)
        }
        if (inSession) Pending.set(context, key, missing)
        val before = WinComponents.selection(context, key).auto
        if (before.keys != kept.keys) {
            WinComponents.setAuto(context, key, kept)
            Log.i(TAG, "${game.name}: on by itself ${kept.keys.joinToString().ifEmpty { "(none)" }}")
        }
        changes.value = changes.value + 1
    }

    private fun download(context: Context, id: String, asked: Boolean, inSession: Boolean = false) {
        val catalog = catalogOf(context, true)?.associateBy { it.name } ?: run { Pending.drop(context, id); return }
        if (id !in installedOf(context)) {
            val c = catalog[id] ?: run { Pending.drop(context, id); return }
            val started = System.currentTimeMillis()
            downloading.value = downloading.value + (id to WinComponents.Progress(id, "", WinComponents.Phase.DOWNLOAD, -1))
            val problem = try {
                installer(context, c, catalog) { p -> downloading.value = downloading.value + (id to p) }
            } finally {
                downloading.value = downloading.value - id
            }
            if (problem != null) { Log.w(TAG, "wincomp: $id did not install: $problem"); Pending.drop(context, id); return }
            Log.i(TAG, "wincomp: $id installed in ${System.currentTimeMillis() - started}ms")
        }
        val games = synchronized(waiting) { waiting.remove(id).orEmpty() }
        games.forEach { apply(context, it, asked, inSession) }
        Pending.drop(context, id)
    }

    internal object Pending {
        private fun file(context: Context) = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.config/droiddeck/wincomponents-pending.json")

        @Synchronized fun read(context: Context): JSONObject =
            runCatching { JSONObject(file(context).readText()) }.getOrDefault(JSONObject()).let { it.optJSONObject("games") ?: JSONObject() }

        @Synchronized fun set(context: Context, key: String, ids: List<String>) {
            val games = read(context)
            if (ids.isEmpty()) games.remove(key) else games.put(key, JSONArray(ids))
            write(context, games)
        }

        @Synchronized fun drop(context: Context, id: String) {
            val games = read(context)
            for (k in games.keys().asSequence().toList()) {
                val left = games.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() - id
                if (left.isEmpty()) games.remove(k) else games.put(k, JSONArray(left))
            }
            write(context, games)
        }

        private fun write(context: Context, games: JSONObject) {
            val f = file(context)
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.writeText(JSONObject().put("games", games).toString())
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }


    private const val FINDINGS = "wincomp-findings.json"

    internal fun signature(context: Context, game: Library.SteamGame): String {
        val folder = game.gameFiles
        val parts = listOfNotNull(
            "font-requirements-v1",
            folder?.lastModified(),
            folder?.let { SteamRedists.manifest(it, game.appId)?.lastModified() },
            folder?.let { StoreGameSidecar.file(it).lastModified() },
            folder?.let { File(it, "fuel.json").lastModified() },
            SteamAppInfo.appinfoFile(context).lastModified(),
            folder?.let { SteamMatch.get(context, it.path) }?.let { "${it.appId}/${it.certainty}" },
            steamAppOf(context, game)?.let { SteamAppInfo.state(context, it) },
        )
        return parts.joinToString("|")
    }

    internal fun findings(context: Context, game: Library.SteamGame): List<WinCompSources.Finding> {
        val key = appKey(game) ?: return emptyList()
        val sig = signature(context, game)
        val all = readFindings(context)
        all.optJSONObject(key)?.takeIf { it.optString("sig") == sig }?.let { return decode(it.optJSONArray("findings")) }
        val fresh = WinCompSources.forGame(context, game)
        writeFindings(context, all.put(key, JSONObject().put("sig", sig).put("findings", encode(fresh))))
        return fresh
    }

    fun cachedFindings(context: Context, appKey: String): List<WinCompSources.Finding>? =
        readFindings(context).optJSONObject(appKey)?.let { decode(it.optJSONArray("findings")) }

    @Synchronized
    private fun readFindings(context: Context): JSONObject =
        runCatching { JSONObject(AtomicFile(File(context.filesDir, FINDINGS)).readFully().toString(Charsets.UTF_8)) }.getOrDefault(JSONObject())

    @Synchronized
    private fun writeFindings(context: Context, json: JSONObject) {
        val atomic = AtomicFile(File(context.filesDir, FINDINGS))
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out) }
    }

    private fun encode(list: List<WinCompSources.Finding>) = JSONArray().apply {
        list.forEach { f ->
            put(JSONObject().put("original", f.original).put("origin", f.origin.name).put("auto", f.auto).put("args", JSONArray(f.args))
                .apply { f.component?.let { put("component", it) } })
        }
    }

    private fun decode(arr: JSONArray?): List<WinCompSources.Finding> = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val origin = runCatching { WinCompSources.Origin.valueOf(o.optString("origin")) }.getOrNull() ?: return@mapNotNull null
        val args = o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        WinCompSources.Finding(o.optString("component").takeIf { it.isNotEmpty() }, o.optString("original"), origin, o.optBoolean("auto"), args)
    }


    fun beforeLaunch(context: Context, game: Library.SteamGame) = queueGame(context, game, asked = false)

    fun refreshLater(context: Context, game: Library.SteamGame) = queueGame(context, game, asked = true)

    fun sweep(context: Context, games: List<Library.SteamGame>) {
        val apps = games.mapNotNull { steamAppOf(context, it) }.distinct()
        if (apps.isNotEmpty() && SteamAppInfo.needLookup(context, apps).isNotEmpty()) enqueue(context, Job(null, null, false, seq.incrementAndGet(), pics = apps))
        games.forEach { queueGame(context, it, asked = false) }
    }

    private fun steamAppOf(context: Context, game: Library.SteamGame): Int? =
        if (game.library != Library.ADDED) game.appId.takeIf { it > 0 }
        else game.gameFiles?.let { SteamMatch.get(context, it.path) }?.appId

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun constrained(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isPowerSaveMode == true) return true
        if (Build.VERSION.SDK_INT >= 29 && (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE) return true
        return context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
    }
}
