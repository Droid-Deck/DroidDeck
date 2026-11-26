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
    private val REFUSED = Regex("logon_failed: eresult=(-?\\d+).*")
    /** InvalidPassword, AccessDenied, AccountNotFound, Revoked, Expired, AccountLogonDenied. */
    private val REFUSALS = setOf(5, 15, 18, 26, 27, 63)

    var picking by mutableStateOf<CatalogItem?>(null)

    class Plan(val installDir: String, val downloadBytes: Long, val installBytes: Long, val buildId: String, val linuxBuild: Boolean) {
        companion object {
            fun of(json: JSONObject): Plan? {
                if (!json.optBoolean("ok")) return null
                val dir = json.optString("installDir").takeIf { SteamStore.validDir(it) } ?: return null
                return Plan(dir, json.optLong("downloadBytes"), json.optLong("installBytes"), json.optString("buildId"), json.optBoolean("linuxBuild"))
            }
        }
    }

    internal const val LINUX_BUILD = "linux_build"

    /** Each plan with the language it was made for. */
    private val plans = ConcurrentHashMap<Int, Pair<String, Plan>>()
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "steam-store") }
    @Volatile private var rejectedThisRun: String? = null

    fun key(appId: Int): String = "${Store.STEAM.id}:$appId"

    private fun validDir(dir: String) = dir.isNotBlank() && dir != "." && dir != ".." && '/' !in dir && '\\' !in dir

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toast(context: Context, @StringRes text: Int) {
        val app = context.applicationContext
        StoresState.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }

    /** Steam refusing the saved login; a connection failure or a throttled logon leaves the account as it is. */
    internal fun isSignInError(error: String) =
        error in SIGNED_OUT || REFUSED.matchEntire(error)?.groupValues?.get(1)?.toIntOrNull() in REFUSALS

    internal fun errorText(context: Context, answer: JSONObject): String {
        val error = answer.optString("error")
        return when {
            error == SteamEngine.MISSING -> SteamEngine.MISSING_TEXT
            error == LINUX_BUILD -> context.getString(R.string.steam_store_linux_build)
            isSignInError(error) -> context.getString(R.string.stores_login_title, Store.STEAM.label)
            error.isBlank() -> "Steam: install failed"
            else -> error
        }
    }

    internal fun fingerprint(context: Context): String {
        val users = SteamClient.loginUsers(context)
        val registry = SteamClient.registry(context)
        return "${SteamClient.activeArch(context)}:${users.lastModified()}:${users.length()}:${registry.lastModified()}:${registry.length()}"
    }

    private fun savedAccount(context: Context): SteamLibraryFolders.SavedAccount? {
        val print = fingerprint(context)
        if (print == rejectedThisRun || print == prefs(context).getString("rejected", null)) return null
        val registry = runCatching { SteamClient.registry(context).readText() }.getOrDefault("")
        return runCatching { SteamLibraryFolders.savedLogin(SteamClient.loginUsers(context).readText(), registry) }.getOrNull()
    }

    /** Mirrors the client's saved sign-in into the store account; reads storage. A new name for the same account only relabels it. */
    fun syncAccount(context: Context): Boolean {
        val saved = savedAccount(context)
        val stored = StoreAccounts.read(context, Store.STEAM)
        val storedId = stored?.optString("steam_id").orEmpty()
        if (saved != null && saved.id == storedId) {
            if (saved.name != stored?.optString("display_name")) {
                StoreAccounts.write(context, Store.STEAM, JSONObject().put("display_name", saved.name).put("steam_id", saved.id))
            }
            return false
        }
        if (saved == null && stored == null) return false
        if (saved == null) StoreAccounts.clear(context, Store.STEAM)
        else StoreAccounts.write(context, Store.STEAM, JSONObject().put("display_name", saved.name).put("steam_id", saved.id))
        plans.clear()
        SteamEngine.forget()
        StoresState.post {
            StoresState.library.remove(Store.STEAM)
            StoresState.problems.remove(Store.STEAM)
        }
        Log.i(TAG, if (saved == null) "no saved Steam sign-in" else "Steam sign-in found")
        return true
    }

    internal fun signInFailed(context: Context, error: String) {
        if (!isSignInError(error)) return
        val print = fingerprint(context)
        if (SteamEngine.attempted?.let { it != print } == true) return
        rejectedThisRun = print
        if (error in SIGNED_OUT) prefs(context).edit().putString("rejected", print).apply()
        StoreAccounts.clear(context, Store.STEAM)
        StoresState.logLine("Steam: the saved sign-in was not accepted ($error)")
        StoresState.post {
            StoresState.accounts.remove(Store.STEAM)
            StoresState.library.remove(Store.STEAM)
        }
    }

    fun downloading(): Boolean = StoresState.latestDownloads.any {
        it.store == Store.STEAM && (it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED)
    }

    /** A Steam store download holds every session that could start the Steam client. */
    fun blocksSession(intent: Intent?): Boolean {
        val mode = intent?.getStringExtra(SessionService.EXTRA_MODE) ?: SessionService.MODE_STEAM
        return mode != SessionService.MODE_RUN && (downloading() || StoresState.uninstalling.keys.any { it.startsWith("${Store.STEAM.id}:") })
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

    /** Reaches the network; call off the main thread. */
    internal fun plan(context: Context, appId: Int, language: String = SteamClient.language(context)): Pair<Plan?, JSONObject> {
        val plan = plans[appId]?.takeIf { it.first == language }?.second ?: run {
            val answer = SteamEngine.plan(context, appId, language)
            val error = answer.optString("error")
            if (isSignInError(error)) signInFailed(context, error)
            Plan.of(answer)?.also { plans[appId] = language to it } ?: return null to answer
        }
        if (plan.linuxBuild && !protonMapped(context, appId)) return null to SteamEngine.failure(LINUX_BUILD)
        return plan to JSONObject().put("ok", true)
    }

    /** The engine installs the Windows depots; a game with a Linux build gets those from the client only when it is mapped to a Proton. */
    private fun protonMapped(context: Context, appId: Int): Boolean {
        val config = runCatching { File(SteamClient.root(context), "config/config.vdf").readText() }.getOrNull() ?: return false
        return SteamStorage.compatMappings(config)[appId.toString()]?.contains("proton", ignoreCase = true) == true
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
        val plan = plans[appId]?.second
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

    /** Removes a game like the client does; [fallback] deletes it when the engine cannot (no engine, or a library it does not map). */
    fun uninstall(context: Context, appId: Int, manifest: File?, fallback: () -> Boolean, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        if (SteamClient.clientMayRun()) {
            toast(app, R.string.steam_store_close_steam)
            return
        }
        val key = key(appId)
        if (key in StoresState.uninstalling) return
        StoresState.uninstalling[key] = 0f
        Thread({
            if (!DownloadQueue.holdForRemovalWhenFree(key)) {
                StoresState.logLine("Steam: kept $appId, its download is still running")
                StoresState.post { StoresState.uninstalling.remove(key); onDone() }
                return@Thread
            }
            val answer = try { runCatching {
                val libraries = SteamClient.libraries(app)
                val apps = manifest?.parentFile?.let(::canonical)
                val chosen = if (apps == null) libraries else libraries.filter { canonical(File(it.host, "steamapps")) == apps }
                val game = SteamLibraryFolders.installed(chosen)[appId]
                val engine = SteamClient.engineLibraries(app)
                val answer = if (game == null) SteamEngine.failure(SteamEngine.MISSING)
                    else SteamEngine.uninstall(appId, SteamClient.root(app).absolutePath, engine.hostOf(game.library).absolutePath, game.library.guest, engine.json)
                when {
                    answer.optString("error") != SteamEngine.MISSING -> answer
                    fallback() -> JSONObject().put("ok", true)
                    else -> SteamEngine.failure("remove_failed")
                }
            }.getOrElse { SteamEngine.failure(it.javaClass.simpleName) } } finally { DownloadQueue.releaseRemoval(key) }
            val ok = answer.optBoolean("ok")
            StoresState.logLine(if (ok) "Steam: uninstalled $appId" else "Steam: uninstall of $appId failed: ${answer.optString("error")}")
            StoresState.post {
                StoresState.uninstalling.remove(key)
                if (ok) StoreSizes.onDisk.remove(key)
                else Toast.makeText(app, errorText(app, answer), Toast.LENGTH_LONG).show()
                onDone()
            }
        }, "steam-uninstall").start()
    }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /** The engine keys an app's resume journal by library as `<journal>/<appId>/<appId>/<crc32 of the guest path>`. */
    private fun libraryKey(guest: String): String =
        "%08x".format(java.util.zip.CRC32().apply { update(guest.trimEnd('/').toByteArray()) }.value)

    private fun createdMarker(context: Context, appId: Int, guest: String) =
        File(context.filesDir, "$JOURNAL/$appId/created-${libraryKey(guest)}")

    /** Drops the resume state of one app in one library; other libraries' downloads of the app keep theirs. */
    internal fun forgetJournal(context: Context, appId: Int, guest: String, engineGuest: String? = null) {
        val app = File(context.filesDir, "$JOURNAL/$appId")
        val spelled = engineGuest ?: runCatching {
            SteamClient.engineLibraries(context).libraries.firstOrNull { SteamClient.sameLibrary(context, it.first, guest) }?.first?.guest
        }.getOrNull() ?: guest
        File(app, "$appId/${libraryKey(spelled)}").deleteRecursively()
        createdMarker(context, appId, guest).delete()
        File(app, "$appId").delete()
        app.delete()
    }

    /** Whether this install made the game folder in that library: decided by its first attempt and kept for every resume. */
    internal fun claimFolder(context: Context, appId: Int, guest: String, folder: File): Boolean {
        val marker = createdMarker(context, appId, guest)
        if (marker.isFile) return true
        if (folder.exists()) return false
        marker.parentFile?.mkdirs()
        return runCatching { marker.writeText(folder.name); true }.getOrDefault(false)
    }

    internal fun createdFolder(context: Context, appId: Int, guest: String): Boolean = createdMarker(context, appId, guest).isFile

    /**
     * Removes what a cancelled download left; a finished install stays. The game folder goes only when this download made it
     * ([createdDir]) and no other installed game uses it.
     */
    internal fun discardPartial(context: Context, appId: Int, guest: String, installDir: String?, createdDir: Boolean) {
        // An unmounted library keeps its journal and ownership record, so a later cancel can still clean it up.
        val library = runCatching {
            SteamClient.engineLibraries(context).libraries.firstOrNull { SteamClient.sameLibrary(context, it.first, guest) }?.second
        }.getOrNull() ?: return
        val apps = File(library, "steamapps")
        val acf = File(apps, "appmanifest_$appId.acf")
        val manifest = acf.takeIf { it.isFile }?.let { f -> runCatching { SteamStorage.parseManifest(f, f.readText()) }.getOrNull() }
        if (manifest?.installed == true) { forgetJournal(context, appId, guest); return }
        val claimed = runCatching { createdMarker(context, appId, guest).readText().trim() }.getOrNull()?.ifEmpty { null }
        val dir = (installDir ?: claimed ?: manifest?.installDir ?: plans[appId]?.second?.installDir)?.takeIf { validDir(it) }
        val shared = dir != null && apps.listFiles { f -> f.name.startsWith("appmanifest_") && f.name != acf.name }.orEmpty().any { f ->
            runCatching { SteamStorage.parseManifest(f, f.readText()) }.getOrNull()?.installDir == dir
        }
        if (dir != null && createdDir && !shared) StoreInstalls.deleteTree(File(apps, "common/$dir"))
        StoreInstalls.deleteTree(File(apps, "downloading/$appId"))
        if (manifest != null) acf.delete()
        forgetJournal(context, appId, guest)
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

    override fun loadShelves(context: Context, force: Boolean) {}

    override fun search(context: Context, query: String): List<CatalogItem> =
        StoresState.library[store].orEmpty().filter { it.title.contains(query, ignoreCase = true) }

    override fun install(context: Context, item: CatalogItem, root: File) = SteamStore.restore(context, item)

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
