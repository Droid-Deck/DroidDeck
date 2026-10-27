package com.droiddeck.launcher.stores.battlenet

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.runtime.BattleNet
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreAccounts
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object BattleNetClient {
    private const val TAG = "BattleNetClient"
    const val DOWNLOAD_ID = "battlenet-client"
    const val DOWNLOAD_KEY = "battlenet:$DOWNLOAD_ID"
    const val REQUEST = "battlenet-request"
    internal const val INSTALLER_URL = "https://downloader.battle.net/download/getInstallerForGame?os=win&gameProgram=BATTLENET_APP&version=Live"
    private const val SETUP = "root/.cache/droiddeck/battlenet/Battle.net-Setup.exe"
    private const val CONFIG = "pfx/drive_c/users/steamuser/AppData/Roaming/Battle.net/Battle.net.config"
    internal const val MIN_SETUP = 1L shl 20
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "battlenet-client") }

    private val sequence = AtomicLong()

    @Volatile var opener: ((List<String>) -> Unit)? = null

    fun setup(context: Context): File = File(LinuxRuntime.rootDir(context), SETUP)

    internal fun setupReady(file: File): Boolean = runCatching {
        file.length() >= MIN_SETUP && file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte()
        }
    }.getOrDefault(false)

    internal fun account(config: String?): String? {
        val client = runCatching { JSONObject(config ?: return null).optJSONObject("Client") }.getOrNull() ?: return null
        if (client.optString("AutoLogin") != "true") return null
        return client.optString("SavedAccountNames").split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    private fun config(context: Context) = File(BattleNet.prefix(context), CONFIG)

    fun signedInAs(context: Context): String? =
        if (!BattleNet.installed(context)) null
        else account(runCatching { config(context).readText() }.getOrNull())?.let(StoreAccounts::maskEmail)

    fun syncAccount(context: Context): Boolean {
        val name = signedInAs(context)
        if (name == StoreAccounts.signedInAs(context, Store.BATTLENET)) return false
        if (name == null) StoreAccounts.clear(context, Store.BATTLENET)
        else StoreAccounts.write(context, Store.BATTLENET, JSONObject().put("display_name", name))
        Log.i(TAG, if (name == null) "Battle.net is signed out" else "Battle.net is signed in")
        return true
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        work.execute {
            if (runCatching { syncAccount(app) }.getOrDefault(false)) StoresState.notifyLibraryChanged(app)
        }
    }

    fun signIn(context: Context) {
        val app = context.applicationContext
        work.execute {
            if (BattleNet.installed(app) || setupReady(setup(app))) {
                open(emptyList())
                return@execute
            }
            StoresState.post {
                if (DownloadQueue.isActive(DOWNLOAD_KEY)) return@post
                val entry = DownloadEntry(
                    Store.BATTLENET, DOWNLOAD_ID, app.getString(R.string.bnet_name),
                    location = StoreInstallRoot.labelFor(app, setup(app)),
                )
                DownloadQueue.enqueue(app, entry) { SetupDownload(app) }
                StoresState.openDownloads = true
            }
        }
    }

    fun signOut(context: Context) {
        val file = config(context)
        runCatching {
            val json = JSONObject(file.readText())
            json.optJSONObject("Client")?.put("AutoLogin", "false") ?: return@runCatching
            val staged = File(file.path + ".droiddeck")
            staged.writeText(json.toString(4))
            check(staged.renameTo(file))
            Log.i(TAG, "Battle.net will ask for a sign-in at its next start")
        }.onFailure { Log.w(TAG, "Battle.net's sign-in kept: ${it.javaClass.simpleName}") }
    }

    fun request(sessionRoot: File, args: List<String>): Boolean = runCatching {
        require(args.none { '\n' in it })
        val now = System.nanoTime()
        val name = "$REQUEST.%020d".format(java.util.Locale.ROOT, sequence.updateAndGet { maxOf(it + 1, now) })
        val staged = File(sessionRoot, "$name.tmp")
        staged.writeText(args.joinToString("\n") + "\n")
        check(staged.renameTo(File(sessionRoot, name)))
        Log.i(TAG, "handed ${args.firstOrNull()} to the open Battle.net")
    }.onFailure { Log.w(TAG, "Battle.net request not left: ${it.javaClass.simpleName}") }.isSuccess

    fun clearRequests(sessionRoot: File) {
        sessionRoot.listFiles { file -> file.name == REQUEST || file.name.startsWith("$REQUEST.") }?.forEach { it.delete() }
    }

    fun open(args: List<String>) {
        StoresState.post { opener?.invoke(args) }
    }

    fun library(context: Context, root: File = preferred(context)): List<String> =
        AddedGames.guestPath(context, StoreInstallRoot.storeDir(root, Store.BATTLENET))?.let { listOf("--library", it) }.orEmpty()

    internal fun preferred(context: Context): File {
        val second = StoreInstallRoot.libraryRoot(context)?.absolutePath
        return StoreInstallRoot.targets(context).firstOrNull { it.root.absolutePath == second && (it.root.isDirectory || it.root.mkdirs()) }?.root
            ?: StoreInstallRoot.internalRoot(context)
    }

    fun installGame(context: Context, uid: String, root: File) {
        val app = context.applicationContext
        work.execute { open(library(app, root) + listOf("install-game", uid)) }
    }

    internal fun downloaded(context: Context) {
        Log.i(TAG, "Battle.net's installer downloaded from Blizzard")
        open(emptyList())
        StoresState.post { StoresState.notifyLibraryChanged(context) }
    }
}

object BattleNetBackend : StoreBackend {
    override val store: Store = Store.BATTLENET

    override fun signIn(context: Context) = BattleNetClient.signIn(context)

    override fun signOut(context: Context) {
        BattleNetClient.signOut(context)
        StoresState.post { StoresState.shelves.remove(store); StoresState.found.remove(store) }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            val items = runCatching { installed(app).map { item(app, it.title, owned = true) } }.getOrDefault(emptyList())
            StoresState.post { StoresState.library[store] = items }
        }, "battlenet-library").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            val known = shelves(app)
            StoresState.post { StoresState.shelves[store] = known }
            if (runCatching { BattleNet.fetchCovers(app) }.getOrDefault(false)) {
                val covered = shelves(app)
                val items = runCatching { installed(app).map { item(app, it.title, owned = true) } }.getOrDefault(emptyList())
                StoresState.post {
                    StoresState.shelves[store] = covered
                    StoresState.library[store] = items
                    StoresState.notifyLibraryChanged(app)
                }
            }
        }, "battlenet-shelves").start()
    }

    override fun search(context: Context, query: String): List<CatalogItem> =
        BattleNet.STORE.filter { it.name.contains(query, ignoreCase = true) }.map { item(context, it) }

    override fun install(context: Context, item: CatalogItem, root: File) {
        if (!BattleNetInstall.headless(item)) return BattleNetClient.installGame(context, item.id, root)
        val app = context.applicationContext
        BattleNetInstall.enqueue(app, item, StoreInstallRoot.folderFor(app, store, item.id, item.title, root))
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {
        if (!BattleNetInstall.uninstall(context, game)) BattleNetClient.open(emptyList())
    }

    fun installed(context: Context): List<BattleNet.Game> = if (BattleNet.installed(context)) BattleNet.games(context) else emptyList()

    fun installedGames(context: Context): List<InstalledStoreGame> = installed(context).map { game ->
        val art = BattleNet.art(context, game.title)
        val cover = art.cover?.let { Uri.fromFile(it).toString() }
        InstalledStoreGame(StoreGameSidecar(store, game.title.uid, game.name, "", cover = cover, hero = art.header?.let { Uri.fromFile(it).toString() } ?: cover), game.folder)
    }

    private fun shelves(context: Context): StoreShelves {
        val items = BattleNet.STORE.map { item(context, it) }
        return StoreShelves(trending = items, art = items.mapNotNull { it.tallImageUrl })
    }

    private fun item(context: Context, title: BattleNet.Title, owned: Boolean = false): CatalogItem {
        val art = BattleNet.art(context, title)
        return CatalogItem(
            store, title.uid, title.name,
            imageUrl = (art.header ?: art.cover)?.let { Uri.fromFile(it).toString() },
            tallImageUrl = art.cover?.let { Uri.fromFile(it).toString() },
            owned = owned,
        )
    }
}

internal class SetupDownload(private val app: Context) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    override fun run(handle: DownloadQueue.JobHandle): String? {
        handle.stage(DownloadStage.DOWNLOAD)
        if (!LinuxRuntime.isInstalled(app)) throw IllegalStateException(app.getString(R.string.epic_client_no_runtime))
        val file = BattleNetClient.setup(app)
        val partial = File(file.path + ".part")
        file.parentFile?.mkdirs()
        var attempt = 0
        while (!cancelled.get()) {
            try {
                fetch(partial, handle)
                if (cancelled.get()) break
                if (!BattleNetClient.setupReady(partial)) throw IOException("not a Windows program")
                if (!partial.renameTo(file)) throw IOException("could not keep the installer")
                BattleNetClient.downloaded(app)
                return file.absolutePath
            } catch (e: IOException) {
                partial.delete()
                if (cancelled.get()) break
                attempt++
                handle.log("Battle.net installer attempt $attempt: ${e.message ?: e.javaClass.simpleName}")
                if (attempt >= ATTEMPTS) throw IllegalStateException(app.getString(R.string.epic_client_download_failed, e.message ?: e.javaClass.simpleName))
                val until = System.currentTimeMillis() + (2_000L shl (attempt - 1))
                while (System.currentTimeMillis() < until && !cancelled.get()) Thread.sleep(100)
            }
        }
        partial.delete()
        return null
    }

    private fun fetch(partial: File, handle: DownloadQueue.JobHandle) {
        val conn = (URL(BattleNetClient.INSTALLER_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DroidDeck")
        }
        connection = conn
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            if (conn.url.protocol != "https") throw IOException("not served over HTTPS")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (!cancelled.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        handle.progress(done, total)
                    }
                    out.fd.sync()
                }
            }
            if (!cancelled.get() && total > 0 && done != total) throw IOException("the download ended early")
        } finally {
            connection = null
            runCatching { conn.disconnect() }
        }
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        runCatching { connection?.disconnect() }
    }

    private companion object {
        const val ATTEMPTS = 4
    }
}
