package com.droiddeck.launcher.stores

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.download.DownloadEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Stores section's state for the life of the process (like the Flathub store's StoreState):
 * who is signed in where, each store's library and shelves, what is installed on disk and what is
 * being downloaded, so a download carries on - and its progress shows - while the user moves
 * around the launcher or plays something meanwhile.
 *
 * Everything here is read on the main thread by Compose; the workers post their results back.
 * The stores themselves plug in as [StoreBackend]s; a store with no backend in this build shows
 * its sign-in card and nothing else.
 */
object StoresState {
    private const val TAG = "Stores"
    private val main = Handler(Looper.getMainLooper())

    /** The signed-in account name per store; absent = signed out. */
    val accounts = mutableStateMapOf<Store, String>()

    /** Each store's owned games, once synced; absent until then. */
    val library = mutableStateMapOf<Store, List<CatalogItem>>()

    /** Each store's storefront shelves, once fetched. */
    val shelves = mutableStateMapOf<Store, StoreShelves>()

    /** What a store is doing for the user right now ("Syncing 12 games…"); absent when idle. */
    val status = mutableStateMapOf<Store, String>()

    /** Why the last sync or shelf fetch failed, per store; cleared by the next success. */
    val problems = mutableStateMapOf<Store, String>()

    /** Games the stores installed, as their sidecars say; rebuilt by [refresh]. */
    var installed by mutableStateOf<List<InstalledStoreGame>>(emptyList())
        private set

    /**
     * The download queue as the Downloads page lists it, newest first. Replaced only when a row is
     * added, removed, reordered or changes state - not on progress - so whatever iterates it does
     * not recompose on every byte. A row's progress is read through [download].
     */
    var downloads by mutableStateOf<List<DownloadEntry>>(emptyList())
        private set

    /** Downloads queued, running or paused across the stores (the rail badge); changes only when the count does. */
    var activeDownloads by mutableStateOf(0)
        private set

    private val rows = HashMap<String, androidx.compose.runtime.MutableState<DownloadEntry?>>()

    /** One row's live entry: a composable reading it recomposes for that row's progress only. Main thread. */
    fun download(key: String): DownloadEntry? = rows.getOrPut(key) { mutableStateOf(null) }.value

    /** Main thread: each row's holder set (equal entries change nothing), the list and the count only when they change. */
    /** The newest list as published, progress included; for the notification, which is not composed. */
    @Volatile var latestDownloads: List<DownloadEntry> = emptyList()
        private set
    private var notifiedAt = 0L

    internal fun publishDownloads(list: List<DownloadEntry>) {
        latestDownloads = list
        // The notification follows at most once a second.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - notifiedAt >= 1000) { notifiedAt = now; com.droiddeck.launcher.stores.download.StoreDownloadService.tick() }
        val keys = list.mapTo(HashSet()) { it.key }
        for (e in list) rows.getOrPut(e.key) { mutableStateOf(null) }.value = e
        for ((k, holder) in rows) if (k !in keys) holder.value = null
        val shape = { l: List<DownloadEntry> -> l.map { it.key to it.state } }
        if (shape(list) != shape(downloads)) downloads = list
        activeDownloads = list.count { it.isActive }
    }


    /** The native engine's version once probed, "" when the probe said it is missing, null before. */
    var engine by mutableStateOf<String?>(null)
        private set


    private val backends = HashMap<Store, StoreBackend>()

    /** A store's implementation, registered once as the app starts; null when this build lacks it. */
    fun backend(store: Store): StoreBackend? = backends[store]

    fun register(backend: StoreBackend) { backends[backend.store] = backend }

    /** Reads what is on disk for every store: sign-ins, installed games, the engine probe. Off the main thread. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        Thread({
            val found = Store.entries.mapNotNull { store -> StoreAccounts.signedInAs(app, store)?.let { store to it } }
            val onDisk = StoreInstallRoot.gameFolders(app).mapNotNull { folder ->
                StoreGameSidecar.read(folder)?.takeIf { it.isInstalled }?.let { InstalledStoreGame(it, folder) }
            }
            val partial = StoreInstallRoot.unfinished(app)
            val version = if (StoresNative.available) StoresNative.version ?: "" else ""
            main.post {
                accounts.clear()
                found.forEach { (store, name) -> accounts[store] = name }
                installed = onDisk
                unfinished = partial
                engine = version
            }
        }, "stores-refresh").start()
    }

    fun isSignedIn(store: Store): Boolean = accounts.containsKey(store)

    /** Stores whose sign-in has run out (a launch could not get a code): their chip dims until the user signs in again. */
    val expired = mutableStateMapOf<Store, Boolean>()

    fun markSignInExpired(store: Store) = post { expired[store] = true }

    /** Installs that stopped before they finished; their Install button reads "Resume install". */
    var unfinished by mutableStateOf<List<StoreInstallRoot.Unfinished>>(emptyList())
        internal set

    fun isUnfinished(item: CatalogItem): Boolean = unfinished.any {
        it.store == item.store && (it.id == item.id || (it.id == null && it.folder.name == StoreInstallRoot.folderName(item.title, item.id)))
    }

    /** Deletes an unfinished install's folder and caches, as Cancel would; drops its row if there is one. */
    fun clearUnfinished(context: Context, item: CatalogItem) {
        val app = context.applicationContext
        val key = "${item.store.id}:${item.id}"
        if (com.droiddeck.launcher.stores.download.DownloadQueue.entry(key) != null) { com.droiddeck.launcher.stores.download.DownloadQueue.clear(app, key); return }
        val folder = unfinished.firstOrNull {
            it.store == item.store && (it.id == item.id || (it.id == null && it.folder.name == StoreInstallRoot.folderName(item.title, item.id)))
        }?.folder ?: return
        StoreInstalls.discard(app, folder, listOf(java.io.File(folder, ".chunks"), java.io.File(folder, ".gog_chunks"), StoreInstallRoot.scratchDir(app, item.store, item.id)))
    }

    fun installedGame(store: Store, id: String): InstalledStoreGame? = installed.firstOrNull { it.sidecar.store == store && it.sidecar.id == id }

    /** The user's library and the shelves for [store], from the cache first and the network when stale. */
    fun open(context: Context, store: Store, force: Boolean = false) {
        val backend = backends[store] ?: return
        val app = context.applicationContext
        if (!isSignedIn(store)) return
        if (force || !library.containsKey(store)) backend.syncLibrary(app, force)
        if (force || !shelves.containsKey(store)) backend.loadShelves(app, force)
    }

    /** A store not signed into: its public storefront only, for its sign-in page to show. */
    fun preview(context: Context, store: Store) {
        if (!shelves.containsKey(store)) backends[store]?.loadShelves(context.applicationContext, false)
    }

    fun signIn(context: Context, store: Store) {
        expired.remove(store)
        backends[store]?.signIn(context) ?: logLine("${store.label}: this build has no sign-in for it yet")
    }

    fun signOut(context: Context, store: Store) {
        val app = context.applicationContext
        Thread({
            backends[store]?.signOut(app)
            StoreAccounts.clear(app, store)
            main.post {
                accounts.remove(store); library.remove(store); status.remove(store); problems.remove(store)
                logLine("${store.label}: signed out")
            }
        }, "stores-signout").start()
    }

    /** A notification asked for the Downloads chip; the Stores page takes it and clears it. */
    var openDownloads by mutableStateOf(false)

    /**
     * Android 13+: notifications need permission. Asked once, the first time a download starts from
     * a screen; refused, downloads still run, without their notification.
     */
    private fun askForNotifications(context: Context) {
        val activity = context as? android.app.Activity ?: return
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val p = activity.getSharedPreferences("stores", Context.MODE_PRIVATE)
        if (p.getBoolean("asked_notifications", false)) return
        p.edit().putBoolean("asked_notifications", true).apply()
        runCatching { activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7102) }
    }

    /** An install waiting for the user to say where: the page shows the choice while this is set. */
    var pendingInstall by mutableStateOf<CatalogItem?>(null)

    /**
     * The way a page starts an install: with a card in the device the user is asked where (the
     * dialog's default is last time's pick, never a silent choice); with none it goes to internal
     * storage at once.
     */
    fun requestInstall(context: Context, item: CatalogItem) {
        askForNotifications(context)
        // A resume goes where the files already are; there is nothing to choose.
        if (isUnfinished(item)) { install(context, item); return }
        val targets = StoreInstallRoot.targets(context)
        if (targets.size > 1) pendingInstall = item else install(context, item, targets.first().root)
    }

    /** Starts an install into [root] (the internal root when null). */
    fun install(context: Context, item: CatalogItem, root: java.io.File? = null) {
        val app = context.applicationContext
        val target = root ?: StoreInstallRoot.installRoot(app)
        backends[item.store]?.install(app, item, target) ?: logLine("${item.store.label}: this build cannot install yet")
    }

    /** Removes the game's folder and its shortcut, then [onDone] on the main thread. */
    fun uninstall(context: Context, game: InstalledStoreGame, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        Thread({
            runCatching { backends[game.sidecar.store]?.uninstall(app, game) }
            StoreInstalls.uninstall(app, game)
            main.post(onDone)
        }, "stores-uninstall").start()
    }

    /** Told on the main thread when an install or removal changed what is on disk; the launcher rebuilds its Games list. */
    @Volatile var libraryListener: (() -> Unit)? = null

    /** A game landed or went: the installed set is re-read and the launcher told. Any thread. */
    fun notifyLibraryChanged(context: Context) {
        refresh(context)
        main.post { libraryListener?.invoke() }
    }

    /** Appends to the engine log with a clock, keeping the last 80 lines. Any thread. */
    fun logLine(raw: String) {
        // Every store line passes here, the engines' included: no token or full URL is kept.
        val text = StoreLog.redactLine(raw)
        Log.i(TAG, text)
        val app = appContext ?: return
        StoreLogFiles.append(app, "${synchronized(CLOCK) { CLOCK.format(Date()) }}  $text")
    }

    private var appContext: Context? = null

    /** Called once as the app starts: where the log file goes, and the old days pruned. */
    fun init(context: Context) {
        appContext = context.applicationContext
        StoreLogFiles.prune(context)
        // The EOS overlay an earlier build downloaded (656 MB) is gone with the feature.
        val overlay = java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.local/share/droiddeck/epic-overlay")
        if (overlay.exists()) Thread({ overlay.deleteRecursively(); Log.i(TAG, "removed the old EOS overlay download") }, "epic-overlay-cleanup").start()
        // Cloud saves a killed app never uploaded; not while a session (and maybe that game) runs.
        val app = context.applicationContext
        Thread({ CloudSaves.uploadAllDirty(app, "recovery") }, "cloud-recovery").start()
    }

    internal fun post(block: () -> Unit) = main.post(block)

    private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}

/** What one store contributes: sign-in, its library, its shelves, installs and removals. */
interface StoreBackend {
    val store: Store
    fun signIn(context: Context)
    fun signOut(context: Context)
    fun syncLibrary(context: Context, force: Boolean)
    fun loadShelves(context: Context, force: Boolean)
    /** Queues an install of [item] under [root] (a StoreInstallRoot target). */
    fun install(context: Context, item: CatalogItem, root: java.io.File)
    /** The store's own records of the install, before the folder goes (the queue row, cached ids). */
    fun uninstall(context: Context, game: InstalledStoreGame)
}
