package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.epic.EpicApiClient
import com.droiddeck.launcher.stores.epic.EpicCredentialStore
import com.droiddeck.launcher.stores.epic.EpicPrefs
import com.droiddeck.launcher.stores.gog.GogAuth
import com.droiddeck.launcher.stores.gog.GogPrefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * GOG and Epic cloud saves for an installed store game: before a launch the cloud copy is brought
 * down where it is newer, after the game exits what changed goes up - newest wins, file by file.
 * Before a download overwrites anything, the folder is copied to the app's storage (the last
 * [BACKUPS] per game). Both launch paths reach it: the compat tool's request from
 * droiddeck-store-launch ([StoreLaunchRequests]) for every launch, and the Games tab's Manage saves.
 *
 * The save folder: GOG's remote-config location template (by the game's Galaxy client id) or
 * Epic's CloudSaveFolder catalog attribute, cached per game, made concrete by [CloudSavePaths]
 * inside the game's Proton prefix. A game whose store gives none has no cloud saves.
 */
object CloudSaves {
    private const val TAG = "CloudSaves"
    private const val BACKUPS = 3
    private const val PARALLEL = 6

    /** What a sync did, for the log and the Manage saves row. */
    /**
     * What a sync did, for the log and the Manage saves row. [result]: ok, skipped (nothing to do or
     * not possible: [reason] says why), deferred (no prefix yet; it runs once Proton makes one),
     * failed. [conflicts]: files changed on both sides, left alone.
     */
    class Result(val result: String, val files: Int, val bytes: Long, val reason: String, val conflicts: Int = 0) {
        val ok: Boolean get() = result != "failed"
        val line: String get() = "result=$result files=$files bytes=$bytes reason=$reason" + if (conflicts > 0) " conflicts=$conflicts" else ""
    }

    /** A game's cloud state for the Manage saves row. */
    class Status(val supported: Boolean, val folder: File?, val lastSync: Long, val reason: String, val conflicts: List<String> = emptyList())

    private fun game(context: Context, store: Store, id: String): Pair<File, StoreGameSidecar>? =
        StoreInstallRoot.gameFolders(context).firstNotNullOfOrNull { f ->
            StoreGameSidecar.read(f)?.takeIf { it.store == store && it.id == id && it.isInstalled }?.let { f to it }
        }

    /** The game's prefix: compatdata/<its shortcut appid>/pfx, wherever that library is. */
    fun prefix(context: Context, folder: File): File? {
        val appId = AddedGames.scan(context).firstOrNull { it.folder.absolutePath == folder.absolutePath }?.appId ?: return null
        return Library.protonPrefix(context, appId and 0xFFFFFFFFL)
    }

    private fun prefs(context: Context, store: Store) = if (store == Store.GOG) GogPrefs.get(context) else EpicPrefs.get(context)

    fun lastSync(context: Context, store: Store, id: String): Long = prefs(context, store).getLong("cloud_sync_$id", 0L)

    private fun markSynced(context: Context, store: Store, id: String) = prefs(context, store).edit().putLong("cloud_sync_$id", System.currentTimeMillis()).apply()

    // ---- the save folder ------------------------------------------------------------------------

    /** The store's template for this game: cached, else fetched; "" when the store gives none, null when it could not be asked. */
    fun template(context: Context, store: Store, id: String, folder: File, sidecar: StoreGameSidecar): String? {
        val p = prefs(context, store)
        if (p.contains("cloud_template_$id")) return p.getString("cloud_template_$id", "")
        val fetched = when (store) {
            Store.GOG -> gogTemplate(context, id, folder)
            Store.EPIC -> {
                val token = EpicCredentialStore.getValidAccessToken(context) ?: return null
                val ns = sidecar.extra["namespace"].orEmpty()
                val item = sidecar.extra["catalogItemId"].orEmpty()
                if (ns.isEmpty() || item.isEmpty()) "" else EpicApiClient.getCustomAttribute(token, ns, item, "CloudSaveFolder")
            }
            Store.AMAZON -> ""
        } ?: return null
        p.edit().putString("cloud_template_$id", fetched).apply()
        Log.i(TAG, "cloud ${store.id} $id template ${if (fetched.isEmpty()) "none" else "found"}")
        return fetched
    }

    private fun gogClientId(context: Context, id: String, folder: File): String? =
        GogPrefs.get(context).getString("client_id_$id", null)?.ifEmpty { null }
            ?: runCatching { JSONObject(File(folder, "goggame-$id.info").readText()).optString("clientId").ifEmpty { null } }.getOrNull()

    /** GOG's public remote-config: `content.Windows.cloudStorage.locations`, the one named "saves" first. */
    private fun gogTemplate(context: Context, id: String, folder: File): String? {
        val clientId = gogClientId(context, id, folder) ?: return ""
        return try {
            val c = URL("https://remote-config.gog.com/components/galaxy_client/clients/$clientId?component_version=2.0.45").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "GOG Galaxy")
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val cloud = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("content")?.optJSONObject("Windows")?.optJSONObject("cloudStorage")
            c.disconnect()
            if (cloud?.optBoolean("enabled", false) != true) return ""
            val locations = cloud.optJSONArray("locations") ?: return ""
            val all = (0 until locations.length()).mapNotNull { locations.optJSONObject(it) }
            (all.firstOrNull { it.optString("name").equals("saves", true) } ?: all.firstOrNull())?.optString("location").orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "gog remote-config: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The concrete save folder in the prefix, or null with why. */
    private fun saveFolder(context: Context, store: Store, id: String): Pair<File?, String> {
        val (folder, sidecar) = game(context, store, id) ?: return null to "not-installed"
        val template = template(context, store, id, folder, sidecar) ?: return null to "store-unreachable"
        if (template.isEmpty()) return null to "no-cloud-saves"
        val pfx = prefix(context, folder) ?: return null to "no-prefix"
        val dir = when (store) {
            Store.GOG -> CloudSavePaths.gog(template, pfx, folder)
            Store.EPIC -> CloudSavePaths.epic(template, pfx, folder, EpicCredentialStore.load(context)?.accountId.orEmpty(), id)
            Store.AMAZON -> null
        }
        return dir to (if (dir == null) "unresolved" else "ok")
    }

    fun status(context: Context, store: Store, id: String): Status {
        val (dir, reason) = saveFolder(context, store, id)
        return Status(reason != "no-cloud-saves" && reason != "not-installed", dir, lastSync(context, store, id), reason, conflicts(context, store, id))
    }

    // ---- transport ------------------------------------------------------------------------------

    private fun transport(context: Context, store: Store, id: String, folder: File): CloudTransport? = when (store) {
        Store.GOG -> {
            val account = StoreAccounts.read(context, Store.GOG)
            val userId = account?.optString("user_id").orEmpty()
            val galaxy = GogAuth.validToken(context)
            val clientId = gogClientId(context, id, folder)
            if (userId.isEmpty() || galaxy == null || clientId == null) null
            else GogCloud(userId, clientId, gogScopedToken(context, id, clientId, account?.optString("refresh_token").orEmpty()) ?: galaxy)
        }
        Store.EPIC -> {
            val token = EpicCredentialStore.getValidAccessToken(context)
            val account = EpicCredentialStore.load(context)?.accountId.orEmpty()
            if (token == null || account.isEmpty()) null else EpicCloud(account, id, token)
        }
        Store.AMAZON -> null
    }

    /** GOG cloud storage wants a token issued to the game's own client: the Galaxy refresh token, exchanged with the game's id and secret. */
    private fun gogScopedToken(context: Context, id: String, clientId: String, refresh: String): String? {
        val secret = GogPrefs.get(context).getString("client_secret_$id", null)?.ifEmpty { null } ?: return null
        if (refresh.isEmpty()) return null
        return try {
            val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
            val c = URL("https://auth.gog.com/token?client_id=${enc(clientId)}&client_secret=${enc(secret)}&grant_type=refresh_token&refresh_token=${enc(refresh)}").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "GOG Galaxy")
            if (c.responseCode != 200) { c.disconnect(); return null }
            JSONObject(c.inputStream.bufferedReader().readText()).optString("access_token").ifEmpty { null }.also { c.disconnect() }
        } catch (e: Exception) { null }
    }

    // ---- sync -----------------------------------------------------------------------------------

    private fun baseline(context: Context, store: Store, id: String): Map<String, CloudPlan.Entry>? =
        CloudPlan.fromJson(prefs(context, store).getString("cloud_baseline_$id", null))

    private fun saveBaseline(context: Context, store: Store, id: String, b: Map<String, CloudPlan.Entry>) =
        prefs(context, store).edit().putString("cloud_baseline_$id", CloudPlan.toJson(b)).apply()

    /** Files changed on both sides since the last sync, waiting for Keep cloud / Keep local. */
    fun conflicts(context: Context, store: Store, id: String): List<String> =
        prefs(context, store).getString("cloud_conflicts_$id", "").orEmpty().split('\n').filter { it.isNotEmpty() }

    private fun saveConflicts(context: Context, store: Store, id: String, names: Collection<String>) =
        prefs(context, store).edit().putString("cloud_conflicts_$id", names.joinToString("\n")).apply()

    /** Cloud → device where the cloud copy changed (the first time: wherever it differs). [force]: conflicts too (Keep cloud). Blocking. */
    fun download(context: Context, store: Store, id: String, force: Boolean = false): Result =
        sync(context, store, id, "down") { dir, cloud -> downWith(context, store, id, dir, cloud, force) }

    /** Device → cloud where the local copy changed, once a download has set the baseline. [force]: conflicts too (Keep local). Blocking. */
    fun upload(context: Context, store: Store, id: String, force: Boolean = false): Result =
        sync(context, store, id, "up") { dir, cloud -> upWith(context, store, id, dir, cloud, force) }

    fun keepCloud(context: Context, store: Store, id: String): Result = download(context, store, id, force = true)
    fun keepLocal(context: Context, store: Store, id: String): Result = upload(context, store, id, force = true)

    /**
     * The pre-launch download, or - when Proton has not made the game's prefix yet (a first launch)
     * - a deferred one: answered at once, then run in the background as soon as the prefix's user
     * folder appears (within [PREFIX_WAIT_MS]), so the launch is not held up for it.
     */
    fun downloadOrDefer(context: Context, store: Store, id: String): Result {
        val app = context.applicationContext
        val g = game(app, store, id) ?: return done(store, id, "down", Result("skipped", 0, 0, "not-installed"))
        if (!g.second.cloud) return done(store, id, "down", Result("skipped", 0, 0, "off"))
        if (prefix(app, g.first)?.let { CloudSavePaths.profile(it).isDirectory } == true) return download(app, store, id)
        Thread({
            val end = System.currentTimeMillis() + PREFIX_WAIT_MS
            while (System.currentTimeMillis() < end) {
                if (prefix(app, g.first)?.let { CloudSavePaths.profile(it).isDirectory } == true) { download(app, store, id); return@Thread }
                Thread.sleep(2000)
            }
            done(store, id, "down", Result("skipped", 0, 0, "no-prefix"))
        }, "cloud-deferred").start()
        return done(store, id, "down", Result("deferred", 0, 0, "no-prefix"))
    }

    private const val PREFIX_WAIT_MS = 60_000L

    private fun sync(context: Context, store: Store, id: String, way: String, body: (File, CloudTransport) -> Result): Result {
        val result = try {
            val g = game(context, store, id)
            when {
                g == null -> Result("skipped", 0, 0, "not-installed")
                !g.second.cloud -> Result("skipped", 0, 0, "off")
                else -> {
                    val (dir, reason) = saveFolder(context, store, id)
                    val cloud = if (dir != null) transport(context, store, id, g.first) else null
                    when {
                        dir == null -> Result("skipped", 0, 0, reason)
                        cloud == null -> Result("skipped", 0, 0, "not-signed-in")
                        else -> body(dir, cloud).also { if (it.result == "ok") markSynced(context, store, id) }
                    }
                }
            }
        } catch (e: NoCloudSaves) {
            prefs(context, store).edit().putString("cloud_template_$id", "").apply()
            Result("skipped", 0, 0, "no-cloud-saves")
        } catch (e: Exception) {
            Result("failed", 0, 0, e.javaClass.simpleName)
        }
        return done(store, id, way, result)
    }

    private fun done(store: Store, id: String, way: String, r: Result): Result {
        Log.i(TAG, "cloud ${store.id} $id $way ${r.line}")
        StoresState.logLine("cloud ${store.id} $id $way ${r.line}")
        return r
    }

    private fun localFiles(dir: File): Map<String, File> =
        if (!dir.isDirectory) emptyMap()
        else dir.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(dir).path.replace(File.separatorChar, '/') }

    internal fun md5(f: File): String = md5(f.readBytes())

    internal fun md5(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    /** The cloud listing, with MD5 and time filled in (one HEAD each where the listing lacks them, in parallel). */
    private fun remote(cloud: CloudTransport, wanted: (String) -> Boolean): Map<String, CloudFile> {
        val files = cloud.list()
        val pool = Executors.newFixedThreadPool(PARALLEL, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("cloud"))
        for (f in files) if (wanted(f.name)) pool.execute { runCatching { cloud.details(f) } }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
        return files.associateBy { it.name }
    }

    internal fun downWith(context: Context, store: Store, id: String, dir: File, cloud: CloudTransport, force: Boolean): Result {
        val files = localFiles(dir)
        val remote = remote(cloud) { it in files }
        val local = files.mapValues { CloudPlan.Local(md5(it.value)) }
        val base = baseline(context, store, id)
        val plan = CloudPlan.down(local, remote.mapValues { CloudPlan.Remote(it.value.md5, it.value.modifiedMs) }, base)
        val take = if (force) plan.transfer + plan.conflicts else plan.transfer
        if (take.isNotEmpty() && files.isNotEmpty()) backup(context, store, id, dir, "local")
        val next = HashMap(base.orEmpty())
        var bytes = 0L
        for (name in take) {
            val f = remote[name] ?: continue
            val (data, modified) = cloud.get(f)
            val dest = File(dir, name)
            if (!dest.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
            if (modified > 0) dest.setLastModified(modified)
            bytes += data.size
            next[name] = CloudPlan.Entry(md5(data), f.md5, if (modified > 0) modified else f.modifiedMs)
        }
        for (name in plan.same) remote[name]?.let { r -> next[name] = CloudPlan.Entry(local.getValue(name).md5, r.md5, r.modifiedMs) }
        // A download that completed sets the baseline even when the cloud was empty: uploads may follow.
        saveBaseline(context, store, id, next)
        val left = if (force) emptyList() else plan.conflicts
        saveConflicts(context, store, id, left)
        return Result("ok", take.size, bytes, if (take.isEmpty()) "up-to-date" else "downloaded", left.size)
    }

    internal fun upWith(context: Context, store: Store, id: String, dir: File, cloud: CloudTransport, force: Boolean): Result {
        val base = baseline(context, store, id) ?: return Result("skipped", 0, 0, "no-baseline")
        val files = localFiles(dir)
        if (files.isEmpty()) return Result("skipped", 0, 0, "nothing-local")
        val remote = remote(cloud) { it in files }
        val local = files.mapValues { CloudPlan.Local(md5(it.value)) }
        val plan = CloudPlan.up(local, remote.mapValues { CloudPlan.Remote(it.value.md5, it.value.modifiedMs) }, base)
        val send = if (force) plan.transfer + plan.conflicts else plan.transfer
        val next = HashMap(base)
        for (name in plan.same) remote[name]?.let { r -> next[name] = CloudPlan.Entry(local.getValue(name).md5, r.md5, r.modifiedMs) }
        if (send.isNotEmpty()) {
            // The cloud copies about to be replaced are kept first, as a download keeps the local ones.
            backupCloud(context, store, id, cloud, send.mapNotNull { remote[it] })
            val data = send.associateWith { files.getValue(it).readBytes() }
            val stamped = cloud.put(data)
            for ((name, t) in stamped) if (t > 0) files[name]?.setLastModified(t)
            val after = remote(cloud) { it in data }
            for (name in data.keys) {
                val r = after[name]
                next[name] = CloudPlan.Entry(md5(data.getValue(name)), r?.md5, r?.modifiedMs ?: stamped[name] ?: -1L)
            }
        }
        saveBaseline(context, store, id, next)
        val left = if (force) emptyList() else plan.conflicts
        saveConflicts(context, store, id, left)
        return Result("ok", send.size, send.sumOf { files.getValue(it).length() }, if (send.isEmpty()) "up-to-date" else "uploaded", left.size)
    }

    // ---- uploads that do not depend on one hook ---------------------------------------------------

    /**
     * A game launched with cloud saves is marked dirty in the app's storage when its pre-launch step
     * runs. The upload happens on the first of: the compat tool's exit request (`exit`), the end of
     * the session (`session-end`), or the app's next start for a mark a killed app left (`recovery`)
     * - so a session closed from the drawer, a game Steam killed, or a crash still uploads. The mark
     * goes only after an upload that went through, or one skipped on purpose with its reason logged.
     */
    private fun marks(context: Context) = context.applicationContext.getSharedPreferences("stores.cloud", Context.MODE_PRIVATE)

    fun markDirty(context: Context, store: Store, id: String) {
        marks(context).edit().putLong("dirty:${store.id}:$id", System.currentTimeMillis()).apply()
    }

    fun dirty(context: Context): List<Pair<Store, String>> = marks(context).all.keys.mapNotNull { k ->
        val parts = k.split(':', limit = 3)
        if (parts.size == 3 && parts[0] == "dirty") Store.byId(parts[1])?.let { it to parts[2] } else null
    }

    private val markLock = Any()

    /** Why a skip is final: retrying cannot change it, so the mark goes. */
    private val FINAL_SKIPS = setOf("off", "no-cloud-saves", "no-baseline", "not-installed", "nothing-local")

    /** Uploads [id] if it is marked dirty; null when it is not (another trigger got there first). */
    fun uploadDirty(context: Context, store: Store, id: String, trigger: String): Result? = synchronized(markLock) {
        val key = "dirty:${store.id}:$id"
        if (!marks(context).contains(key)) return null
        val r = sync(context, store, id, "up trigger=$trigger") { dir, cloud -> upWith(context, store, id, dir, cloud, force = false) }
        if (r.result == "ok" || (r.result == "skipped" && r.reason in FINAL_SKIPS)) marks(context).edit().remove(key).apply()
        r
    }

    /**
     * Every dirty game, uploaded; [running] says a game session is up, in which case `recovery` waits
     * - a marked game may be the one running.
     */
    fun uploadAllDirty(context: Context, trigger: String, running: Boolean = com.droiddeck.launcher.session.SessionState.running) {
        if (trigger == "recovery" && running) return
        for ((store, id) in dirty(context)) uploadDirty(context, store, id, trigger)
    }

    private fun backupRoot(context: Context, store: Store, id: String) =
        File(context.filesDir, "stores/cloud-backups/${store.id}-${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}")

    /** The local folder as it is, before a download changes it; the last [BACKUPS] of each kind are kept. */
    private fun backup(context: Context, store: Store, id: String, dir: File, kind: String) {
        val root = backupRoot(context, store, id)
        runCatching { dir.copyRecursively(File(root, "$kind-${System.currentTimeMillis()}"), overwrite = true) }
        prune(root, kind)
    }

    private fun backupCloud(context: Context, store: Store, id: String, cloud: CloudTransport, files: List<CloudFile>) {
        if (files.isEmpty()) return
        val root = backupRoot(context, store, id)
        val target = File(root, "cloud-${System.currentTimeMillis()}")
        for (f in files) {
            val dest = File(target, f.name)
            if (!dest.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
            dest.parentFile?.mkdirs()
            dest.writeBytes(cloud.get(f).first)
        }
        prune(root, "cloud")
    }

    private fun prune(root: File, kind: String) {
        root.listFiles { f -> f.name.startsWith("$kind-") }?.sortedByDescending { it.name }?.drop(BACKUPS)?.forEach { it.deleteRecursively() }
    }
}
