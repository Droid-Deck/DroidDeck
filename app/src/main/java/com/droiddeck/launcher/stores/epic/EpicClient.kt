package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
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
import com.droiddeck.launcher.stores.WebCookies
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.SteamLiveShortcuts
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.CRC32

object EpicClient {
    private const val TAG = "EpicClient"
    const val DOWNLOAD_ID = "epic-games-launcher"
    const val DOWNLOAD_KEY = "epic:$DOWNLOAD_ID"
    const val PROGRAM = "/usr/local/bin/droiddeck-epic"
    const val PLAYING = "epic-playing"
    const val STEAM_LAUNCHER = "/usr/local/bin/droiddeck-epic-steam"
    const val OPEN = "epic-open"
    const val FOCUSED_APP = "steam-focused-app"
    private const val STEAM_DIR = "/usr/local/bin"
    private const val SETTINGS = "settings/app-settings.json"
    private const val STOCK_INSTALL_DIR = "/root/Games"
    private const val FEX_DIR = "FEX-Emu"
    private val FEX_PREFIXES = listOf("usr/", "", "files/usr/", "files/")
    private const val FEX_WANTED = "root/.config/droiddeck/fex-wanted"
    private const val ICON = "epic-games-launcher.png"
    const val LAUNCHER_APP_ID = 0
    private const val PREF_CHECKED = "client_checked_at"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val IMPORT_EVERY_MS = 15_000L
    private const val STATE = "root/.local/share/droiddeck-epic"
    private const val DATA = "root/.local/share/com.epicgames.eglv2"
    private const val AUTH = "auth/auth.json"
    private const val SIGNED_OUT = "signed-out"
    private const val AUTO_SIGN_IN = "auto-sign-in"
    private val UNINSTALL_PATHS = listOf("current.json", "app", "runtime", "downloads", "bin", "fex", "run", "logs", "status.json", "instance.json")
    private const val COOKIE_DOMAIN = ".epicgames.com"
    private val COOKIE_PATHS = listOf("/", "/id")
    private val COOKIE_URLS = listOf(
        "https://www.epicgames.com/id/login", "https://www.epicgames.com/", "https://epicgames.com/",
        "https://store.epicgames.com/", "https://accounts.epicgames.com/", "https://launcher.store.epicgames.com/",
    )
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

    fun signOut(context: Context): Boolean {
        val app = context.applicationContext
        val auth = File(dataDir(app), AUTH)
        val signedIn = launcherSignedIn(auth)
        if (signedIn) runCatching { File(stateDir(app), SIGNED_OUT).writeText("${System.currentTimeMillis() / 1000}\n") }
        auth.delete()
        File(stateDir(app), AUTO_SIGN_IN).delete()
        Log.i(TAG, if (signedIn) "signed the Epic Games Launcher out too" else "the Epic Games Launcher was already signed out")
        return signedIn
    }

    internal fun launcherSignedIn(auth: File): Boolean =
        runCatching { JSONObject(auth.readText()).optJSONObject("prod")?.optString("refresh_token").orEmpty().isNotEmpty() }.getOrDefault(false)

    fun forgetWebSignIn() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                WebCookies.expire(COOKIE_URLS, COOKIE_DOMAIN, COOKIE_PATHS) { cleared ->
                    Log.i(TAG, "cleared the Epic web sign-in ($cleared cookie lines)")
                }
            }.onFailure { Log.w(TAG, "could not clear the Epic web sign-in: ${it.javaClass.simpleName}") }
        }
    }

    internal fun expiredCookies(header: String?, host: String? = null): List<String> =
        WebCookies.expired(header, COOKIE_DOMAIN, COOKIE_PATHS, host)

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

    @Volatile var uninstalling = false

    fun install(context: Context) {
        val app = context.applicationContext
        if (status.installed || uninstalling) return
        enqueue(app)
    }

    private fun syncLibrary(app: Context) {
        if (launcherRunning(app)) return
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

    val shortcutAppId: Long = CRC32().apply { update("\"$STEAM_LAUNCHER\"${Library.EPIC_LAUNCHER_NAME}".toByteArray()) }.value or 0x80000000L

    fun gameId(appId: Long): String = java.lang.Long.toUnsignedString((appId shl 32) or 0x02000000L)

    fun steamListing(context: Context): JSONObject? {
        val current = read(context)
        if (!current.installed) return null
        val entry = JSONObject().put("name", Library.EPIC_LAUNCHER_NAME).put("exe", STEAM_LAUNCHER).put("folder", STEAM_DIR)
            .put("dir", STEAM_DIR).put("appid", shortcutAppId).put("seen", 0).put("native", true)
        icon(context, current.version)?.let { guestPath(context, it) }?.let { entry.put("art", JSONObject().put("icon", it)) }
        return entry
    }

    fun steamLauncherId(context: Context): Long? =
        SteamLiveShortcuts.ensure(context, Library.EPIC_LAUNCHER_NAME, STEAM_LAUNCHER, STEAM_DIR, compat = false, appId = shortcutAppId)

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

    private fun steamSession(): Boolean =
        SessionState.running && !SessionState.stopRequested && SessionState.mode == SessionService.MODE_STEAM

    fun launcherSession(): Boolean =
        SessionState.running && !SessionState.stopRequested && SessionState.mode == SessionService.MODE_RUN &&
            SessionState.program == com.droiddeck.launcher.session.NoSteamLaunch.EPIC

    fun uninstall(context: Context): Boolean {
        val app = context.applicationContext
        val state = stateDir(app)
        com.droiddeck.launcher.stores.download.DownloadQueue.clear(app, DOWNLOAD_KEY)
        val removed = UNINSTALL_PATHS.map { File(state, it) }
            .map { com.droiddeck.launcher.runtime.RuntimeFileTree.deleteWhatCan(it, null) }
            .all { it }
        val next = read(app)
        StoresState.post { status = next }
        Log.i(TAG, if (removed) "uninstalled the Epic Games Launcher" else "could not remove every Epic Games Launcher file")
        return removed && !next.installed
    }

    fun launcherRunning(context: Context): Boolean =
        (steamSession() || launcherSession()) && File(LinuxRuntime.sessionRoot(context), OPEN).exists()

    fun launcherFocused(context: Context): Boolean {
        val root = LinuxRuntime.sessionRoot(context)
        if (launcherSession()) return File(root, OPEN).exists()
        if (!steamSession()) return false
        val open = runCatching { File(root, OPEN).readText().trim() }.getOrNull() ?: return false
        val focused = runCatching { File(root, FOCUSED_APP).readText().trim() }.getOrNull() ?: return false
        return focused == (open.takeIf { it.length > 1 && it.all(Char::isDigit) } ?: shortcutAppId.toString())
    }

    fun playing(context: Context): Boolean = File(LinuxRuntime.sessionRoot(context), PLAYING).exists()

    fun fexReady(context: Context): Boolean {
        val usr = File(LinuxRuntime.rootDir(context), "usr/bin")
        if (File(usr, "FEXServer").isFile && fexBinary(usr)) return true
        return Library.steamLibraries(context).any { (library, _) ->
            val manifest = File(library, "steamapps/appmanifest_${SteamStorage.FEX}.acf")
            val dir = runCatching { SteamStorage.parseManifest(manifest, manifest.readText())?.installDir }.getOrNull()
            val roots = listOf(library, com.droiddeck.launcher.session.SecondaryLibrary.privateRoot(context.filesDir, library))
            listOfNotNull(dir, FEX_DIR).distinct().any { name ->
                roots.any { root ->
                    val tool = File(root, "steamapps/common/$name")
                    FEX_PREFIXES.any { fexBinary(File(tool, "${it}bin")) }
                }
            }
        }
    }

    private fun fexBinary(bin: File): Boolean = File(bin, "FEX").isFile || File(bin, "FEXInterpreter").isFile

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
        if (!steamSession()) return failed("no-steam")
        runCatching { EpicClientLibrary.importClientGames(context) }.onFailure { Log.w(TAG, "client library scan failed: ${it.javaClass.simpleName}") }
        val game = steamShortcut(AddedGames.scan(context), id) ?: return failed("not-registered")
        if (com.droiddeck.launcher.session.NoSteamLaunch.enabled(context, com.droiddeck.launcher.session.NoSteamLaunch.settingsId(game))) return failed("no-steam-game")
        if (!AddedGames.inSteam(context, game.appId) || SteamLiveShortcuts.known(context, game.appId) == false) return failed("not-in-steam")
        if (!SessionService.launchGame(context, gameId(game.appId))) return failed("busy")
        Log.i(TAG, "the Epic Games Launcher hands $id to Steam as ${gameId(game.appId)}")
        return JSONObject().put("ok", true)
    }

    fun runSteamGame(context: Context, id: String): JSONObject {
        if (!com.droiddeck.launcher.frontend.GameLaunchLink.validId(id)) return failed("bad-id")
        if (!steamSession()) return failed("no-steam")
        if (!SessionService.launchGame(context, id)) return failed("busy")
        Log.i(TAG, "the Epic Games Launcher hands Steam game $id to Steam")
        return JSONObject().put("ok", true)
    }

    private fun failed(reason: String): JSONObject = JSONObject().put("ok", false).put("reason", reason)

    internal fun steamShortcut(games: List<AddedGames.Game>, id: String): AddedGames.Game? =
        games.firstOrNull { it.source == Store.EPIC.id && it.storeId == id }

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

    internal fun loginFlow(url: String, browserAgent: String): StoreLoginActivity.Flow? {
        val redirect = redirectOf(url) ?: return null
        val agent = runCatching { EpicNative.call("auth.config")?.optString("webViewUserAgent") }.getOrNull()?.ifEmpty { null }
        return ClientLogin(url, redirect, agent ?: browserAgent)
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
