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
    class Result(val ok: Boolean, val files: Int, val bytes: Long, val reason: String) {
        val line: String get() = "files=$files bytes=$bytes result=${if (ok) "ok" else reason}"
    }

    /** A game's cloud state for the Manage saves row. */
    class Status(val supported: Boolean, val folder: File?, val lastSync: Long, val reason: String)

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
        return Status(reason != "no-cloud-saves" && reason != "not-installed", dir, lastSync(context, store, id), reason)
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

    /** Cloud → device where the cloud copy is newer. Blocking. */
    fun download(context: Context, store: Store, id: String): Result = run(context, store, id, "down") { dir, cloud -> pull(context, store, id, dir, cloud) }

    /** Device → cloud where the local copy is newer. Blocking. */
    fun upload(context: Context, store: Store, id: String): Result = run(context, store, id, "up") { dir, cloud -> push(dir, cloud) }

    private fun run(context: Context, store: Store, id: String, way: String, body: (File, CloudTransport) -> Result): Result {
        val result = try {
            val (folder, sidecar) = game(context, store, id) ?: return done(store, id, way, Result(false, 0, 0, "not-installed"))
            if (!sidecar.cloud) return done(store, id, way, Result(true, 0, 0, "off"))
            val (dir, reason) = saveFolder(context, store, id)
            if (dir == null) Result(reason == "no-cloud-saves", 0, 0, reason)
            else {
                val cloud = transport(context, store, id, folder) ?: return done(store, id, way, Result(false, 0, 0, "signed-out"))
                body(dir, cloud).also { if (it.ok) markSynced(context, store, id) }
            }
        } catch (e: NoCloudSaves) {
            prefs(context, store).edit().putString("cloud_template_$id", "").apply()
            Result(true, 0, 0, "no-cloud-saves")
        } catch (e: Exception) {
            Result(false, 0, 0, e.javaClass.simpleName)
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

    private fun md5(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { input -> val buf = ByteArray(8192); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun pull(context: Context, store: Store, id: String, dir: File, cloud: CloudTransport): Result {
        val remote = cloud.list()
        if (remote.isEmpty()) return Result(true, 0, 0, "empty")
        val local = localFiles(dir)
        val pool = Executors.newFixedThreadPool(PARALLEL, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("cloud"))
        val wanted = java.util.concurrent.ConcurrentLinkedQueue<CloudFile>()
        for (f in remote) pool.execute {
            val mine = local[f.name]
            if (mine == null) { wanted.add(f); return@execute }
            cloud.details(f)
            if (f.md5 != null && f.md5.equals(md5(mine), ignoreCase = true)) return@execute
            if (f.modifiedMs > mine.lastModified()) wanted.add(f)
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
        if (wanted.isEmpty()) return Result(true, 0, 0, "up-to-date")
        backup(context, store, id, dir)
        var bytes = 0L
        for (f in wanted) {
            val (data, modified) = cloud.get(f)
            val dest = File(dir, f.name)
            if (!dest.canonicalPath.startsWith(dir.canonicalPath + File.separator)) continue
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
            if (modified > 0) dest.setLastModified(modified)
            bytes += data.size
        }
        return Result(true, wanted.size, bytes, "ok")
    }

    private fun push(dir: File, cloud: CloudTransport): Result {
        val local = localFiles(dir)
        if (local.isEmpty()) return Result(true, 0, 0, "nothing-local")
        val remote = cloud.list().associateBy { it.name }
        val pool = Executors.newFixedThreadPool(PARALLEL, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("cloud"))
        val changed = java.util.concurrent.ConcurrentHashMap<String, File>()
        for ((name, file) in local) pool.execute {
            val r = remote[name]
            if (r == null) { changed[name] = file; return@execute }
            cloud.details(r)
            if (r.md5 != null && r.md5.equals(md5(file), ignoreCase = true)) {
                // Same bytes, touched by the game: line the time up so the next sync skips it cheaply.
                if (r.modifiedMs > 0) file.setLastModified(r.modifiedMs)
                return@execute
            }
            if (r.modifiedMs < 0 || file.lastModified() > r.modifiedMs) changed[name] = file
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
        if (changed.isEmpty()) return Result(true, 0, 0, "up-to-date")
        val data = changed.mapValues { it.value.readBytes() }
        val stamped = cloud.put(data)
        for ((name, t) in stamped) if (t > 0) changed[name]?.setLastModified(t)
        return Result(true, stamped.size, data.values.sumOf { it.size.toLong() }, "ok")
    }

    /** The folder as it is, copied before a download changes it; the last [BACKUPS] are kept. */
    private fun backup(context: Context, store: Store, id: String, dir: File) {
        if (!dir.isDirectory || dir.listFiles().isNullOrEmpty()) return
        val root = File(context.filesDir, "stores/cloud-backups/${store.id}-${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        runCatching { dir.copyRecursively(File(root, System.currentTimeMillis().toString()), overwrite = true) }
        root.listFiles()?.sortedByDescending { it.name }?.drop(BACKUPS)?.forEach { it.deleteRecursively() }
    }
}
