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

    /** The download queue as the Downloads page lists it, newest first. */
    var downloads by mutableStateOf<List<DownloadEntry>>(emptyList())
        internal set


    /** The native engine's version once probed, "" when the probe said it is missing, null before. */
    var engine by mutableStateOf<String?>(null)
        private set

    /** Downloads queued, running or paused across the stores (the rail badge). */
    val activeDownloads: Int get() = downloads.count { it.isActive }

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

    fun signIn(context: Context, store: Store) {
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

    /** An install waiting for the user to say where: the page shows the choice while this is set. */
    var pendingInstall by mutableStateOf<CatalogItem?>(null)

    /**
     * The way a page starts an install: with a card in the device the user is asked where (the
     * dialog's default is last time's pick, never a silent choice); with none it goes to internal
     * storage at once.
     */
    fun requestInstall(context: Context, item: CatalogItem) {
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
