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

object StoresState {
    private const val TAG = "Stores"
    private val main = Handler(Looper.getMainLooper())

    val accounts = mutableStateMapOf<Store, String>()

    val library = mutableStateMapOf<Store, List<CatalogItem>>()

    val shelves = mutableStateMapOf<Store, StoreShelves>()

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

    fun backend(store: Store): StoreBackend? = backends[store]

    fun register(backend: StoreBackend) { backends[backend.store] = backend }

    fun refresh(context: Context) {
        val app = context.applicationContext
        com.droiddeck.launcher.stores.download.SavedQueue.restore(app)
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

    fun open(context: Context, store: Store, force: Boolean = false) {
        val backend = backends[store] ?: return
        val app = context.applicationContext
        if (!isSignedIn(store)) return
        if (force || !library.containsKey(store)) backend.syncLibrary(app, force)
        if (force || !shelves.containsKey(store)) backend.loadShelves(app, force)
    }

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
            if (store == Store.GOG) com.droiddeck.launcher.stores.gog.GogComet.replaceAccount { StoreAccounts.clear(app, store) }
            else StoreAccounts.clear(app, store)
            main.post {
                accounts.remove(store); library.remove(store); status.remove(store); problems.remove(store)
                logLine("${store.label}: signed out")
            }
        }, "stores-signout").start()
    }

    var openDownloads by mutableStateOf(false)

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

    fun requestInstall(context: Context, item: CatalogItem) {
        askForNotifications(context)
        if (isUnfinished(item)) { install(context, item); return }
        val targets = StoreInstallRoot.targets(context)
        if (targets.size > 1) pendingInstall = item else install(context, item, targets.first().root)
    }

    fun install(context: Context, item: CatalogItem, root: java.io.File? = null) {
        val app = context.applicationContext
        val folder = StoreInstallRoot.folderFor(app, item.store, item.id, item.title, root ?: StoreInstallRoot.installRoot(app))
        val target = folder.parentFile!!.parentFile!!
        val check = StoreInstallRoot.checkStorage(app, item, target)
        if (!check.enough) {
            val errorMsg = check.errorMessage ?: app.getString(com.droiddeck.launcher.R.string.stores_storage_insufficient, formatBytes(check.requiredBytes), formatBytes(check.availableBytes))
            val entry = com.droiddeck.launcher.stores.download.DownloadEntry(
                item.store,
                item.id,
                item.title,
                cover = item.imageUrl,
                bytesTotal = item.sizeBytes,
                location = StoreInstallRoot.labelFor(app, folder)
            )
            com.droiddeck.launcher.stores.download.SavedQueue.remember(app, item, target)
            com.droiddeck.launcher.stores.download.DownloadQueue.recordFailed(app, entry, errorMsg) {
                install(app, item, target)
            }
            return
        }
        com.droiddeck.launcher.stores.download.SavedQueue.remember(app, item, target)
        backends[item.store]?.install(app, item, target) ?: logLine("${item.store.label}: this build cannot install yet")
    }

    val uninstalling = mutableStateMapOf<String, Float>()

    fun uninstall(context: Context, game: InstalledStoreGame, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        val key = "${game.sidecar.store.id}:${game.sidecar.id}"
        uninstalling[key] = 0f
        Thread({
            runCatching { backends[game.sidecar.store]?.uninstall(app, game) }
            StoreInstalls.uninstall(app, game, progress = { f -> main.post { if (key in uninstalling) uninstalling[key] = f } }, removed = {
                main.post {
                    installed = installed.filterNot { it.folder.absolutePath == game.folder.absolutePath }
                    uninstalling.remove(key)
                }
            })
            main.post(onDone)
        }, "stores-uninstall").start()
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
        StoreLogFiles.append(app, "${synchronized(CLOCK) { CLOCK.format(Date()) }}  $text")
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        StoreLogFiles.prune(context)
        val overlay = java.io.File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(context), "root/.local/share/droiddeck/epic-overlay")
        if (overlay.exists()) Thread({ overlay.deleteRecursively(); Log.i(TAG, "removed the old EOS overlay download") }, "epic-overlay-cleanup").start()
        val app = context.applicationContext
        Thread({
            runCatching { CloudSaves.uploadAllDirty(app, "recovery") }
                .onFailure { Log.w(TAG, "cloud save recovery failed", it) }
        }, "cloud-recovery").start()
    }

    internal fun post(block: () -> Unit) = main.post(block)

    private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}

interface StoreBackend {
    val store: Store
    fun signIn(context: Context)
    fun signOut(context: Context)
    fun syncLibrary(context: Context, force: Boolean)
    fun loadShelves(context: Context, force: Boolean)
    fun install(context: Context, item: CatalogItem, root: java.io.File)
    fun uninstall(context: Context, game: InstalledStoreGame)
}
