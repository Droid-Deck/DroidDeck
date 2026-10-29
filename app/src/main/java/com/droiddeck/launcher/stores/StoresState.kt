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
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.download.DownloadEntry

object StoresState {
    private const val TAG = "Stores"
    private const val SEARCH_DELAY_MS = 400L
    private const val LIBRARY_RETRY_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())

    val accounts = mutableStateMapOf<Store, String>()
    var accountsLoaded by mutableStateOf(false)
        private set

    val library = mutableStateMapOf<Store, List<CatalogItem>>()

    val shelves = mutableStateMapOf<Store, StoreShelves>()

    val found = mutableStateMapOf<Store, List<CatalogItem>>()

    val status = mutableStateMapOf<Store, String>()

    val problems = mutableStateMapOf<Store, String>()

    var installed by mutableStateOf<List<InstalledStoreGame>>(emptyList())
        private set

    var downloads by mutableStateOf<List<DownloadEntry>>(emptyList())
        private set

    var activeDownloads by mutableStateOf(0)
        private set

    private val rows = HashMap<String, androidx.compose.runtime.MutableState<DownloadEntry?>>()

    fun download(key: String): DownloadEntry? = rows.getOrPut(key) { mutableStateOf(null) }.value

    @Volatile var latestDownloads: List<DownloadEntry> = emptyList()
        private set
    private var notifiedAt = 0L

    internal fun publishDownloads(list: List<DownloadEntry>) {
        latestDownloads = list
        val now = android.os.SystemClock.uptimeMillis()
        if (now - notifiedAt >= 1000) { notifiedAt = now; com.droiddeck.launcher.stores.download.StoreDownloadService.tick() }
        val keys = list.mapTo(HashSet()) { it.key }
        for (e in list) rows.getOrPut(e.key) { mutableStateOf(null) }.value = e
        for ((k, holder) in rows) if (k !in keys) holder.value = null
        val shape = { l: List<DownloadEntry> -> l.map { it.key to it.state } }
        if (shape(list) != shape(downloads)) downloads = list
        activeDownloads = list.count { it.isActive }
    }


    var engine by mutableStateOf<String?>(null)
        private set


    private val backends = HashMap<Store, StoreBackend>()

    private val searchSeq = java.util.concurrent.ConcurrentHashMap<Store, Int>()

    fun backend(store: Store): StoreBackend? = backends[store]

    fun register(backend: StoreBackend) { backends[backend.store] = backend }

    fun refresh(context: Context) {
        val app = context.applicationContext
        com.droiddeck.launcher.stores.download.SavedQueue.restore(app)
        background("stores-refresh") {
            runCatching { com.droiddeck.launcher.stores.battlenet.BattleNetClient.syncAccount(app) }
            val found = Store.entries.mapNotNull { store -> StoreAccounts.displayName(app, store)?.let { store to it } }
            val onDisk = StoreInstallRoot.gameFolders(app).mapNotNull { folder ->
                StoreGameSidecar.read(folder)?.takeIf { it.isInstalled || it.store == Store.GOG && it.state == StoreGameSidecar.STATE_UPDATING }?.let { InstalledStoreGame(it, folder) }
            } + runCatching { com.droiddeck.launcher.stores.battlenet.BattleNetBackend.installedGames(app) }.getOrDefault(emptyList())
            val partial = StoreInstallRoot.unfinished(app)
            val version = if (StoresNative.available) StoresNative.version ?: "" else ""
            main.post {
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    val restored = found.toMap()
                    accounts.keys.toList().filter { it !in restored }.forEach { accounts.remove(it); libraryAsked.remove(it) }
                    accounts.putAll(restored)
                    installed = onDisk
                    unfinished = partial
                    engine = version
                    accountsLoaded = true
                }
                com.droiddeck.launcher.stores.gog.GogMaintenance.checkInstalled(app, onDisk)
            }
        }
    }

    fun isSignedIn(store: Store): Boolean = accounts.containsKey(store)

    fun notSignedInLine(store: Store): String =
        if (StoreAccounts.isUnavailable(store)) "${store.shortLabel} sign-in could not be read: try again."
        else "${store.shortLabel} session expired: sign in again."

    val expired = mutableStateMapOf<Store, Boolean>()

    fun markSignInExpired(store: Store) = post { expired[store] = true }

    var unfinished by mutableStateOf<List<StoreInstallRoot.Unfinished>>(emptyList())
        internal set

    fun isUnfinished(item: CatalogItem): Boolean = unfinished.any {
        it.store == item.store && (it.id == item.id || (it.id == null && it.folder.name == StoreInstallRoot.folderName(item.title, item.id)))
    }

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

    fun detailItem(store: Store, id: String): CatalogItem? = detailCatalogItem(
        store, id, library[store].orEmpty(),
        found[store].orEmpty() + if (store == Store.ITCH) com.droiddeck.launcher.stores.itch.ItchBrowseState.items + com.droiddeck.launcher.stores.itch.ItchCollections.items() else emptyList(),
        shelves[store],
    )

    fun open(context: Context, store: Store, force: Boolean = false) {
        val backend = backends[store] ?: return
        val app = context.applicationContext
        if (!isSignedIn(store)) return
        if (force || !library.containsKey(store)) backend.syncLibrary(app, force)
        if (force || !shelves.containsKey(store)) backend.loadShelves(app, force)
    }

    private val libraryAsked = java.util.concurrent.ConcurrentHashMap<Store, Long>()

    /** Once per store, again after a minute when the last sync never produced a library (offline, expired sign-in). */
    fun loadLibrary(context: Context, store: Store) {
        if (!isSignedIn(store) || library.containsKey(store)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val asked = libraryAsked[store]
        if (asked != null && now - asked < LIBRARY_RETRY_MS) return
        libraryAsked[store] = now
        backends[store]?.syncLibrary(context.applicationContext, false)
    }

    fun preview(context: Context, store: Store) {
        if (!shelves.containsKey(store)) backends[store]?.loadShelves(context.applicationContext, false)
    }

    fun search(context: Context, store: Store, query: String) {
        val backend = backends[store] ?: return
        val text = query.trim()
        if (text.isEmpty()) {
            searchSeq.remove(store)
            found.remove(store)
            return
        }
        val seq = (searchSeq[store] ?: 0) + 1
        searchSeq[store] = seq
        val app = context.applicationContext
        Thread({
            Thread.sleep(SEARCH_DELAY_MS)
            if (searchSeq[store] != seq) return@Thread
            val items = runCatching { backend.search(app, text) }.getOrDefault(emptyList())
            if (searchSeq[store] != seq) return@Thread
            main.post { found[store] = items }
        }, "stores-search").start()
    }

    fun signIn(context: Context, store: Store) {
        expired.remove(store)
        libraryAsked.remove(store)
        backends[store]?.signIn(context) ?: logLine("${store.label}: this build has no sign-in for it yet")
    }

    fun signOut(context: Context, store: Store) {
        val app = context.applicationContext
        background("stores-signout") {
            val backend = backends[store]
            backend?.signOut(app)
            if (store == Store.GOG) com.droiddeck.launcher.stores.gog.GogComet.replaceAccount { StoreAccounts.clear(app, store) }
            else if (store != Store.XBOX || backend == null) StoreAccounts.clear(app, store)
            if (store == Store.ITCH) {
                com.droiddeck.launcher.stores.itch.ItchCollections.clear()
                com.droiddeck.launcher.stores.itch.ItchUpdates.clear(app)
            }
            main.post {
                if (store == Store.XBOX && StoreAccounts.signedInAs(app, store) != null) return@post
                accounts.remove(store); library.remove(store); status.remove(store); problems.remove(store); libraryAsked.remove(store)
                logLine("${store.label}: signed out")
            }
        }
    }

    var openDownloads by mutableStateOf(false)
    /** A "store:id" key another page asks the Stores page to open. */
    var openGameRequest by mutableStateOf<String?>(null)

    private fun askForNotifications(context: Context) {
        val activity = context as? android.app.Activity ?: return
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (activity.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val p = activity.getSharedPreferences("stores", Context.MODE_PRIVATE)
        if (p.getBoolean("asked_notifications", false)) return
        p.edit().putBoolean("asked_notifications", true).apply()
        runCatching { activity.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7102) }
    }

    var pendingInstall by mutableStateOf<CatalogItem?>(null)
    var itchPicking by mutableStateOf<CatalogItem?>(null)

    fun requestInstall(context: Context, item: CatalogItem) {
        askForNotifications(context)
        if (item.store == Store.ITCH && item.extra["uploadId"].isNullOrEmpty()) { itchPicking = item; return }
        if (isUnfinished(item)) { install(context, item); return }
        val targets = StoreInstallRoot.targets(context)
        val preferred = StoreInstallRoot.defaultTarget(context)
        val wantedExternal = SessionPrefs.defaultStorage(context) == SessionPrefs.DEFAULT_STORAGE_EXTERNAL
        if (preferred != null) SessionPrefs.setStoresInstallTarget(context, preferred.root.absolutePath)
        when {
            preferred != null && !StoreLanguage.selectable(item.store) -> {
                install(context, item, preferred.root)
            }
            targets.size > 1 || wantedExternal || StoreLanguage.selectable(item.store) -> pendingInstall = item
            else -> install(context, item, targets.first().root)
        }
    }

    fun install(context: Context, item: CatalogItem, root: java.io.File? = null, language: String? = null) {
        val app = context.applicationContext
        val targetRoot = root ?: StoreInstallRoot.defaultTarget(app)?.root ?: StoreInstallRoot.installRoot(app)
        if (item.store == Store.BATTLENET && !com.droiddeck.launcher.stores.battlenet.BattleNetInstall.headless(item)) {
            backends[item.store]?.install(app, item, targetRoot)
            return
        }
        val folder = StoreInstallRoot.folderFor(app, item.store, item.id, item.title, targetRoot)
        val target = folder.parentFile!!.parentFile!!
        val code = language?.takeIf { it.isNotBlank() } ?: StoreLanguage.preferredCode(app)
        val tagged = if (StoreLanguage.selectable(item.store)) item.copy(extra = item.extra + (StoreLanguage.EXTRA to code)) else item
        val check = StoreInstallRoot.checkStorage(app, tagged, target)
        if (!check.enough) {
            val errorMsg = check.errorMessage ?: app.getString(com.droiddeck.launcher.R.string.stores_storage_insufficient, formatBytes(check.requiredBytes), formatBytes(check.availableBytes))
            val entry = com.droiddeck.launcher.stores.download.DownloadEntry(
                tagged.store,
                tagged.id,
                tagged.title,
                cover = tagged.imageUrl,
                bytesTotal = tagged.sizeBytes,
                location = StoreInstallRoot.labelFor(app, folder)
            )
            com.droiddeck.launcher.stores.download.SavedQueue.remember(app, tagged, target)
            com.droiddeck.launcher.stores.download.DownloadQueue.recordFailed(app, entry, errorMsg) {
                install(app, tagged, target, code)
            }
            return
        }
        com.droiddeck.launcher.stores.download.SavedQueue.remember(app, tagged, target)
        backends[tagged.store]?.install(app, tagged, target) ?: logLine("${tagged.store.label}: this build cannot install yet")
    }

    val uninstalling = mutableStateMapOf<String, Float>()

    fun uninstall(context: Context, game: InstalledStoreGame, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        val key = "${game.sidecar.store.id}:${game.sidecar.id}"
        uninstalling[key] = 0f
        background("stores-uninstall", onFailure = { main.post { uninstalling.remove(key); onDone() } }) {
            runCatching { backends[game.sidecar.store]?.uninstall(app, game) }
            if (game.sidecar.store == Store.BATTLENET && com.droiddeck.launcher.runtime.BattleNet.games(app).any { it.folder.absolutePath == game.folder.absolutePath }) {
                main.post { uninstalling.remove(key); onDone() }
                return@background
            }
            StoreInstalls.uninstall(app, game, progress = { f -> main.post { if (key in uninstalling) uninstalling[key] = f } }, removed = {
                main.post {
                    installed = installed.filterNot { it.folder.absolutePath == game.folder.absolutePath }
                    uninstalling.remove(key)
                    if (game.sidecar.store == Store.ITCH) com.droiddeck.launcher.stores.itch.ItchUpdates.applied(app, key)
                }
            })
            main.post(onDone)
        }
    }

    @Volatile var libraryListener: (() -> Unit)? = null

    fun notifyLibraryChanged(context: Context) {
        refresh(context)
        main.post { libraryListener?.invoke() }
    }

    fun logLine(raw: String) {
        val text = StoreLog.redactLine(raw)
        Log.i(TAG, text)
        val app = appContext ?: return
        StoreLogFiles.stamped(app, text)
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        StoreLogFiles.prune(context)
        val overlay = java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.local/share/droiddeck/epic-overlay")
        if (overlay.exists()) background("epic-overlay-cleanup") { overlay.deleteRecursively(); Log.i(TAG, "removed the old EOS overlay download") }
        val app = context.applicationContext
        Thread({
            runCatching { CloudSaves.uploadAllDirty(app, "recovery") }
                .onFailure { Log.w(TAG, "cloud save recovery failed", it) }
        }, "cloud-recovery").start()
    }

    internal fun post(block: () -> Unit) = main.post(block)

    /**
     * A worker thread whose failure is logged. An uncaught exception on a plain Thread reaches the crash
     * handler, which ends the whole app, and none of these jobs is worth that.
     */
    private fun background(name: String, onFailure: () -> Unit = {}, block: () -> Unit) {
        Thread({
            try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "$name failed", e)
                runCatching(onFailure)
            }
        }, name).start()
    }
}

interface StoreBackend {
    val store: Store
    fun signIn(context: Context)
    fun signOut(context: Context)
    fun syncLibrary(context: Context, force: Boolean)
    fun loadShelves(context: Context, force: Boolean)
    fun search(context: Context, query: String): List<CatalogItem> = emptyList()
    fun install(context: Context, item: CatalogItem, root: java.io.File)
    fun uninstall(context: Context, game: InstalledStoreGame)
}
