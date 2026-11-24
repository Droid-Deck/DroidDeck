package com.droiddeck.launcher.stores

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.ea.EaAuth
import com.droiddeck.launcher.stores.ea.EaCloud
import com.droiddeck.launcher.stores.ea.EaLibrary
import com.droiddeck.launcher.stores.ea.EaPrefs
import com.droiddeck.launcher.stores.ea.EaSaveRules
import com.droiddeck.launcher.stores.epic.EpicApiClient
import com.droiddeck.launcher.stores.epic.EpicCredentialStore
import com.droiddeck.launcher.stores.epic.EpicPrefs
import com.droiddeck.launcher.stores.gog.GogAuth
import com.droiddeck.launcher.stores.gog.GogPrefs
import com.droiddeck.launcher.stores.ubisoft.UbisoftAuth
import com.droiddeck.launcher.stores.ubisoft.UbisoftCloud
import com.droiddeck.launcher.stores.ubisoft.UbisoftCloudTransport
import com.droiddeck.launcher.stores.ubisoft.UbisoftConnect
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object CloudSaves {
    private const val TAG = "CloudSaves"
    private const val BACKUPS = 3
    private const val PARALLEL = 6
    private const val CLOUD_LAYOUT = 2

    class Result(val result: String, val files: Int, val bytes: Long, val reason: String, val conflicts: Int = 0, val detail: String = "") {
        val ok: Boolean get() = result != "failed"
        val line: String get() = "result=$result files=$files bytes=$bytes reason=$reason" +
            (if (conflicts > 0) " conflicts=$conflicts" else "") +
            if (detail.isNotEmpty()) " detail=\"$detail\"" else ""
    }

    class Status(val supported: Boolean, val folder: File?, val lastSync: Long, val reason: String, val conflicts: List<String> = emptyList(), val signedIn: Boolean = true)

    private fun game(context: Context, store: Store, id: String): Pair<File, StoreGameSidecar>? {
        fun match(f: File) = StoreGameSidecar.read(f)?.takeIf { it.store == store && it.id == id && it.isInstalled }?.let { f to it }
        return StoreInstallRoot.gameFolders(context).firstNotNullOfOrNull(::match)
            ?: if (store != Store.UBISOFT) null
            else com.droiddeck.launcher.session.SessionPrefs.storeGameFolder(context, "${store.id}:$id")?.let { match(File(it)) }
    }

    fun supports(store: Store?): Boolean = store == Store.GOG || store == Store.EPIC || store == Store.EA || store == Store.UBISOFT

    fun prefix(context: Context, folder: File): File? {
        val appId = AddedGames.scan(context).firstOrNull { it.folder.absolutePath == folder.absolutePath }?.appId ?: return null
        return Library.protonPrefix(context, appId and 0xFFFFFFFFL)
    }

    private fun prefs(context: Context, store: Store) = when (store) {
        Store.GOG -> GogPrefs.get(context)
        Store.EA -> EaPrefs.get(context)
        else -> EpicPrefs.get(context)
    }

    fun lastSync(context: Context, store: Store, id: String): Long = prefs(context, store).getLong("cloud_sync_$id", 0L)

    private fun markSynced(context: Context, store: Store, id: String) = prefs(context, store).edit().putLong("cloud_sync_$id", System.currentTimeMillis()).apply()


    private const val TEMPLATE_NONE = "none"
    private const val UBISOFT_TEMPLATE = "ubisoft"
    internal const val TEMPLATE_NONE_TTL_MS = 24 * 60 * 60 * 1000L

    internal fun noneTemplateIsCurrent(storedAtMs: Long, nowMs: Long) =
        storedAtMs > 0L && nowMs - storedAtMs < TEMPLATE_NONE_TTL_MS

    private fun rememberNoCloud(p: SharedPreferences, id: String) {
        p.edit().putString("cloud_template_$id", TEMPLATE_NONE)
            .putLong("cloud_template_at_$id", System.currentTimeMillis()).apply()
    }

    fun template(context: Context, store: Store, id: String, folder: File, sidecar: StoreGameSidecar): String? {
        val p = prefs(context, store)
        if (store == Store.EA) eaRules(context, id)?.let { fresh ->
            if (fresh.isEmpty()) rememberNoCloud(p, id) else p.edit().putString("cloud_template_$id", fresh).remove("cloud_template_at_$id").apply()
            return fresh
        }
        var retryNone = false
        if (p.contains("cloud_template_$id")) {
            val cached = p.getString("cloud_template_$id", "") ?: ""
            if (cached == TEMPLATE_NONE) {
                if (noneTemplateIsCurrent(p.getLong("cloud_template_at_$id", 0L), System.currentTimeMillis())) return ""
                retryNone = true
            } else if (cached.isNotEmpty()) return cached
        }
        var gogLocation = ""
        val fetched = when (store) {
            Store.GOG -> {
                val config = gogConfig(context, id, folder)
                if (config == null) {
                    if (retryNone) { rememberNoCloud(p, id); return "" }
                    return null
                }
                gogLocation = config.name
                config.location
            }
            Store.EPIC -> {
                val token = EpicCredentialStore.getValidAccessToken(context) ?: return null
                val ns = sidecar.extra["namespace"].orEmpty()
                val item = sidecar.extra["catalogItemId"].orEmpty()
                if (ns.isEmpty() || item.isEmpty()) "" else EpicApiClient.getCustomAttribute(token, ns, item, "CloudSaveFolder")
            }
            Store.UBISOFT -> UBISOFT_TEMPLATE
            Store.EA -> eaRules(context, id) ?: return null
            Store.AMAZON, Store.ITCH, Store.GAMEJOLT, Store.XBOX, Store.BATTLENET, Store.ROCKSTAR -> ""
        } ?: return null
        p.edit()
            .putString("cloud_template_$id", fetched.ifEmpty { TEMPLATE_NONE })
            .apply {
                if (fetched.isEmpty()) putLong("cloud_template_at_$id", System.currentTimeMillis())
                else remove("cloud_template_at_$id")
                if (store == Store.GOG && gogLocation.isNotEmpty()) putString("cloud_location_$id", gogLocation)
            }
            .apply()
        Log.i(TAG, "cloud ${store.id} $id template ${if (fetched.isEmpty()) "none" else "found"}")
        return fetched
    }

    private fun gogClientId(context: Context, id: String, folder: File): String? =
        GogPrefs.get(context).getString("client_id_$id", null)?.ifEmpty { null }
            ?: runCatching { JSONObject(File(folder, "goggame-$id.info").readText()).optString("clientId").ifEmpty { null } }.getOrNull()

    private class GogConfig(val name: String, val location: String)

    private fun gogConfig(context: Context, id: String, folder: File): GogConfig? {
        val clientId = gogClientId(context, id, folder) ?: return GogConfig("", "")
        return try {
            val c = URL("https://remote-config.gog.com/components/galaxy_client/clients/$clientId?component_version=2.0.45").openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "GOG Galaxy")
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val cloud = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("content")?.optJSONObject("Windows")?.optJSONObject("cloudStorage")
            c.disconnect()
            val locations = cloud?.optJSONArray("locations")
            val picked = CloudSavePaths.gogSaveFromRemote(
                enabled = cloud?.optBoolean("enabled", false) == true,
                locations = (0 until (locations?.length() ?: 0)).mapNotNull { i ->
                    locations!!.optJSONObject(i)?.let { it.optString("name") to it.optString("location") }
                },
                clientId = clientId,
            )
            if (picked == null) GogConfig("", "") else GogConfig(picked.name, picked.template)
        } catch (e: Exception) {
            Log.w(TAG, "gog remote-config: ${cloudFailureDetail(e)}")
            null
        }
    }

    private fun gogLocationName(context: Context, id: String, folder: File): String? {
        val p = GogPrefs.get(context)
        p.getString("cloud_location_$id", null)?.ifEmpty { null }?.let { return it }
        if (p.getString("cloud_template_$id", null)?.let { it.isEmpty() || it == TEMPLATE_NONE } == true) return null
        val config = gogConfig(context, id, folder) ?: return null
        if (config.name.isNotEmpty()) p.edit().putString("cloud_location_$id", config.name).apply()
        return config.name.ifEmpty { null }
    }

    private fun saveFolder(context: Context, store: Store, id: String): Pair<File?, String> {
        val (folder, sidecar) = game(context, store, id) ?: return null to "not-installed"
        val template = template(context, store, id, folder, sidecar) ?: return null to "store-unreachable"
        if (template.isEmpty()) return null to "no-cloud-saves"
        if (store == Store.UBISOFT) {
            val user = UbisoftAuth.load(context)?.userId?.takeIf { it.isNotEmpty() } ?: return null to "not-signed-in"
            val product = sidecar.extra["productId"]?.toLongOrNull() ?: return null to "unresolved"
            return File(UbisoftConnect.prefix(context), "pfx/drive_c/users/steamuser/Saved Games/Ubisoft/$user/$product") to "ok"
        }
        val pfx = prefix(context, folder) ?: return null to "no-prefix"
        val dir = when (store) {
            Store.GOG -> CloudSavePaths.gog(template, pfx, folder)
            Store.EPIC -> CloudSavePaths.epic(template, pfx, folder, EpicCredentialStore.load(context)?.accountId.orEmpty(), id)
            Store.EA -> if (sidecar.extra["masterTitleId"].isNullOrEmpty()) null
                else EaSaveRules.parse(template)?.base()?.let(EaSaveRules::template)?.let { CloudSavePaths.gog(it, pfx, folder) }
            Store.AMAZON, Store.ITCH, Store.GAMEJOLT, Store.XBOX, Store.BATTLENET, Store.UBISOFT, Store.ROCKSTAR -> null
        }
        return dir to (if (dir == null) "unresolved" else "ok")
    }

    fun status(context: Context, store: Store, id: String): Status {
        val (dir, reason) = saveFolder(context, store, id)
        return Status(reason != "no-cloud-saves" && reason != "not-installed", dir, lastSync(context, store, id), reason, conflicts(context, store, id, dir), signedIn(context, store))
    }


    private fun transport(context: Context, store: Store, id: String, folder: File): CloudTransport? = when (store) {
        Store.GOG -> {
            val account = StoreAccounts.read(context, Store.GOG)
            val userId = account?.optString("user_id").orEmpty()
            val galaxy = GogAuth.validToken(context)
            val clientId = gogClientId(context, id, folder)
            if (userId.isEmpty() || galaxy == null || clientId == null) null
            else GogCloud(userId, clientId, gogScopedToken(context, id, clientId) ?: galaxy, gogLocationName(context, id, folder), tempDir = context.cacheDir)
        }
        Store.EPIC -> {
            val token = EpicCredentialStore.getValidAccessToken(context)
            val account = EpicCredentialStore.load(context)?.accountId.orEmpty()
            if (token == null || account.isEmpty()) null else EpicCloud(account, id, token)
        }
        Store.UBISOFT -> {
            val session = UbisoftAuth.current(context)
            val product = StoreGameSidecar.read(folder)?.extra?.get("productId")?.toLongOrNull()
            if (session == null || product == null) null else UbisoftCloudTransport(UbisoftCloud(session, product), session.userId)
        }
        Store.EA -> {
            val sidecar = StoreGameSidecar.read(folder)
            val rules = sidecar?.let { template(context, store, id, folder, it) }?.let(EaSaveRules::parse)
            val base = rules?.base()
            val token = EaAuth.accessToken(context)
            val uid = EaAuth.load(context)?.userId.orEmpty()
            val master = sidecar?.extra?.get("masterTitleId").orEmpty()
            if (rules == null || base == null || token == null || uid.isEmpty() || master.isEmpty()) null
            else EaCloud(token, uid, EaCloud.keys(master, sidecar?.extra?.get("multiplayerId").orEmpty()), base, rules, saveFolder(context, store, id).first)
        }
        Store.AMAZON, Store.ITCH, Store.GAMEJOLT, Store.XBOX, Store.BATTLENET, Store.ROCKSTAR -> null
    }

    private fun signedIn(context: Context, store: Store): Boolean = when (store) {
        Store.EA -> EaAuth.signedIn(context)
        else -> true
    }

    private fun eaRules(context: Context, id: String): String? {
        val games = EaLibrary.cached(context)
        if (games.isEmpty()) return null
        return games.firstOrNull { it.offerId == id }?.cloudSaves.orEmpty()
    }

    private fun gogScopedToken(context: Context, id: String, clientId: String): String? {
        val secret = GogPrefs.get(context).getString("client_secret_$id", null)?.ifEmpty { null } ?: return null
        return GogAuth.gameToken(context, clientId, secret)?.accessToken
    }


    /** Ubisoft saves belong to an account and product, so their sync state is kept per save folder. */
    internal fun scope(store: Store, id: String, dir: File): String =
        if (store == Store.UBISOFT) "$id:${dir.parentFile?.name.orEmpty()}:${dir.name}" else id

    internal fun baseline(context: Context, store: Store, key: String): Map<String, CloudPlan.Entry>? =
        CloudPlan.fromJson(prefs(context, store).getString("cloud_baseline_$key", null))

    private fun saveBaseline(context: Context, store: Store, key: String, b: Map<String, CloudPlan.Entry>) =
        prefs(context, store).edit().putString("cloud_baseline_$key", CloudPlan.toJson(b)).apply()

    internal class Migration(val baseline: Map<String, CloudPlan.Entry>, val moved: Int, val cleaned: Int, val failed: Int)

    internal fun migrateNested(dir: File, name: String, base: Map<String, CloudPlan.Entry>): Migration? {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) return null
        val nested = File(dir, name)
        if (!nested.isDirectory) return null
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        if (runCatching { nested.canonicalFile }.getOrNull()?.parentFile != root) return null
        val prefix = "$name/"
        val next = HashMap(base)
        var moved = 0
        var cleaned = 0
        var failed = 0
        for (src in nested.walkTopDown().filter { it.isFile }.toList()) {
            if (runCatching { java.nio.file.Files.isSymbolicLink(src.toPath()) }.getOrDefault(true)) continue
            val srcCanon = runCatching { src.canonicalFile }.getOrNull() ?: continue
            if (!srcCanon.path.startsWith(root.path + File.separator)) continue
            val rel = src.relativeTo(nested).path.replace(File.separatorChar, '/')
            val key = prefix + rel
            val entry = base[key] ?: continue
            val dest = File(dir, rel)
            val destCanon = runCatching { dest.canonicalFile }.getOrNull() ?: continue
            if (!destCanon.path.startsWith(root.path + File.separator)) continue
            if (dest.isFile && runCatching { md5(dest) == md5(src) }.getOrDefault(false)) {
                if (!src.delete()) { failed++; continue }
                cleaned++
                next[rel] = entry
                next.remove(key)
                continue
            }
            if (dest.exists()) continue
            val copied = runCatching {
                dest.parentFile?.mkdirs()
                val tmp = File.createTempFile(dest.name + ".", ".migrate", dest.parentFile)
                try {
                    src.copyTo(tmp, overwrite = true)
                    if (md5(tmp) != md5(src)) false
                    else {
                        java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                        true
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            }.getOrDefault(false)
            if (!copied) { failed++; continue }
            if (!src.delete()) { failed++; continue }
            moved++
            next[rel] = entry
            next.remove(key)
        }
        if (nested.walkTopDown().none { it.isFile }) nested.deleteRecursively()
        return Migration(next, moved, cleaned, failed)
    }

    private fun migrateGogNested(context: Context, id: String, folder: File, dir: File) {
        val p = GogPrefs.get(context)
        if (p.getInt("cloud_layout_$id", 0) >= CLOUD_LAYOUT) return
        val name = gogLocationName(context, id, folder) ?: return
        val base = baseline(context, Store.GOG, id).orEmpty()
        val result = migrateNested(dir, name, base)
        if (result != null) {
            if (result.baseline != base) saveBaseline(context, Store.GOG, id, result.baseline)
            if (result.moved + result.cleaned > 0) {
                StoresState.logLine("cloud gog $id migrate result=ok moved=${result.moved} cleaned=${result.cleaned} from=$name")
            }
        }
        if (result == null || result.failed == 0) p.edit().putInt("cloud_layout_$id", CLOUD_LAYOUT).apply()
    }

    fun conflicts(context: Context, store: Store, id: String, dir: File? = null): List<String> =
        if (store == Store.UBISOFT && dir == null) emptyList() else savedConflicts(context, store, dir?.let { scope(store, id, it) } ?: id)

    private fun savedConflicts(context: Context, store: Store, key: String): List<String> =
        prefs(context, store).getString("cloud_conflicts_$key", "").orEmpty().split('\n').filter { it.isNotEmpty() }

    private fun saveConflicts(context: Context, store: Store, key: String, names: Collection<String>) =
        prefs(context, store).edit().putString("cloud_conflicts_$key", names.joinToString("\n")).apply()

    private fun alike(cloud: CloudTransport, conflicts: List<String>, local: Map<String, CloudPlan.Local>, remote: Map<String, CloudFile>): List<String> =
        if (cloud.contentDigests) emptyList()
        else conflicts.filter { name ->
            val r = remote[name] ?: return@filter false
            val l = local[name] ?: return@filter false
            runCatching { md5(cloud.get(r).first) }.getOrNull()?.equals(l.md5, ignoreCase = true) == true
        }

    private fun settled(plan: CloudPlan.Plan, alike: List<String>): CloudPlan.Plan =
        if (alike.isEmpty()) plan else CloudPlan.Plan(plan.transfer, plan.conflicts - alike.toSet(), plan.same + alike, plan.refused)

    fun download(context: Context, store: Store, id: String, force: Boolean = false, claim: () -> Boolean = { true }): Result =
        sync(context, store, id, "down") { dir, cloud -> downWith(context, store, id, dir, cloud, force, claim) }

    fun upload(context: Context, store: Store, id: String, force: Boolean = false): Result =
        sync(context, store, id, "up") { dir, cloud -> upWith(context, store, id, dir, cloud, force) }

    fun downloadOrDefer(context: Context, store: Store, id: String, claim: () -> Boolean): Result =
        downloadOrDefer(context, store, id, Executor { Thread(it, "cloud-deferred").start() }, claim)

    internal fun downloadOrDefer(context: Context, store: Store, id: String, executor: Executor, claim: () -> Boolean = { true }): Result {
        val app = context.applicationContext
        val g = game(app, store, id) ?: return done(store, id, "down", Result("skipped", 0, 0, "not-installed"))
        if (!g.second.cloud) return done(store, id, "down", Result("skipped", 0, 0, "off"))
        val ready = { (if (store == Store.UBISOFT) File(UbisoftConnect.prefix(app), "pfx") else prefix(app, g.first))?.let { CloudSavePaths.profile(it).isDirectory } == true }
        if (ready()) return download(app, store, id, claim = claim)
        executor.execute {
            val end = System.currentTimeMillis() + PREFIX_WAIT_MS
            while (System.currentTimeMillis() < end) {
                if (ready()) { download(app, store, id); return@execute }
                Thread.sleep(2000)
            }
            done(store, id, "down", Result("skipped", 0, 0, "no-prefix"))
        }
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
                    if (dir != null && store == Store.GOG) migrateGogNested(context, id, g.first, dir)
                    val cloud = if (dir != null) transport(context, store, id, g.first) else null
                    when {
                        cloud is UbisoftCloudTransport && dir?.parentFile?.name != cloud.user -> Result("skipped", 0, 0, "account-changed")
                        dir == null -> Result("skipped", 0, 0, reason)
                        cloud == null && store == Store.EA && signedIn(context, store) -> Result("failed", 0, 0, "sign-in-unreachable")
                        cloud == null -> Result("skipped", 0, 0, "not-signed-in")
                        cloud is EaCloud && cloud.localDir?.canonicalPath != dir.canonicalPath -> Result("skipped", 0, 0, "rules-changed")
                        cloud is EaCloud && caseCollision(localFiles(dir).keys, cloud::wants) -> Result("failed", 0, 0, "case-collision")
                        else -> body(dir, cloud).also { if (it.result == "ok") markSynced(context, store, id) }
                    }
                }
            }
        } catch (e: NoCloudSaves) {
            rememberNoCloud(prefs(context, store), id)
            Result("skipped", 0, 0, "no-cloud-saves")
        } catch (e: Exception) {
            Result("failed", 0, 0, e.javaClass.simpleName, detail = cloudFailureDetail(e))
        }
        return done(store, id, way, result)
    }

    internal fun caseCollision(names: Collection<String>, wants: (String) -> Boolean): Boolean =
        names.filter(wants).groupBy { it.lowercase() }.any { it.value.size > 1 }

    private fun done(store: Store, id: String, way: String, r: Result): Result {
        Log.i(TAG, "cloud ${store.id} $id $way ${r.line}")
        StoresState.logLine("cloud ${store.id} $id $way ${r.line}")
        return r
    }

    private fun localFiles(dir: File): Map<String, File> {
        if (!dir.isDirectory) return emptyMap()
        val root = runCatching { dir.toPath().toRealPath() }.getOrNull() ?: return emptyMap()
        val found = LinkedHashMap<String, File>()
        fun walk(d: File, prefix: String, ancestry: Set<java.nio.file.Path>) {
            for (child in d.listFiles().orEmpty()) {
                val real = runCatching { child.toPath().toRealPath() }.getOrNull() ?: continue
                if (!real.startsWith(root)) continue
                val name = prefix + child.name
                if (child.isDirectory) {
                    if (real !in ancestry) walk(child, "$name/", ancestry + real)
                } else if (child.isFile) found[name] = child
            }
        }
        walk(dir, "", setOf(root))
        return found
    }

    internal fun md5(f: File): String = md5(f.readBytes())

    internal fun md5(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private class Listing(val live: Map<String, CloudFile>, val deleted: Set<String>)

    private fun remote(cloud: CloudTransport, wanted: (String) -> Boolean): Listing {
        val files = cloud.list()
        val pool = Executors.newFixedThreadPool(PARALLEL, com.droiddeck.launcher.stores.download.DownloadQueue.workerFactory("cloud"))
        for (f in files) if (wanted(f.name)) pool.execute { runCatching { cloud.details(f) } }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
        val (gone, live) = files.partition(cloud::deleted)
        return Listing(live.associateBy { it.name }, gone.mapTo(HashSet()) { it.name })
    }

    internal fun downWith(context: Context, store: Store, id: String, dir: File, cloud: CloudTransport, force: Boolean, claim: () -> Boolean = { true }): Result {
        val files = localFiles(dir).filterKeys(cloud::wants)
        val remote = remote(cloud) { it in files }.live
        val local = files.mapValues { CloudPlan.Local(md5(it.value)) }
        val key = scope(store, id, dir)
        val base = baseline(context, store, key)
        val planned = CloudPlan.down(local, remote.mapValues { CloudPlan.Remote(it.value.md5, it.value.modifiedMs) }, base)
        val alike = alike(cloud, planned.conflicts, local, remote)
        val plan = settled(planned, alike)
        val saved = savedConflicts(context, store, key).filter { it in remote && it !in alike && it !in plan.same }
        val take = if (force) (plan.transfer + plan.conflicts + saved).distinct() else plan.transfer
        val next = HashMap(base.orEmpty())
        var bytes = 0L
        val written = HashSet<String>()
        val changed = ArrayList<String>()
        val blocked = ArrayList<String>()
        val fetched = LinkedHashMap<String, Pair<CloudFile, Pair<ByteArray, Long>>>()
        for (name in take) {
            val f = remote[name] ?: continue
            if (!File(dir, name).canonicalPath.startsWith(dir.canonicalPath + File.separator)) { blocked += name; continue }
            fetched[name] = f to cloud.get(f)
        }
        if (fetched.isNotEmpty() && !claim()) return Result("skipped", 0, 0, "launch-moved-on")
        val replaced = fetched.keys.mapNotNull { name -> files[name] ?: File(dir, name).takeIf { it.isFile } }
        if (!backupReplaced(context, store, id, dir, replaced)) return Result("failed", 0, 0, "backup-failed")
        for ((name, got) in fetched) {
            val (f, content) = got
            val (data, modified) = content
            val dest = File(dir, name)
            if ((if (dest.isFile) md5(dest) else null) != local[name]?.md5) { changed += name; continue }
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
            if (modified > 0) dest.setLastModified(modified)
            bytes += data.size
            written += name
            next[name] = CloudPlan.Entry(md5(data), f.md5, if (modified > 0) modified else f.modifiedMs)
        }
        for (name in plan.same) remote[name]?.let { r -> next[name] = CloudPlan.Entry(local.getValue(name).md5, r.md5, r.modifiedMs) }
        saveBaseline(context, store, key, next)
        val left = (if (force) take.filter { it !in written } else plan.conflicts + saved.filter { it !in written } + changed)
            .filter { it !in blocked }.distinct()
        saveConflicts(context, store, key, left)
        val reason = when {
            take.isEmpty() -> "up-to-date"
            changed.isNotEmpty() -> "changed-locally"
            blocked.isNotEmpty() -> "blocked-path"
            else -> "downloaded"
        }
        val detail = listOfNotNull(
            changed.takeIf { it.isNotEmpty() }?.joinToString(prefix = "changed locally: "),
            blocked.takeIf { it.isNotEmpty() }?.joinToString(prefix = "outside the save folder: "),
        ).joinToString("; ")
        return Result(if (written.size < take.size) "partial" else "ok", written.size, bytes, reason, left.size, detail)
    }

    internal fun upWith(context: Context, store: Store, id: String, dir: File, cloud: CloudTransport, force: Boolean): Result {
        val key = scope(store, id, dir)
        val base = baseline(context, store, key) ?: return Result("skipped", 0, 0, "no-baseline")
        val files = localFiles(dir).filterKeys(cloud::wants).let { all -> if (cloud is UbisoftCloudTransport) all.filterValues(::markUbisoftSave) else all }
        if (files.isEmpty()) return Result("skipped", 0, 0, "nothing-local")
        val listing = remote(cloud) { it in files }
        val remote = listing.live
        val local = files.mapValues { CloudPlan.Local(md5(it.value)) }
        val planned = CloudPlan.up(local, remote.mapValues { CloudPlan.Remote(it.value.md5, it.value.modifiedMs) }, base, listing.deleted)
        val alike = alike(cloud, planned.conflicts, local, remote)
        val plan = settled(planned, alike)
        val saved = savedConflicts(context, store, key).filter { it !in alike && it !in plan.same }
        var send = CloudPlan.upSend(plan, force, cloud is GogCloud, files.keys)
        val next = HashMap(base)
        for (name in plan.same) remote[name]?.let { r -> next[name] = CloudPlan.Entry(local.getValue(name).md5, r.md5, r.modifiedMs) }
        var drifted = emptyList<String>()
        var raced = emptyList<String>()
        if (send.isNotEmpty()) {
            val copies = backupCloud(context, store, id, cloud, send.mapNotNull { remote[it] })
            if (cloud is UbisoftCloudTransport && !force) {
                drifted = send.filter { name -> val synced = base[name]?.localMd5; synced != null && copies[name]?.let { it != synced } == true }
                send = send - drifted.toSet()
            }
        }
        if (send.isNotEmpty()) {
            val stamped = HashMap<String, Long>()
            val sentMd5 = HashMap<String, String>()
            if (cloud is GogCloud) {
                // Stream one save at a time: gzip needs a full-size buffer and uploads can be large.
                val filesToSend = send.associateWith { files.getValue(it) }
                val results = cloud.putFiles(filesToSend, send.associateWith { files.getValue(it).lastModified() })
                for ((name, result) in results) {
                    if (result.modifiedMs > 0) stamped[name] = result.modifiedMs
                    result.md5?.let { sentMd5[name] = it }
                }
            } else {
                val data = send.associateWith { files.getValue(it).readBytes() }
                for ((name, bytes) in data) sentMd5[name] = md5(bytes)
                stamped += try {
                    cloud.put(data, send.associateWith { files.getValue(it).lastModified() })
                } catch (e: CloudChanged) {
                    raced = e.unsent
                    e.sent
                } catch (e: Exception) {
                    val kept = send.mapNotNull { name -> cloud.committed(name)?.let { name to it } }
                    if (kept.isNotEmpty()) {
                        for ((name, r) in kept) next[name] = CloudPlan.Entry(sentMd5.getValue(name), r.md5, r.modifiedMs)
                        saveBaseline(context, store, key, next)
                        saveConflicts(context, store, key, saved - kept.map { it.first }.toSet())
                    }
                    throw e
                }
                send = send.filter { it in stamped }
                for ((name, t) in stamped) if (t > 0) files[name]?.setLastModified(t)
            }
            val after = if (send.all { cloud.committed(it) != null }) emptyMap() else remote(cloud) { it in send && cloud.committed(it) == null }.live
            for (name in send) {
                val r = cloud.committed(name) ?: after[name]
                next[name] = CloudPlan.Entry(sentMd5.getValue(name), r?.md5, r?.modifiedMs ?: stamped[name] ?: -1L)
            }
        }
        saveBaseline(context, store, key, next)
        val left = (if (force) (plan.conflicts + saved).filter { it !in send } + raced
            else plan.conflicts + drifted + raced + saved.filter { it !in send && it in files }).distinct()
        saveConflicts(context, store, key, left)
        val reason = when {
            raced.isNotEmpty() -> "changed-in-cloud"
            send.isEmpty() -> "up-to-date"
            else -> "uploaded"
        }
        return Result(if (raced.isEmpty()) "ok" else "partial", send.size, send.sumOf { files.getValue(it).length() }, reason, left.size,
            if (raced.isEmpty()) "" else raced.joinToString(prefix = "changed in the cloud: "))
    }


    private fun marks(context: Context) = context.applicationContext.getSharedPreferences("stores.cloud", Context.MODE_PRIVATE)

    fun markDirty(context: Context, store: Store, id: String) {
        marks(context).edit().putLong("dirty:${store.id}:$id", System.currentTimeMillis()).apply()
    }

    fun dirty(context: Context): List<Pair<Store, String>> = marks(context).all.keys.mapNotNull { k ->
        val parts = k.split(':', limit = 3)
        if (parts.size == 3 && parts[0] == "dirty") Store.byId(parts[1])?.let { it to parts[2] } else null
    }

    private val markLock = Any()

    private val FINAL_SKIPS = setOf("off", "no-cloud-saves", "no-baseline", "not-installed", "nothing-local")

    fun uploadDirty(context: Context, store: Store, id: String, trigger: String): Result? = synchronized(markLock) {
        val key = "dirty:${store.id}:$id"
        if (!marks(context).contains(key)) return null
        val r = sync(context, store, id, "up trigger=$trigger") { dir, cloud -> upWith(context, store, id, dir, cloud, force = false) }
        if (r.result == "ok" || (r.result == "skipped" && r.reason in FINAL_SKIPS)) marks(context).edit().remove(key).apply()
        r
    }

    fun uploadAllDirty(context: Context, trigger: String, running: Boolean = com.droiddeck.launcher.session.SessionState.running) {
        if (trigger == "recovery" && running) return
        for ((store, id) in dirty(context)) uploadDirty(context, store, id, trigger)
    }

    private fun backupRoot(context: Context, store: Store, id: String) =
        File(context.filesDir, "stores/cloud-backups/${store.id}-${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}")

    private fun markUbisoftSave(file: File): Boolean {
        val head = ByteArray(40)
        var filled = 0
        file.inputStream().use { input ->
            while (filled < head.size) {
                val n = input.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
        }
        if (filled < head.size) return false
        if (UbisoftCloud.mark(head) != null) return true
        if (head.copyOfRange(8, 40).any { it != 0.toByte() }) return false
        java.io.RandomAccessFile(file, "rw").use { it.seek(8); it.write(UbisoftCloud.freshMark().toByteArray(Charsets.ISO_8859_1)) }
        return true
    }

    private fun backupCloud(context: Context, store: Store, id: String, cloud: CloudTransport, files: List<CloudFile>): Map<String, String> {
        if (files.isEmpty()) return emptyMap()
        val root = backupRoot(context, store, id)
        val target = File(root, "cloud-${System.currentTimeMillis()}")
        val copies = HashMap<String, String>()
        for (f in files) {
            val dest = File(target, f.name)
            if (!dest.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
            dest.parentFile?.mkdirs()
            val data = cloud.get(f).first
            dest.writeBytes(data)
            copies[f.name] = md5(data)
        }
        prune(root, "cloud")
        return copies
    }

    private fun backupReplaced(context: Context, store: Store, id: String, dir: File, replaced: List<File>): Boolean {
        if (replaced.isEmpty()) return true
        val base = runCatching { dir.canonicalPath + File.separator }.getOrNull() ?: return false
        val root = backupRoot(context, store, id)
        val stamp = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
        val staging = File(root, "staging-$stamp")
        val copied = runCatching {
            for (f in replaced) {
                check(f.canonicalPath.startsWith(base))
                f.copyTo(File(staging, f.relativeTo(dir).path), overwrite = true)
            }
            check(staging.renameTo(File(root, "local-$stamp")))
        }.isSuccess
        if (!copied) {
            staging.deleteRecursively()
            return false
        }
        prune(root, "local")
        return true
    }

    private fun prune(root: File, kind: String) {
        root.listFiles { f -> f.name.startsWith("$kind-") }?.sortedByDescending { it.name }?.drop(BACKUPS)?.forEach { it.deleteRecursively() }
    }
}
