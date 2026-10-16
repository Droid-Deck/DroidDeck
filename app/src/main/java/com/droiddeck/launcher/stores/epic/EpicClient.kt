package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.storage.SteamStorage
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLog
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object EpicClient {
    private const val TAG = "EpicClient"
    const val DOWNLOAD_ID = "epic-games-launcher"
    const val DOWNLOAD_KEY = "epic:$DOWNLOAD_ID"
    const val PROGRAM = "/usr/local/bin/droiddeck-epic"
    const val PLAYING = "epic-playing"
    const val ACTION_PLAY_IN_STEAM = "com.droiddeck.launcher.EPIC_PLAY_IN_STEAM"
    private const val SETTINGS = "settings/app-settings.json"
    private const val STOCK_INSTALL_DIR = "/root/Games"
    private const val FEX_DIR = "FEX-Emu"
    private const val FEX_WANTED = "root/.config/droiddeck/fex-wanted"
    private const val ICON = "epic-games-launcher.png"
    const val LAUNCHER_APP_ID = 0
    private const val PREF_CHECKED = "client_checked_at"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val IMPORT_EVERY_MS = 15_000L
    private const val STATE = "root/.local/share/droiddeck-epic"
    private const val DATA = "root/.local/share/com.epicgames.eglv2"
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "epic-client") }
    @Volatile private var importedAt = 0L

    data class Status(
        val installed: Boolean = false,
        val version: String? = null,
        val runtimeVersion: String? = null,
    )

    var status by mutableStateOf(Status())
        private set

    fun stateDir(context: Context): File = File(LinuxRuntime.rootDir(context), STATE)

    fun dataDir(context: Context): File = File(LinuxRuntime.rootDir(context), DATA)

    fun downloadsDir(context: Context): File = File(stateDir(context), "downloads")

    fun guestPath(context: Context, file: File): String? {
        val root = LinuxRuntime.rootDir(context).absolutePath
        val path = file.absolutePath
        return if (path.startsWith("$root/")) "/" + path.removePrefix("$root/") else null
    }

    private fun ready(dir: File): Boolean = File(dir, ".droiddeck-ready").isFile

    internal fun canHandOff(context: Context): Boolean = EpicCredentialStore.isLoggedIn(context.applicationContext)

    fun read(context: Context): Status {
        val app = context.applicationContext
        if (!LinuxRuntime.isInstalled(app)) return Status()
        val state = stateDir(app)
        val current = runCatching { JSONObject(File(state, "current.json").readText()) }.getOrNull()
        val version = current?.optString("app")?.ifEmpty { null }
        val runtimeVersion = current?.optString("runtime")?.ifEmpty { null }
        val installed = version != null && runtimeVersion != null &&
            ready(File(state, "app/$version")) && ready(File(state, "runtime/$runtimeVersion"))
        return Status(installed, version, runtimeVersion)
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        work.execute {
            val next = runCatching { read(app) }.getOrNull() ?: return@execute
            if (next.installed && System.currentTimeMillis() - importedAt >= IMPORT_EVERY_MS) {
                importedAt = System.currentTimeMillis()
                runCatching { EpicClientLibrary.importClientGames(app) }
                    .onFailure { Log.w(TAG, "client library scan failed: ${it.javaClass.simpleName}") }
            }
            StoresState.post { if (status != next) status = next }
        }
    }

    fun ensure(context: Context) {
        val app = context.applicationContext
        if (!LinuxRuntime.isInstalled(app)) { refresh(app); return }
        work.execute {
            val before = read(app)
            StoresState.post { if (status != before) status = before }
            if (DownloadQueue.isActive(DOWNLOAD_KEY) || !before.installed) return@execute
            syncLibrary(app)
            val prefs = EpicPrefs.get(app)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(PREF_CHECKED, 0L) < CHECK_EVERY_MS) return@execute
            val latest = EpicClientSources.launcher()?.version
            prefs.edit().putLong(PREF_CHECKED, if (latest != null) now else 0L).apply()
            val after = read(app)
            StoresState.post { if (status != after) status = after }
            if (updateWanted(after, latest)) StoresState.post { enqueue(app) }
        }
    }

    internal fun updateWanted(current: Status, latest: String?): Boolean =
        current.installed && latest != null && latest != current.version

    fun install(context: Context) {
        val app = context.applicationContext
        if (status.installed) return
        enqueue(app)
    }

    private fun syncLibrary(app: Context) {
        if (launcherRunning()) return
        EpicClientLibrary.sync(app)
    }

    private fun enqueue(app: Context) {
        if (DownloadQueue.isActive(DOWNLOAD_KEY)) return
        val entry = DownloadEntry(
            Store.EPIC, DOWNLOAD_ID, app.getString(R.string.epic_client_title),
            location = StoreInstallRoot.labelFor(app, stateDir(app)),
        )
        DownloadQueue.enqueue(app, entry) { EpicClientInstaller(app) }
    }

    internal fun installed(context: Context) {
        val app = context.applicationContext
        EpicPrefs.get(app).edit().putLong(PREF_CHECKED, System.currentTimeMillis()).apply()
        refresh(app)
        work.execute { syncLibrary(app) }
    }

    fun libraryChanged(context: Context) {
        val app = context.applicationContext
        work.execute { if (read(app).installed) syncLibrary(app) }
    }

    fun openIntent(context: Context): Intent =
        Intent(context, SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
            .putExtra(SessionService.EXTRA_PROGRAM, PROGRAM)
            .putExtra(SessionService.EXTRA_PROGRAM_ARGS, arrayOf("run"))

    fun icon(context: Context, version: String?): File? =
        version?.let { File(stateDir(context), "app/$it/$ICON") }?.takeIf(File::isFile)

    fun launcherEntry(context: Context, name: String, current: Status = status, art: File? = icon(context, current.version)): Library.SteamGame? {
        if (!current.installed) return null
        return Library.SteamGame(
            appId = LAUNCHER_APP_ID, name = name, art = art, library = Library.EPIC_LAUNCHER,
            gameId = LAUNCHER_APP_ID.toLong(), source = Store.EPIC.id,
        )
    }

    fun withLauncher(games: List<Library.SteamGame>, launcher: Library.SteamGame?): List<Library.SteamGame> {
        if (launcher == null) return games
        val at = games.indexOfFirst { it.source == Store.EPIC.id }.takeIf { it >= 0 } ?: games.size
        return games.subList(0, at) + launcher + games.subList(at, games.size)
    }

    fun launcherRunning(): Boolean =
        SessionState.running && !SessionState.stopRequested && SessionState.mode == SessionService.MODE_RUN && SessionState.program == PROGRAM

    fun playing(context: Context): Boolean = File(LinuxRuntime.sessionRoot(context), PLAYING).exists()

    fun fexReady(context: Context): Boolean {
        val usr = File(LinuxRuntime.rootDir(context), "usr/bin")
        if (File(usr, "FEXServer").isFile && (File(usr, "FEX").isFile || File(usr, "FEXInterpreter").isFile)) return true
        return Library.steamLibraries(context).any { (library, _) ->
            val manifest = File(library, "steamapps/appmanifest_${SteamStorage.FEX}.acf")
            val dir = runCatching { SteamStorage.parseManifest(manifest, manifest.readText())?.installDir }.getOrNull()
            listOfNotNull(dir, FEX_DIR).any { File(library, "steamapps/common/$it").isDirectory }
        }
    }

    fun requestFex(context: Context) {
        runCatching {
            File(LinuxRuntime.rootDir(context), FEX_WANTED).apply { parentFile?.mkdirs() }.writeText("1\n")
        }.onFailure { Log.w(TAG, "could not ask Steam for FEX: ${it.javaClass.simpleName}") }
    }

    fun prepareInstallDir(context: Context): String? {
        val app = context.applicationContext
        val dirs = StoreInstallRoot.targets(app).mapNotNull { t ->
            val folder = StoreInstallRoot.storeDir(t.root, Store.EPIC)
            AddedGames.guestPath(app, folder)?.let { t.root.absolutePath to (folder to it) }
        }.toMap()
        if (dirs.isEmpty()) return null
        val chosen = dirs[SessionPrefs.storesInstallTarget(app)] ?: dirs[StoreInstallRoot.internalRoot(app).absolutePath] ?: dirs.values.first()
        val file = File(dataDir(app), SETTINGS)
        val settings = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
        val current = settings.optString("defaultInstallDir").trimEnd('/')
        if (current.isNotEmpty() && current != STOCK_INSTALL_DIR && dirs.values.none { it.second == current } && !(current.endsWith("/${Store.EPIC.shortLabel}") && (current.startsWith("/mnt/droiddeck-") || current.startsWith("/root/Storage/")))) return current
        if (!chosen.first.isDirectory && !chosen.first.mkdirs()) return null
        if (current == chosen.second) return current
        return runCatching {
            file.parentFile?.mkdirs()
            val staged = File(file.path + ".tmp")
            staged.writeText(settings.put("defaultInstallDir", chosen.second).toString(2))
            check(staged.renameTo(file))
            Log.i(TAG, "the Epic Games Launcher installs games to ${chosen.second}")
            chosen.second
        }.onFailure { Log.w(TAG, "could not set the launcher's install folder: ${it.javaClass.simpleName}") }.getOrNull()
    }

    fun playInSteam(context: Context, id: String): JSONObject {
        if (!launcherRunning()) return JSONObject().put("ok", false).put("reason", "no-launcher")
        runCatching { EpicClientLibrary.importClientGames(context) }.onFailure { Log.w(TAG, "client library scan failed: ${it.javaClass.simpleName}") }
        val game = steamShortcut(Library.launchableGames(context), id)
            ?: return JSONObject().put("ok", false).put("reason", "not-registered")
        handToSteam(context, game.gameIdString)
        Log.i(TAG, "the Epic Games Launcher hands $id to Steam as ${game.gameIdString}")
        return JSONObject().put("ok", true)
    }

    fun runSteamGame(context: Context, id: String): JSONObject {
        if (!launcherRunning()) return JSONObject().put("ok", false).put("reason", "no-launcher")
        if (!com.droiddeck.launcher.frontend.GameLaunchLink.validId(id)) return JSONObject().put("ok", false).put("reason", "bad-id")
        handToSteam(context, id)
        Log.i(TAG, "the Epic Games Launcher hands Steam game $id to Steam")
        return JSONObject().put("ok", true)
    }

    private fun handToSteam(context: Context, gameId: String) {
        context.startActivity(Intent(context, com.droiddeck.launcher.MainActivity::class.java)
            .setAction(ACTION_PLAY_IN_STEAM)
            .putExtra(com.droiddeck.launcher.frontend.GameLaunchIntent.EXTRA_APP_ID, gameId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    internal fun steamShortcut(games: List<Library.SteamGame>, id: String): Library.SteamGame? =
        games.firstOrNull { it.source == Store.EPIC.id && it.storeId == id && it.gameId != LAUNCHER_APP_ID.toLong() }

    fun redirectOf(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host !in SIGN_IN_HOSTS || uri.path?.startsWith("/id/authorize") != true) return null
        val redirect = runCatching { uri.getQueryParameter("redirect_uri") }.getOrNull() ?: return null
        return redirect.takeIf { loopback(it) }
    }

    fun loopback(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host in LOOPBACK && uri.port in 1..65535 && uri.userInfo == null
    }

    fun signIn(context: Context, url: String, auto: Boolean): Boolean {
        if (redirectOf(url) == null) return false
        context.startActivity(
            Intent(context, StoreLoginActivity::class.java)
                .putExtra(StoreLoginActivity.EXTRA_STORE, Store.EPIC.id)
                .putExtra(StoreLoginActivity.EXTRA_CLIENT_URL, if (auto) withoutAccountPrompt(url) else url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Log.i(TAG, "sign-in for the Epic Games Launcher: ${StoreLog.redactUrl(url)}")
        return true
    }

    internal fun withoutAccountPrompt(url: String): String {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return url
        val kept = query.split('&').filter { it != "prompt=select_account" }
        if (kept.size == query.split('&').size) return url
        return url.substringBefore('?') + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    fun deliver(url: String): Boolean {
        if (!loopback(url)) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
            Log.i(TAG, "sign-in handed to the launcher: HTTP $code")
            code in 200..399
        } catch (e: Exception) {
            Log.w(TAG, "the launcher's sign-in listener did not answer: ${e.javaClass.simpleName}")
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun loginFlow(url: String): StoreLoginActivity.Flow? {
        val redirect = redirectOf(url) ?: return null
        val agent = runCatching { EpicNative.call("auth.config")?.optString("webViewUserAgent") }.getOrNull()?.ifEmpty { null }
        return ClientLogin(url, redirect, agent ?: StoreLoginActivity.CHROME_UA)
    }

    private class ClientLogin(
        override val startUrl: String,
        private val redirect: String,
        override val userAgent: String,
    ) : StoreLoginActivity.Flow {
        override fun isRedirect(url: String): Boolean =
            url == redirect || url.startsWith("$redirect?") || url.startsWith("$redirect#")

        override fun finish(activity: StoreLoginActivity, view: android.webkit.WebView, url: String): String? =
            if (deliver(url)) null else activity.getString(R.string.epic_client_sign_in_failed)
    }

    private val SIGN_IN_HOSTS = setOf("www.epicgames.com", "epicgames.com")
    private val LOOPBACK = setOf("127.0.0.1", "localhost")
}
