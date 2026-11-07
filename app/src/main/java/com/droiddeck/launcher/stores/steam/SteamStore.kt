package com.droiddeck.launcher.stores.steam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.storage.SteamStorage
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoreSizes
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.download.SavedQueue
import com.droiddeck.launcher.stores.formatBytes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Games land in the active client's own libraries, registered as the client does, and only while no session can run the client. */
internal object SteamStore {
    private const val TAG = "SteamStore"
    private const val PREFS = "steam-store"
    internal const val JOURNAL = "steam-store/journal"
    private const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/"
    private const val LIBRARY = "library"
    private const val LIBRARY_LABEL = "libraryLabel"
    private val SIGNED_OUT = setOf("no_saved_login", "remember_me_off", "decrypt_failed")

    var installed by mutableStateOf<Map<Int, SteamLibraryFolders.Installed>>(emptyMap())
        private set

    var picking by mutableStateOf<CatalogItem?>(null)

    class Plan(val installDir: String, val downloadBytes: Long, val installBytes: Long, val buildId: String) {
        companion object {
            fun of(json: JSONObject): Plan? {
                if (!json.optBoolean("ok")) return null
                val dir = json.optString("installDir").takeIf { SteamStore.validDir(it) } ?: return null
                return Plan(dir, json.optLong("downloadBytes"), json.optLong("installBytes"), json.optString("buildId"))
            }
        }
    }

    private val plans = ConcurrentHashMap<Int, Plan>()
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "steam-store") }
    @Volatile private var rejectedThisRun: String? = null

    fun key(appId: Int): String = "${Store.STEAM.id}:$appId"

    private fun validDir(dir: String) = dir.isNotBlank() && dir != "." && dir != ".." && '/' !in dir && '\\' !in dir

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toast(context: Context, @StringRes text: Int) {
        val app = context.applicationContext
        StoresState.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }

    internal fun isSignInError(error: String) = error in SIGNED_OUT || error.startsWith("logon_failed")

    internal fun errorText(context: Context, answer: JSONObject): String {
        val error = answer.optString("error")
        return when {
            error == SteamEngine.MISSING -> SteamEngine.MISSING_TEXT
            isSignInError(error) -> context.getString(R.string.stores_login_title, Store.STEAM.label)
            error.isBlank() -> "Steam: install failed"
            else -> error
        }
    }

    private fun fingerprint(context: Context): String {
        val users = SteamClient.loginUsers(context)
        val registry = SteamClient.registry(context)
        return "${SteamClient.activeArch(context)}:${users.lastModified()}:${users.length()}:${registry.lastModified()}:${registry.length()}"
    }

    private fun savedAccount(context: Context): String? {
        val print = fingerprint(context)
        if (print == rejectedThisRun || print == prefs(context).getString("rejected", null)) return null
        return runCatching { SteamLibraryFolders.savedAccount(SteamClient.loginUsers(context).readText()) }.getOrNull()
    }

    /** Mirrors the client's saved sign-in into the store account; reads storage. */
    fun syncAccount(context: Context): Boolean {
        val name = savedAccount(context)
        if (name == StoreAccounts.signedInAs(context, Store.STEAM)) return false
        if (name == null) StoreAccounts.clear(context, Store.STEAM)
        else StoreAccounts.write(context, Store.STEAM, JSONObject().put("display_name", name))
        SteamEngine.forget()
        StoresState.post {
            StoresState.library.remove(Store.STEAM)
            StoresState.problems.remove(Store.STEAM)
        }
        Log.i(TAG, if (name == null) "no saved Steam sign-in" else "Steam sign-in found")
        return true
    }

    internal fun signInFailed(context: Context, error: String) {
        if (error == SteamEngine.MISSING) return
        val print = fingerprint(context)
        rejectedThisRun = print
        if (error in SIGNED_OUT) prefs(context).edit().putString("rejected", print).apply()
        StoreAccounts.clear(context, Store.STEAM)
        StoresState.logLine("Steam: the saved sign-in was not accepted ($error)")
        StoresState.post {
            StoresState.accounts.remove(Store.STEAM)
            StoresState.library.remove(Store.STEAM)
        }
    }

    fun poll(context: Context) {
        val app = context.applicationContext
        work.execute {
            val changed = runCatching { syncAccount(app) }.getOrDefault(false)
            runCatching { scanInstalled(app) }
            if (changed) StoresState.notifyLibraryChanged(app)
        }
    }

    private class Read(val modified: Long, val length: Long, val text: String)
    private val manifests = ConcurrentHashMap<String, Read>()

    private fun readManifest(file: File): String? {
        val modified = file.lastModified()
        val length = file.length()
        manifests[file.path]?.takeIf { it.modified == modified && it.length == length }?.let { return it.text }
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        manifests[file.path] = Read(modified, length, text)
        return text
    }

    /** Owned games count as installed when an appmanifest with StateFlags 4 is in one of the active client's libraries. */
    fun scanInstalled(context: Context) {
        val found = SteamLibraryFolders.installed(SteamClient.libraries(context), ::readManifest)
        StoresState.post {
            if (found != installed) installed = found
            for ((id, game) in found) if (game.sizeOnDisk > 0 && StoreSizes.onDisk[key(id)] != game.sizeOnDisk) StoreSizes.onDisk[key(id)] = game.sizeOnDisk
        }
    }

    fun downloading(): Boolean = StoresState.latestDownloads.any {
        it.store == Store.STEAM && (it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED)
    }

    /** A Steam store download holds every session that could start the Steam client. */
    fun blocksSession(intent: Intent?): Boolean {
        val mode = intent?.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM
        return mode != SessionService.MODE_RUN && downloading()
    }

    internal fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        if (force) plans.clear()
        work.execute {
            val answer = SteamEngine.library(app)
            if (!answer.optBoolean("ok")) {
                val error = answer.optString("error")
                if (isSignInError(error)) signInFailed(app, error)
                else StoresState.post {
                    StoresState.problems[Store.STEAM] = errorText(app, answer)
                    StoresState.library[Store.STEAM] = emptyList()
                }
                return@execute
            }
            val art = runCatching { Library.steamLibraryArt(app).toMap() }.getOrDefault(emptyMap())
            val items = items(answer.optJSONArray("games"), art)
            runCatching { scanInstalled(app) }
            StoresState.logLine("Steam: library has ${items.size} games")
            StoresState.post {
                StoresState.problems.remove(Store.STEAM)
                StoresState.library[Store.STEAM] = items
            }
        }
    }

    private fun items(games: JSONArray?, art: Map<Int, File>): List<CatalogItem> {
        val out = ArrayList<CatalogItem>()
        for (i in 0 until (games?.length() ?: 0)) {
            val game = games?.optJSONObject(i) ?: continue
            val id = game.optInt("appId").takeIf { it > 0 } ?: continue
            val type = game.optString("type").lowercase()
            if (type.isNotEmpty() && type != "game") continue
            val name = game.optString("name").takeIf { it.isNotBlank() } ?: continue
            out += CatalogItem(
                Store.STEAM, id.toString(), name,
                imageUrl = "$CDN$id/header.jpg",
                tallImageUrl = art[id]?.let { Uri.fromFile(it).toString() } ?: "$CDN$id/library_600x900.jpg",
                isFree = game.optBoolean("free"),
                storeUrl = "https://store.steampowered.com/app/$id/",
                owned = true,
            )
        }
        return out.distinctBy { it.id }.sortedBy { it.title.lowercase() }
    }

    internal fun shelves(context: Context): StoreShelves {
        val art = runCatching { Library.steamLibraryArt(context) }.getOrDefault(emptyList()).map { Uri.fromFile(it.second).toString() }
        return StoreShelves(art = art)
    }

    /** Reaches the network; call off the main thread. */
    internal fun plan(context: Context, appId: Int, language: String = SteamClient.language(context)): Pair<Plan?, JSONObject> {
        plans[appId]?.let { return it to JSONObject().put("ok", true) }
        val answer = SteamEngine.plan(context, appId, language)
        val plan = Plan.of(answer)
        val error = answer.optString("error")
        if (plan != null) plans[appId] = plan
        else if (isSignInError(error)) signInFailed(context, error)
        return plan to answer
    }

    internal fun plannedBytes(context: Context, item: CatalogItem): Long =
        item.id.toIntOrNull()?.let { plan(context, it).first?.installBytes } ?: 0L

    fun requestInstall(context: Context, item: CatalogItem) {
        if (SteamClient.clientMayRun()) {
            toast(context, R.string.steam_store_close_steam)
            return
        }
        picking = item
    }

    fun install(context: Context, item: CatalogItem, target: SteamClient.Target) {
        val app = context.applicationContext
        val appId = item.id.toIntOrNull() ?: return
        if (SteamClient.clientMayRun()) {
            toast(app, R.string.steam_store_close_steam)
            return
        }
        val plan = plans[appId]
        val needed = plan?.installBytes ?: 0L
        val tagged = item.copy(extra = item.extra + mapOf(LIBRARY to target.library.guest, LIBRARY_LABEL to target.label))
        val entry = DownloadEntry(
            Store.STEAM, item.id, item.title, cover = item.imageUrl,
            bytesTotal = plan?.downloadBytes ?: 0L, location = target.label,
        )
        SavedQueue.remember(app, tagged, target.library.host)
        if (StoreInstallRoot.insufficientSpace(needed, target.freeBytes)) {
            val error = app.getString(R.string.stores_storage_insufficient, formatBytes(needed), formatBytes(target.freeBytes))
            DownloadQueue.recordFailed(app, entry, error) { requestInstall(app, item) }
            return
        }
        DownloadQueue.enqueue(app, entry) { SteamInstallJob(app, appId, target.library.guest) }
    }

    internal fun restore(context: Context, item: CatalogItem) {
        val app = context.applicationContext
        val appId = item.id.toIntOrNull() ?: return
        val guest = item.extra[LIBRARY] ?: return
        SavedQueue.remember(app, item, null)
        val entry = DownloadEntry(Store.STEAM, item.id, item.title, cover = item.imageUrl, location = item.extra[LIBRARY_LABEL].orEmpty())
        DownloadQueue.enqueue(app, entry) { SteamInstallJob(app, appId, guest) }
    }

    fun uninstall(context: Context, appId: Int, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        if (SteamClient.clientMayRun()) {
            toast(app, R.string.steam_store_close_steam)
            return
        }
        val key = key(appId)
        if (key in StoresState.uninstalling) return
        StoresState.uninstalling[key] = 0f
        Thread({
            val answer = runCatching {
                val game = SteamLibraryFolders.installed(SteamClient.libraries(app))[appId]
                if (game == null) JSONObject().put("ok", true)
                else {
                    val engine = SteamClient.engineLibraries(app)
                    SteamEngine.uninstall(appId, SteamClient.root(app).absolutePath, engine.hostOf(game.library).absolutePath, game.library.guest, engine.json)
                }
            }.getOrElse { SteamEngine.failure(it.javaClass.simpleName) }
            val ok = answer.optBoolean("ok")
            StoresState.logLine(if (ok) "Steam: uninstalled $appId" else "Steam: uninstall of $appId failed: ${answer.optString("error")}")
            runCatching { scanInstalled(app) }
            StoresState.post {
                StoresState.uninstalling.remove(key)
                if (ok) StoreSizes.onDisk.remove(key)
                else Toast.makeText(app, errorText(app, answer), Toast.LENGTH_LONG).show()
                onDone()
            }
        }, "steam-uninstall").start()
    }

    /** Removes what a cancelled download left; a finished install stays. */
    internal fun discardPartial(context: Context, appId: Int, guest: String, host: File?, installDir: String?) {
        File(context.filesDir, "$JOURNAL/$appId").deleteRecursively()
        val library = host ?: runCatching {
            SteamClient.engineLibraries(context).libraries.firstOrNull { it.first.guest == guest }?.second
        }.getOrNull() ?: return
        val apps = File(library, "steamapps")
        val acf = File(apps, "appmanifest_$appId.acf")
        val manifest = acf.takeIf { it.isFile }?.let { f -> runCatching { SteamStorage.parseManifest(f, f.readText()) }.getOrNull() }
        if (manifest?.installed == true) return
        val dir = (installDir ?: manifest?.installDir ?: plans[appId]?.installDir)?.takeIf { validDir(it) }
        if (dir != null) StoreInstalls.deleteTree(File(apps, "common/$dir"))
        StoreInstalls.deleteTree(File(apps, "downloading/$appId"))
        if (manifest != null) acf.delete()
        StoresState.logLine("Steam: removed the unfinished download of $appId")
    }
}

internal object SteamBackend : StoreBackend {
    override val store: Store = Store.STEAM

    override fun signIn(context: Context) {
        StoresState.logLine("Steam: sign in from the Steam client")
    }

    override fun signOut(context: Context) = SteamEngine.forget()

    override fun syncLibrary(context: Context, force: Boolean) = SteamStore.syncLibrary(context, force)

    override fun loadShelves(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            val shelves = SteamStore.shelves(app)
            StoresState.post { StoresState.shelves[store] = shelves }
        }, "steam-shelves").start()
    }

    override fun search(context: Context, query: String): List<CatalogItem> =
        StoresState.library[store].orEmpty().filter { it.title.contains(query, ignoreCase = true) }

    override fun install(context: Context, item: CatalogItem, root: File) = SteamStore.restore(context, item)

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
