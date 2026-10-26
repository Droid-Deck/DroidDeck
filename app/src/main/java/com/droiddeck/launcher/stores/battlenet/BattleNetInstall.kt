package com.droiddeck.launcher.stores.battlenet

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.runtime.BattleNet
import com.droiddeck.launcher.runtime.GuestCommand
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SecondaryLibrary
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object BattleNetInstall {
    private const val TAG = "BattleNetInstall"
    internal const val PROGRESS = "BNET_PROGRESS "
    internal const val STATE = "BNET_STATE "
    internal const val DONE = "BNET_DONE "
    internal const val ERROR = "BNET_ERROR "
    private const val VERSIONS = "https://%s.version.battle.net/v2/products/%s/versions"
    private const val BUILD_INFO = ".build.info"
    private const val CHECK_AGAIN_MS = 30 * 60_000L
    private const val START_WAIT_MS = 60_000L

    internal val LOCALES = listOf(
        "enUS", "enGB", "esMX", "esES", "ptBR", "ptPT", "frFR", "deDE", "itIT", "ruRU", "jaJP", "koKR", "plPL", "trTR",
        "zhTW", "zhCN", "thTH", "arSA",
    )

    internal val checks = Executors.newSingleThreadExecutor { r -> Thread(r, "battlenet-updates") }
    internal val checkedAt = HashMap<String, Long>()

    val updates = mutableStateMapOf<String, Boolean>()

    fun product(id: String): String? = BattleNet.title(id)?.product

    fun headless(item: CatalogItem): Boolean = item.store == Store.BATTLENET && product(item.id) != null

    internal fun locale(locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        if (language == "zh") {
            val traditional = locale.script.equals("Hant", true) || locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")
            return if (traditional) "zhTW" else "zhCN"
        }
        val exact = language + locale.country.uppercase(Locale.ROOT)
        return LOCALES.firstOrNull { it == exact } ?: LOCALES.firstOrNull { it.startsWith(language) } ?: "enUS"
    }

    internal fun country(locale: Locale): Pair<String, String> {
        val two = locale.country.uppercase(Locale.ROOT)
        val three = runCatching { locale.isO3Country.uppercase(Locale.ROOT) }.getOrDefault("")
        return if (two.matches(Regex("[A-Z]{2}")) && three.matches(Regex("[A-Z]{3}"))) three to two else "USA" to "US"
    }

    internal fun binds(context: Context): List<String> {
        val out = ArrayList<String>(StoreInstallRoot.externalBindSpecs(context))
        val root = LinuxRuntime.rootDir(context)
        GameStorage.effective(context)?.let { library ->
            if (!File(library.path).isDirectory) throw IllegalStateException(context.getString(R.string.game_sync_err_game_storage))
            val content = SecondaryLibrary.contentPath(File(library.path)).path
            for (guest in SecondaryLibrary.CARD_GUESTS) {
                File(root, guest.removePrefix("/")).mkdirs()
                out += "$content:$guest"
            }
        }
        for (folder in AddedGames.roots(context)) {
            if (!folder.host.isDirectory || !folder.host.canRead()) continue
            File(root, folder.guest.removePrefix("/")).mkdirs()
            out += folder.host.path + ":" + folder.guest
        }
        return out
    }

    internal fun run(context: Context, args: List<String>, cancelled: AtomicBoolean?, onLine: (String) -> Unit): Int =
        GuestCommand.run(
            context, listOf(BattleNet.PROGRAM) + args, logName = "battlenet-agent",
            extraBinds = binds(context), fastPath = true, cancelled = cancelled, onLine = onLine,
        )

    fun enqueue(context: Context, item: CatalogItem, folder: File, update: Boolean = false) {
        val app = context.applicationContext
        val entry = DownloadEntry(
            Store.BATTLENET, item.id, item.title, cover = item.tallImageUrl ?: item.imageUrl,
            location = StoreInstallRoot.labelFor(app, folder),
        )
        DownloadQueue.enqueue(app, entry) { BattleNetInstaller(app, item, folder, update) }
    }

    fun update(context: Context, game: InstalledStoreGame) {
        val app = context.applicationContext
        Thread({
            val item = CatalogItem(
                Store.BATTLENET, game.sidecar.id, game.sidecar.title, game.sidecar.hero, tallImageUrl = game.sidecar.cover, owned = true,
                extra = mapOf(INSTALL_UID to installUid(app, game)),
            )
            game.folder.parentFile?.parentFile?.let { com.droiddeck.launcher.stores.download.SavedQueue.remember(app, item, it) }
            enqueue(app, item, game.folder, update = true)
        }, "battlenet-update").start()
    }

    internal const val INSTALL_UID = "installUid"

    internal fun installUid(context: Context, game: InstalledStoreGame): String =
        runCatching { BattleNet.games(context).firstOrNull { it.uid == game.sidecar.id }?.installUid }.getOrNull() ?: game.sidecar.id

    fun uninstall(context: Context, game: InstalledStoreGame): Boolean {
        if (product(game.sidecar.id) == null || !BattleNet.installed(context)) return false
        val done = agentUninstall(context, installUid(context, game))
        if (done) StoresState.post { StoresState.notifyLibraryChanged(context.applicationContext) }
        return done
    }

    internal fun agentUninstall(context: Context, uid: String): Boolean {
        val stop = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val guard = Thread({
            try {
                Thread.sleep(START_WAIT_MS)
                if (!started.get()) stop.set(true)
            } catch (_: InterruptedException) {
            }
        }, "battlenet-uninstall-wait").apply { isDaemon = true; start() }
        val code = runCatching {
            run(context, listOf("agent-uninstall", uid), stop) { line ->
                if (line == STATE + "starting") started.set(true)
                if (line.startsWith(DONE)) done.set(true)
            }
        }.onFailure { Log.w(TAG, "uninstall $uid", it) }.getOrDefault(-1)
        guard.interrupt()
        Log.i(TAG, "uninstall $uid: ${if (done.get()) "done" else if (stop.get()) "Battle.net stayed busy" else "exit $code"}")
        return done.get()
    }

    internal fun buildKey(text: String): Pair<String, String>? {
        val rows = text.lines().filter { it.isNotBlank() }
        if (rows.size < 2) return null
        val head = rows[0].split('|').map { it.substringBefore('!') }
        val branch = head.indexOf("Branch")
        val active = head.indexOf("Active")
        val key = head.indexOf("Build Key")
        if (branch < 0 || key < 0) return null
        val row = rows.drop(1).map { it.split('|') }.firstOrNull { active < 0 || it.getOrNull(active) == "1" } ?: return null
        val region = row.getOrNull(branch)?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: return null
        val build = row.getOrNull(key)?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return null
        return region to build
    }

    internal fun latestBuild(text: String, region: String): String? {
        val rows = text.lines().filter { it.isNotBlank() && !it.startsWith("##") }
        if (rows.isEmpty()) return null
        val head = rows[0].split('|').map { it.substringBefore('!') }
        val at = head.indexOf("Region")
        val key = head.indexOf("BuildConfig")
        if (at < 0 || key < 0) return null
        return rows.drop(1).map { it.split('|') }.firstOrNull { it.getOrNull(at) == region }?.getOrNull(key)
            ?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
    }

    fun checkUpdate(context: Context, game: InstalledStoreGame, force: Boolean = false) = check(game.sidecar.id, game.folder, force)

    internal fun check(id: String, folder: File, force: Boolean) {
        val product = product(id) ?: return
        val now = System.currentTimeMillis()
        synchronized(checkedAt) {
            if (!force && now - (checkedAt[id] ?: 0L) < CHECK_AGAIN_MS) return
            checkedAt[id] = now
        }
        fun retryLater() = synchronized(checkedAt) { if (checkedAt[id] == now) checkedAt.remove(id) }
        checks.execute {
            val local = runCatching { buildKey(File(folder, BUILD_INFO).readText()) }.getOrNull()
                ?: return@execute retryLater()
            val latest = runCatching { latestBuild(fetch(VERSIONS.format(Locale.ROOT, local.first, product)), local.first) }
                .onFailure { Log.w(TAG, "versions of $product: ${it.javaClass.simpleName}") }.getOrNull()
                ?: return@execute retryLater()
            val behind = latest != local.second
            Log.i(TAG, "$id: ${if (behind) "update available" else "up to date"}")
            StoresState.post { if (behind) updates[id] = true else updates.remove(id) }
        }
    }

    private fun fetch(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "DroidDeck")
        }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun failure(context: Context, reason: String?, code: Int): String {
        val detail = when {
            reason == null -> "exit $code"
            reason == "client" -> return context.getString(R.string.bnet_dl_client_first)
            reason.startsWith("agent ") -> context.getString(R.string.bnet_dl_agent_error, reason.removePrefix("agent "))
            else -> reason.removePrefix("stopped ")
        }
        return context.getString(R.string.bnet_dl_failed, detail)
    }

    internal fun localeTag(context: Context): String = locale(StoreLanguage.localeFor(null, context))

    internal enum class Step { PREPARING, DOWNLOADING, APPLYING }

    internal fun step(state: Int, phase: Int, current: Long, total: Long): Step = when {
        state == DONE_STATE || phase == APPLY_PHASE || total > 0L && current >= total -> Step.APPLYING
        total > 0L -> Step.DOWNLOADING
        else -> Step.PREPARING
    }

    private const val DONE_STATE = 1004
    private const val APPLY_PHASE = 2000
}

internal class BattleNetInstaller(
    private val app: Context,
    private val item: CatalogItem,
    private val folder: File,
    private val update: Boolean,
) : DownloadQueue.DownloadJob {
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val ended = java.util.concurrent.CountDownLatch(1)
    private var before = 0L
    private var kept = -1L

    override fun run(handle: DownloadQueue.JobHandle): String? {
        started.set(true)
        try {
            return if (cancelled.get()) null else work(handle)
        } finally {
            ended.countDown()
        }
    }

    private fun work(handle: DownloadQueue.JobHandle): String? {
        before = DownloadQueue.entry(handle.key)?.bytesTotal ?: 0L
        handle.stage(DownloadStage.MANIFEST, app.getString(R.string.bnet_dl_starting))
        if (!BattleNet.installed(app)) throw IllegalStateException(app.getString(R.string.bnet_dl_client_first))
        val title = BattleNet.title(item.id)
        val product = title?.product ?: throw IllegalStateException(app.getString(R.string.bnet_dl_failed, item.id))
        val uid = item.extra[BattleNetInstall.INSTALL_UID] ?: title.uid
        if (!update) folder.mkdirs()
        val guest = AddedGames.guestPath(app, folder) ?: throw IllegalStateException(app.getString(R.string.bnet_dl_failed, folder.path))
        val (country, region) = BattleNetInstall.country(Locale.getDefault())
        val args = if (update) listOf("agent-update", uid)
        else listOf("agent-install", uid, product, guest, BattleNetInstall.localeTag(app), country, region)
        var done: JSONObject? = null
        var reason: String? = null
        val code = BattleNetInstall.run(app, args, cancelled) { line ->
            when {
                line.startsWith(BattleNetInstall.PROGRESS) ->
                    runCatching { JSONObject(line.removePrefix(BattleNetInstall.PROGRESS)) }.getOrNull()?.let { progress(handle, it) }
                line == BattleNetInstall.STATE + "waiting" -> handle.stage(DownloadStage.MANIFEST, app.getString(R.string.bnet_dl_waiting))
                line == BattleNetInstall.STATE + "queued" -> handle.stage(DownloadStage.MANIFEST, app.getString(R.string.bnet_dl_queued))
                line == BattleNetInstall.STATE + "starting" -> handle.stage(DownloadStage.MANIFEST, app.getString(R.string.bnet_dl_starting))
                line.startsWith(BattleNetInstall.DONE) -> done = runCatching { JSONObject(line.removePrefix(BattleNetInstall.DONE)) }.getOrNull()
                line.startsWith(BattleNetInstall.ERROR) -> reason = line.removePrefix(BattleNetInstall.ERROR)
            }
        }
        if (cancelled.get()) return null
        if (done == null) throw IllegalStateException(BattleNetInstall.failure(app, reason, code))
        handle.log("Battle.net: ${item.title} ${if (update) "updated" else "installed"} ${done?.optString("version").orEmpty()}".trimEnd())
        StoresState.post {
            BattleNetInstall.updates.remove(item.id)
            StoresState.notifyLibraryChanged(app)
        }
        BattleNetInstall.check(item.id, folder, force = true)
        return folder.absolutePath
    }

    private fun progress(handle: DownloadQueue.JobHandle, status: JSONObject) {
        val current = status.optLong("download_current", 0L)
        val total = status.optLong("download_total", 0L)
        when (BattleNetInstall.step(status.optInt("state", 0), status.optInt("phase", 0), current, total)) {
            BattleNetInstall.Step.PREPARING -> handle.stage(DownloadStage.MANIFEST, app.getString(R.string.stores_stage_checking))
            BattleNetInstall.Step.DOWNLOADING -> {
                if (kept < 0L) kept = (before - total).coerceAtLeast(0L)
                handle.progress(kept + current, kept + total)
            }
            BattleNetInstall.Step.APPLYING -> handle.stage(DownloadStage.INSTALL)
        }
    }

    override fun cancel(deleteFiles: Boolean) {
        cancelled.set(true)
        if (!deleteFiles || update) return
        val uid = item.extra[BattleNetInstall.INSTALL_UID] ?: BattleNet.title(item.id)?.uid ?: return
        Thread({
            if (started.get() && !ended.await(END_WAIT_S, TimeUnit.SECONDS)) Log.w("BattleNetInstall", "the cancelled install of $uid is still stopping")
            val gone = BattleNetInstall.agentUninstall(app, uid)
            if (folder.isDirectory && (gone || folder.list().isNullOrEmpty())) com.droiddeck.launcher.core.FileUtils.delete(folder)
            Log.i("BattleNetInstall", "cancelled install of $uid: ${if (gone) "removed" else "left for Battle.net to remove"}")
        }, "battlenet-cancel").start()
    }
}

private const val END_WAIT_S = 120L
