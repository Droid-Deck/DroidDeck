package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object EsyncPacks {
    private const val TAG = "DroidDeckSync"
    const val REPO = "Droid-Deck/DroidDeck-Components"
    const val INDEX_URL = "https://github.com/$REPO/releases/download/droiddeck-esync-index/index.json"
    const val SIG_URL = "$INDEX_URL.sig"
    const val ASSET_PREFIX = "https://github.com/$REPO/releases/download/droiddeck-esync-"
    const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFyqZQAMh3g3nsEGLU+kBGVPBEkXrDv5F3kPtuQ+FV1DkTCGmYUQ1LS9wJtHGt0Ht4wToHGGEp9yzDibYZ4/zhg=="
    const val NTDLL = "files/lib/wine/aarch64-unix/ntdll.so"
    const val WINESERVER = "files/bin-arm64/wineserver"
    const val GUEST_STORE = "/root/.local/share/droiddeck-esync"
    private const val STORE = "root/.local/share/droiddeck-esync"
    private const val PACK_JSON = "pack.json"
    private const val COMPLETE = ".complete"
    private const val IGNORED = ".ignored"
    private const val DIST_JSON = ".droiddeck-esync.json"
    private const val WANTED = "wanted.tsv"
    private const val TOOLS = "tools.tsv"
    private const val FLOOR = "floor"
    private const val PACK_FAILURES = "packFailures"
    private const val PROGRESS_LABEL = "Fetching droiddeck-esync pack"
    private const val INDEX_LIMIT = 4L shl 20
    private const val SIG_LIMIT = 4096L
    private const val ASSET_LIMIT = 256L shl 20
    private const val PACK_JSON_LIMIT = 1L shl 20
    private const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    private const val WANTED_AGE_MS = 30L * 60 * 1000
    private const val FAILURE_BACKOFF_MS = 10L * 60 * 1000
    private const val STALE_MS = 60L * 60 * 1000
    private const val PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 30_000
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ASSET_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")
    private val TEMP = Regex("\\..+\\.tmp-[0-9a-f]{16}")
    private val RETIRED = Regex("\\..+\\.del-[0-9a-f]{16}")
    private val BINARIES = listOf(NTDLL, WINESERVER)
    private val EXPECTED = setOf(PACK_JSON, NTDLL, WINESERVER)
    private val REAL_DIRS = setOf(".", "files", "files/bin-arm64", "files/lib", "files/lib/wine", "files/lib/wine/aarch64-unix")
    private val DIRECTORIES = REAL_DIRS - "." + ""
    private val STOCK_LINKS = setOf("dist.lock", "files/steampipe_fixups_mtime")

    data class Asset(val url: String, val sha256: String, val size: Long)

    data class Entry(
        val id: String,
        val flavor: String,
        val rev: Int,
        val version: String,
        val versionLine: String,
        val stock: Map<String, String>,
        val exports: String,
        val sourceMatch: Boolean,
        val files: Map<String, String>,
        val asset: Asset,
        val revoked: Boolean,
    )

    data class Index(val schema: Int, val generated: Long, val packs: List<Entry>)

    private class Signed(val bytes: ByteArray, val sig: ByteArray, val index: Index)

    data class Wanted(
        val ntdll: String,
        val wineserver: String,
        val version: String,
        val exports: String,
        val toolDir: String,
        val firstSeen: Long,
        val inUse: Rank? = null,
        val epoch: Long = 0L,
    ) {
        val key: String get() = "$ntdll:$wineserver"
    }

    data class Rank(val kind: Int, val sameVersion: Int, val rev: Int) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int = compareValuesBy(this, other, { it.kind }, { it.sameVersion }, { it.rev })
    }

    data class ToolState(
        val toolDir: String,
        val versionLine: String,
        val ntdll: String,
        val wineserver: String,
        val state: String,
        val packId: String?,
    )

    data class Status(val installed: Int, val wanted: Int, val tools: List<ToolState>) {
        fun tool(guestPath: String): ToolState? {
            val path = guestPath.trimEnd('/')
            return tools.firstOrNull { it.toolDir.trimEnd('/') == path }
        }
    }

    private val fetchLock = Any()
    private val cacheLock = Any()
    private val installLock = Any()
    private val pending = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "droiddeck-esync-fetch").apply { isDaemon = true }
    }
    private val random = SecureRandom()
    private val shippedKey: PublicKey by lazy { key(PUBLIC_KEY) }
    internal var keyOverride: PublicKey? = null
    private val publicKey: PublicKey get() = keyOverride ?: shippedKey
    internal var fetcher: (String, Long, OutputStream, ((Long) -> Unit)?) -> Boolean = { url, limit, out, progress ->
        httpFetch(url, limit, out, progress)
    }

    fun store(root: File): File = File(root, STORE)

    private fun cacheDir(context: Context) = File(context.filesDir, "droiddeck-esync")

    internal fun key(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun allowedUrl(url: String): Boolean =
        url.startsWith(ASSET_PREFIX) && ASSET_PATH.matches(url.substring(ASSET_PREFIX.length))

    fun parseIndex(bytes: ByteArray): Index {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val schema = root.getInt("schema")
        require(schema == 1) { "sync index schema $schema is not supported" }
        val generated = root.getLong("generated")
        val array = root.getJSONArray("packs")
        val packs = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val entry = try {
                parseEntry(array.getJSONObject(i))
            } catch (e: Exception) {
                Log.w(TAG, "sync index entry $i skipped: ${e.message}")
                continue
            }
            if (seen.add(entry.id)) packs.add(entry)
        }
        return Index(schema, generated, packs)
    }

    private fun parseEntry(o: JSONObject): Entry {
        val id = o.getString("id")
        require(ID.matches(id)) { "invalid id $id" }
        val asset = o.getJSONObject("asset")
        val url = asset.getString("url")
        require(allowedUrl(url)) { "$id: asset $url is outside $ASSET_PREFIX" }
        val sha256 = asset.getString("sha256").lowercase()
        require(SHA256.matches(sha256)) { "$id: invalid asset sha256" }
        val size = asset.getLong("size")
        require(size in 1..ASSET_LIMIT) { "$id: invalid asset size $size" }
        val flavor = o.getString("flavor")
        require(flavor.isNotEmpty()) { "$id: no flavor" }
        return Entry(
            id, flavor, o.getInt("rev"), o.optString("version"), o.optString("version_line"),
            hashes(o.getJSONObject("stock")), o.optString("exports").lowercase(), o.opt("source_match") == true,
            hashes(o.getJSONObject("files")), Asset(url, sha256, size), o.optBoolean("revoked", false),
        )
    }

    private fun hashes(o: JSONObject): Map<String, String> = BINARIES.associateWith { rel ->
        o.getString(rel).lowercase().also { require(SHA256.matches(it)) { "invalid sha256 for $rel" } }
    }

    fun verify(indexBytes: ByteArray, sigBytes: ByteArray): Boolean = try {
        verify(indexBytes, sigBytes, publicKey)
    } catch (e: Exception) {
        false
    }

    internal fun verify(indexBytes: ByteArray, sigBytes: ByteArray, key: PublicKey): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(indexBytes)
            verify(sigBytes)
        }
    } catch (e: Exception) {
        false
    }

    internal fun accept(indexBytes: ByteArray, sigBytes: ByteArray, cached: Index?, key: PublicKey = publicKey, floor: Long = 0L): Index? {
        if (!verify(indexBytes, sigBytes, key)) {
            Log.w(TAG, "sync index signature does not verify")
            return null
        }
        val index = try {
            parseIndex(indexBytes)
        } catch (e: Exception) {
            Log.w(TAG, "sync index unreadable: ${e.message}")
            return null
        }
        val minimum = maxOf(cached?.generated ?: Long.MIN_VALUE, floor)
        if (index.generated < minimum) {
            Log.w(TAG, "sync index ${index.generated} is older than $minimum, one already accepted; kept the cached one")
            return null
        }
        return index
    }

    internal fun kind(entry: Entry, row: Wanted): Int = when {
        entry.stock[NTDLL] == row.ntdll && entry.stock[WINESERVER] == row.wineserver ->
            if (entry.version != row.version && row.epoch > 0 && epochOf(entry.versionLine) > row.epoch) 0 else 2
        entry.sourceMatch && entry.version.isNotEmpty() && entry.version == row.version &&
            entry.exports.isNotEmpty() && entry.exports == row.exports -> 1
        else -> 0
    }

    fun matches(entry: Entry, row: Wanted): Boolean = !entry.revoked && kind(entry, row) > 0

    internal fun epochOf(versionLine: String): Long =
        versionLine.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.takeIf { it > 0 } ?: 0L

    internal fun withEpochs(wanted: List<Wanted>, tools: List<ToolState>): List<Wanted> = wanted.map { row ->
        tools.firstOrNull {
            it.toolDir == row.toolDir && it.ntdll.trim().lowercase() == row.ntdll && it.wineserver.trim().lowercase() == row.wineserver
        }?.let { row.copy(epoch = epochOf(it.versionLine)) } ?: row
    }

    internal fun rank(entry: Entry, row: Wanted): Rank =
        Rank(kind(entry, row), if (entry.version.isNotEmpty() && entry.version == row.version) 1 else 0, entry.rev)

    fun best(entries: List<Entry>, row: Wanted): Entry? = entries.filter { matches(it, row) }
        .maxWithOrNull(compareBy<Entry>({ rank(it, row) }, { it.id }))

    internal fun plan(index: Index, wanted: List<Wanted>, installed: Set<String>): List<Entry> =
        wanted.mapNotNull { row -> best(index.packs, row)?.takeIf { row.inUse == null || rank(it, row) > row.inUse } }
            .distinctBy { it.id }.filter { it.id !in installed }

    internal fun upgradeRows(store: File, tools: List<ToolState>): List<Wanted> = tools.mapNotNull { tool ->
        val ntdll = tool.ntdll.trim().lowercase()
        val wineserver = tool.wineserver.trim().lowercase()
        if (tool.state != "pack" || !SHA256.matches(ntdll) || !SHA256.matches(wineserver)) return@mapNotNull null
        val token = tool.versionLine.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
        val pack = tool.packId?.takeIf { ID.matches(it) }?.let { installedPack(store, it) }
        val exports = if (pack != null && pack.opt("source_match") == true && token.isNotEmpty() && pack.opt("version") == token)
            (pack.opt("exports") as? String).orEmpty() else ""
        val epoch = epochOf(tool.versionLine)
        val inUse = pack?.let { packRank(it, ntdll, wineserver, token, exports, epoch) }?.takeIf { it.kind > 0 }
        Wanted(ntdll, wineserver, token, exports, tool.toolDir, 0L, inUse, epoch)
    }

    private fun packRank(pack: JSONObject, ntdll: String, wineserver: String, token: String, exports: String, epoch: Long): Rank =
        Rank(covers(pack, ntdll, wineserver, token, exports, epoch), if (token.isNotEmpty() && pack.opt("version") == token) 1 else 0,
            pack.optInt("rev"))

    private fun installedPack(store: File, id: String): JSONObject? {
        val dir = File(store, "packs/$id")
        if (!File(dir, COMPLETE).isFile) return null
        return readFile(File(dir, PACK_JSON), PACK_JSON_LIMIT)
            ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
    }

    fun parseWanted(text: String): List<Wanted> = text.lineSequence().mapNotNull { line ->
        val fields = line.trimEnd('\r').split('\t')
        if (fields.size < 5) return@mapNotNull null
        val ntdll = fields[0].trim().lowercase()
        val wineserver = fields[1].trim().lowercase()
        if (!SHA256.matches(ntdll) || !SHA256.matches(wineserver)) return@mapNotNull null
        Wanted(ntdll, wineserver, field(fields[2]), field(fields[3]).lowercase(), fields[4],
            fields.getOrNull(5)?.trim()?.toLongOrNull() ?: 0L)
    }.distinctBy { it.key }.toList()

    private fun field(value: String): String = value.trim().takeIf { it != "-" }.orEmpty()

    fun parseTools(text: String): List<ToolState> = text.lineSequence().mapNotNull { line ->
        val fields = line.trimEnd('\r').split('\t')
        if (fields.size < 6 || fields[0].isEmpty()) return@mapNotNull null
        ToolState(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5].takeIf { it.isNotEmpty() && it != "-" })
    }.toList()

    fun status(root: File): Status = try {
        val store = store(root)
        if (!store.isDirectory) Status(0, 0, emptyList())
        else {
            val wanted = readText(File(store, WANTED))?.let { parseWanted(it) }.orEmpty()
            val tools = readText(File(store, TOOLS))?.let { parseTools(it) }.orEmpty()
            Status(installedIds(store).size, wanted.size, settled(store, tools, wanted))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "sync status", t)
        Status(0, 0, emptyList())
    }

    internal fun settled(store: File, tools: List<ToolState>, wanted: List<Wanted>): List<ToolState> {
        if (tools.none { it.state == "wanted" }) return tools
        val packs = installedIds(store).sorted()
            .filter { !File(store, "packs/$it/$IGNORED").exists() }
            .mapNotNull { id -> installedPack(store, id)?.let { id to it } }
        val rows = wanted.associateBy { it.key }
        return tools.map { tool ->
            if (tool.state != "wanted") return@map tool
            val ntdll = tool.ntdll.trim().lowercase()
            val wineserver = tool.wineserver.trim().lowercase()
            val token = tool.versionLine.trim().split(Regex("\\s+")).getOrNull(1).orEmpty()
            val exports = rows["$ntdll:$wineserver"]?.exports.orEmpty()
            val epoch = epochOf(tool.versionLine)
            val match = packs.map { (id, pack) -> Triple(id, pack, packRank(pack, ntdll, wineserver, token, exports, epoch)) }
                .filter { it.third.kind > 0 }
                .maxWithOrNull(compareBy({ it.third }, { it.first }))
            if (match == null) tool else tool.copy(state = "pack", packId = match.first)
        }
    }

    private fun covers(pack: JSONObject, ntdll: String, wineserver: String, token: String, exports: String, epoch: Long): Int {
        val stock = pack.optJSONObject("stock")
        val version = pack.opt("version") as? String
        if (stock != null && stock.optString(NTDLL) == ntdll && stock.optString(WINESERVER) == wineserver) {
            return if (version != token && epoch > 0 && epochOf(pack.optString("version_line")) > epoch) 0 else 2
        }
        val packExports = pack.opt("exports") as? String
        return if (pack.opt("source_match") == true && token.isNotEmpty() && version == token &&
            exports.isNotEmpty() && packExports == exports) 1 else 0
    }

    fun distPathsFor(root: File, stockGuestPath: String): List<String> = try {
        val wanted = stockGuestPath.trimEnd('/')
        if (wanted.isEmpty()) emptyList()
        else File(store(root), "dist").listFiles().orEmpty()
            .filter { !it.name.startsWith(".") && it.isDirectory }
            .mapNotNull { dir ->
                val record = readText(File(dir, DIST_JSON))?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: return@mapNotNull null
                val stock = record.optString("stock").trimEnd('/')
                val tool = record.optString("tool").trimEnd('/')
                if (stock == wanted || tool == wanted) "$GUEST_STORE/dist/${dir.name}" else null
            }
            .sorted()
    } catch (t: Throwable) {
        Log.w(TAG, "sync dists", t)
        emptyList()
    }

    fun stageBundled(context: Context, root: File) {
        try {
            val store = store(root)
            if (!store.isDirectory && !store.mkdirs()) {
                Log.w(TAG, "could not create $store")
                return
            }
            val bundled = bundledIndex(context)
            val trusted = if (bundled == null) cachedIndex(context)
            else storeIndex(context, bundled.bytes, bundled.sig, bundled.index, newerOnly = true)
            if (trusted != null) removeRevoked(store, trusted)
            if (bundled == null) return
            if (trusted == null) {
                Log.w(TAG, "the bundled sync index is older than one already accepted; bundled packs skipped")
                return
            }
            val revoked = (bundled.index.packs + trusted.packs).filter { it.revoked }.map { it.id }.toSet()
            val shipped = context.assets.list("droiddeck-esync/packs")?.toSet().orEmpty()
            val missing = bundled.index.packs.filter {
                it.id !in revoked && "${it.id}.tzst" in shipped && !File(store, "packs/${it.id}/$COMPLETE").isFile
            }
            if (missing.isEmpty()) return
            sweep(context, store)
            for (entry in missing) installBundled(context, store, entry)
        } catch (t: Throwable) {
            Log.w(TAG, "bundled sync packs", t)
        }
    }

    private fun bundledIndex(context: Context): Signed? {
        val bundle = context.assets.list("droiddeck-esync")?.toSet().orEmpty()
        if ("index.json" !in bundle || "index.json.sig" !in bundle) return null
        val bytes = context.assets.open("droiddeck-esync/index.json").use { readBounded(it, INDEX_LIMIT) }
        val sig = context.assets.open("droiddeck-esync/index.json.sig").use { readBounded(it, SIG_LIMIT) }
        if (!verify(bytes, sig)) {
            Log.w(TAG, "the bundled sync index does not verify; bundled packs skipped")
            return null
        }
        return Signed(bytes, sig, parseIndex(bytes))
    }

    private fun installBundled(context: Context, store: File, entry: Entry) {
        val copy = try {
            tempFile(context, entry.id)
        } catch (e: IOException) {
            Log.w(TAG, "bundled sync pack ${entry.id}", e)
            return
        }
        try {
            context.assets.open("droiddeck-esync/packs/${entry.id}.tzst").use { input ->
                FileOutputStream(copy).use { output -> copyBounded(input, output, entry.asset.size) }
            }
            if (install(store, entry, copy)) {
                Log.i(TAG, "sync pack ${entry.id} installed from the app")
                SessionEvents.record("sync.pack", mapOf("id" to entry.id, "source" to "bundled", "result" to "installed"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bundled sync pack ${entry.id}", t)
            SessionEvents.record("sync.pack", mapOf("id" to entry.id, "source" to "bundled", "result" to "failed", "reason" to t.message))
        } finally {
            copy.delete()
        }
    }

    fun fetchInBackground(context: Context, root: File) {
        val app = context.applicationContext ?: context
        if (!pending.compareAndSet(false, true)) return
        try {
            executor.execute {
                pending.set(false)
                fetchWanted(app, root)
            }
        } catch (t: Throwable) {
            pending.set(false)
            Log.w(TAG, "could not schedule a sync fetch", t)
        }
    }

    fun enabled(context: Context): Boolean = SessionPrefs.syncFallback(context) || try {
        GameEnvironmentStore.read(context).let { config ->
            (listOf(config.shared) + config.games.values).any { it["BL_SYNC_FALLBACK"] == "1" }
        }
    } catch (t: Throwable) {
        false
    }

    fun fetchWanted(context: Context, root: File, onProgress: ((String, Int) -> Unit)? = null): Int = synchronized(fetchLock) {
        try {
            val store = store(root)
            if (!store.isDirectory) return 0
            cachedIndex(context)?.let { removeRevoked(store, it) }
            if (!enabled(context)) return 0
            sweep(context, store)
            val toolsText = readText(File(store, TOOLS))
            val tools = toolsText?.let { parseTools(it) }.orEmpty()
            val wanted = withEpochs(readText(File(store, WANTED))?.let { parseWanted(it) }.orEmpty(), tools)
            val index = refreshIndex(context, wanted) ?: return 0
            removeRevoked(store, index)
            val rows = wanted + upgradeRows(store, tools)
            if (toolsText != null) prune(store, keep(store, index, rows, tools, bundledIds(context)), System.currentTimeMillis())
            val planned = plan(index, rows, installedIds(store))
            val state = loadState(context)
            val previous = state.optJSONObject(PACK_FAILURES)
            val failures = pruneFailures(previous, index)
            val missing = planned.filter { !failedRecently(failures, it, System.currentTimeMillis()) }
            var added = 0
            for (entry in missing) {
                if (download(context, store, entry, onProgress)) {
                    added++
                    failures.remove(entry.id)
                } else {
                    failures.put(entry.id, failure(failures, entry, System.currentTimeMillis()))
                }
            }
            val kept = failures.takeIf { it.length() > 0 }
            if (kept?.toString() != previous?.toString()) {
                if (kept != null) state.put(PACK_FAILURES, kept) else state.remove(PACK_FAILURES)
                saveState(context, state)
            }
            if (planned.size > missing.size) Log.i(TAG, "sync: ${planned.size - missing.size} pack(s) wait after failed downloads")
            if (missing.isNotEmpty()) Log.i(TAG, "sync: $added of ${missing.size} wanted pack(s) installed")
            added
        } catch (t: Throwable) {
            Log.w(TAG, "fetching sync packs", t)
            0
        }
    }

    fun refreshIndex(context: Context, wanted: List<Wanted> = emptyList()): Index? = synchronized(fetchLock) {
        val cached = cachedIndex(context)
        val state = loadState(context)
        val now = System.currentTimeMillis()
        if (!due(cached, state, wanted, now)) return cached
        val bytes = fetchBytes(INDEX_URL, INDEX_LIMIT)
        val sig = if (bytes != null) fetchBytes(SIG_URL, SIG_LIMIT) else null
        val fresh = if (bytes != null && sig != null) accept(bytes, sig, cached, floor = floor(context)) else null
        if (bytes == null || sig == null || fresh == null) {
            state.put("failedAt", now)
            saveState(context, state)
            return cached
        }
        val trusted = storeIndex(context, bytes, sig, fresh, newerOnly = false) ?: return cached
        state.remove("failedAt")
        state.put("checkedAt", now)
        state.put("misses", misses(state.optJSONObject("misses"), trusted, wanted, now))
        saveState(context, state)
        Log.i(TAG, "sync index ${trusted.generated}: ${trusted.packs.size} pack(s)")
        trusted
    }

    internal fun due(cached: Index?, state: JSONObject, wanted: List<Wanted>, now: Long): Boolean {
        val failed = state.optLong("failedAt", 0L)
        if (failed > 0 && now - failed in 0 until FAILURE_BACKOFF_MS) return false
        val checked = state.optLong("checkedAt", 0L)
        if (cached == null || checked <= 0 || now < checked || now - checked >= MAX_AGE_MS) return true
        val misses = state.optJSONObject("misses")
        return wanted.any { row ->
            if (cached.packs.any { matches(it, row) }) return@any false
            val miss = misses?.optJSONObject(row.key)
            val count = miss?.optInt("n", 0) ?: 0
            if (count <= 0) now - checked >= WANTED_AGE_MS
            else now - (miss?.optLong("at", 0L) ?: 0L) >= backoff(count)
        }
    }

    private fun backoff(misses: Int): Long = minOf(WANTED_AGE_MS shl minOf(misses - 1, 5), MAX_AGE_MS)

    private fun packBackoff(failures: Int): Long = minOf(FAILURE_BACKOFF_MS shl minOf(failures - 1, 7), MAX_AGE_MS)

    internal fun pruneFailures(previous: JSONObject?, index: Index): JSONObject {
        val out = JSONObject()
        if (previous == null) return out
        val assets = index.packs.associate { it.id to it.asset.sha256 }
        for (id in previous.keys()) {
            val record = previous.optJSONObject(id) ?: continue
            if (assets[id] == record.optString("sha")) out.put(id, record)
        }
        return out
    }

    internal fun failedRecently(failures: JSONObject?, entry: Entry, now: Long): Boolean {
        val record = failures?.optJSONObject(entry.id) ?: return false
        if (record.optString("sha") != entry.asset.sha256) return false
        val count = record.optInt("n", 0)
        return count > 0 && now - record.optLong("at", 0L) in 0 until packBackoff(count)
    }

    internal fun failure(failures: JSONObject, entry: Entry, now: Long): JSONObject {
        val record = failures.optJSONObject(entry.id)?.takeIf { it.optString("sha") == entry.asset.sha256 }
        return JSONObject().put("sha", entry.asset.sha256).put("n", (record?.optInt("n", 0) ?: 0) + 1).put("at", now)
    }

    private fun misses(previous: JSONObject?, index: Index, wanted: List<Wanted>, now: Long): JSONObject {
        val out = JSONObject()
        for (row in wanted) {
            if (index.packs.any { matches(it, row) }) continue
            val count = previous?.optJSONObject(row.key)?.optInt("n", 0) ?: 0
            out.put(row.key, JSONObject().put("n", count + 1).put("at", now))
        }
        return out
    }

    private fun loadState(context: Context): JSONObject = synchronized(cacheLock) {
        readFile(File(cacheDir(context), "state.json"), PACK_JSON_LIMIT)
            ?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: JSONObject()
    }

    private fun saveState(context: Context, state: JSONObject) = synchronized(cacheLock) {
        try {
            writeAtomic(File(cacheDir(context).apply { mkdirs() }, "state.json"), state.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "sync state", e)
        }
    }

    private fun cachedIndex(context: Context): Index? = synchronized(cacheLock) {
        val dir = cacheDir(context)
        val bytes = readFile(File(dir, "index.json"), INDEX_LIMIT) ?: return null
        val sig = readFile(File(dir, "index.json.sig"), SIG_LIMIT) ?: return null
        if (!verify(bytes, sig)) {
            Log.w(TAG, "the cached sync index does not verify; ignoring it")
            return null
        }
        runCatching { parseIndex(bytes) }.getOrNull()
    }

    private fun floor(context: Context): Long = synchronized(cacheLock) {
        readFile(File(cacheDir(context), FLOOR), 64)?.toString(Charsets.UTF_8)?.trim()?.toLongOrNull() ?: 0L
    }

    private fun storeIndex(context: Context, bytes: ByteArray, sig: ByteArray, index: Index, newerOnly: Boolean): Index? = synchronized(cacheLock) {
        val cached = cachedIndex(context)
        val floor = floor(context)
        if (index.generated < floor) return cached
        if (cached != null && (cached.generated > index.generated || newerOnly && cached.generated >= index.generated)) return cached
        try {
            val dir = cacheDir(context).apply { mkdirs() }
            if (index.generated > floor) writeAtomic(File(dir, FLOOR), index.generated.toString().toByteArray(Charsets.UTF_8))
            writeAtomic(File(dir, "index.json.sig"), sig)
            writeAtomic(File(dir, "index.json"), bytes)
        } catch (e: IOException) {
            Log.w(TAG, "could not cache the sync index", e)
        }
        index
    }

    private fun download(context: Context, store: File, entry: Entry, onProgress: ((String, Int) -> Unit)?): Boolean {
        if (!allowedUrl(entry.asset.url)) {
            Log.w(TAG, "sync pack ${entry.id}: ${entry.asset.url} is outside $ASSET_PREFIX")
            return false
        }
        val part = try {
            tempFile(context, entry.id)
        } catch (e: IOException) {
            Log.w(TAG, "sync pack ${entry.id}", e)
            return false
        }
        try {
            onProgress?.invoke(PROGRESS_LABEL, -1)
            var reported = -1
            val fetched = FileOutputStream(part).use { output ->
                fetch(entry.asset.url, entry.asset.size, output) { done ->
                    val percent = (done * 100 / entry.asset.size).toInt().coerceIn(0, 100)
                    if (percent != reported) {
                        reported = percent
                        onProgress?.invoke(PROGRESS_LABEL, percent)
                    }
                }
            }
            if (!fetched) {
                SessionEvents.record("sync.pack", mapOf("id" to entry.id, "source" to "download", "result" to "failed", "reason" to "download"))
                return false
            }
            if (install(store, entry, part)) {
                Log.i(TAG, "sync pack ${entry.id} downloaded and installed")
                SessionEvents.record("sync.pack", mapOf("id" to entry.id, "source" to "download", "result" to "installed"))
            }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "sync pack ${entry.id}", t)
            SessionEvents.record("sync.pack", mapOf("id" to entry.id, "source" to "download", "result" to "failed", "reason" to t.message))
            return false
        } finally {
            part.delete()
        }
    }

    internal fun install(store: File, entry: Entry, archive: File): Boolean {
        require(archive.length() == entry.asset.size) { "${entry.id}: ${archive.length()} bytes, the index says ${entry.asset.size}" }
        require(Hashes.sha256(archive) == entry.asset.sha256) { "${entry.id}: the archive does not match the index checksum" }
        return TarArchiveInputStream(ZstdInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))).use {
            place(store, entry, it)
        }
    }

    internal fun place(store: File, entry: Entry, tar: TarArchiveInputStream): Boolean {
        val packs = File(store, "packs")
        if (!packs.isDirectory && !packs.mkdirs()) throw IOException("could not create $packs")
        val temp = File(packs, ".${entry.id}.tmp-${token()}")
        try {
            if (!temp.mkdir()) throw IOException("could not create $temp")
            unpack(tar, temp)
            val manifest = File(temp, PACK_JSON)
            checkPack(JSONObject(manifest.readText()), entry)
            for (rel in BINARIES) {
                val file = File(temp, rel)
                require(Hashes.sha256(file) == entry.files[rel]) { "${entry.id}: $rel does not match the index" }
                if (!file.setReadable(true, false) || !file.setExecutable(true, false)) throw IOException("could not mark $rel executable")
            }
            manifest.setReadable(true, false)
            if (!File(temp, COMPLETE).createNewFile()) throw IOException("could not mark ${entry.id} complete")
            return commit(packs, temp, entry.id)
        } finally {
            if (temp.exists()) FileUtils.delete(temp)
        }
    }

    private fun commit(packs: File, temp: File, id: String): Boolean = synchronized(installLock) {
        val target = File(packs, id)
        if (File(target, COMPLETE).isFile) return false
        if (target.exists() || isLink(target)) retire(packs, target)
        if (!temp.renameTo(target)) throw IOException("could not move sync pack $id into place")
        true
    }

    internal fun checkPack(pack: JSONObject, entry: Entry) {
        require(pack.opt("format") == 1) { "${entry.id}: pack.json format ${pack.opt("format")}" }
        require(pack.opt("id") == entry.id) { "pack.json names ${pack.opt("id")}, the index ${entry.id}" }
        require(pack.opt("flavor") == entry.flavor) { "${entry.id}: flavor differs from the index" }
        require(pack.opt("rev") == entry.rev) { "${entry.id}: rev differs from the index" }
        require(pack.opt("version") == entry.version) { "${entry.id}: version differs from the index" }
        require(!pack.has("version_line") || pack.opt("version_line") is String) { "${entry.id}: version_line is not a string" }
        require(strictHashes(pack.opt("stock")) == entry.stock) { "${entry.id}: stock hashes differ from the index" }
        require(pack.opt("exports") == entry.exports) { "${entry.id}: exports differ from the index" }
        require(pack.opt("source_match") == entry.sourceMatch) { "${entry.id}: source_match differs from the index" }
        require(strictHashes(pack.opt("files")) == entry.files) { "${entry.id}: file hashes differ from the index" }
        require(!pack.has("copy") || validCopy(pack.opt("copy"))) { "${entry.id}: invalid copy list" }
        require(!truthy(pack.opt("revoked")) && !truthy(pack.opt("ignored"))) { "${entry.id}: pack.json is revoked" }
    }

    private fun strictHashes(value: Any?): Map<String, String>? {
        if (value !is JSONObject) return null
        return BINARIES.associateWith { rel -> (value.opt(rel) as? String)?.takeIf { SHA256.matches(it) } ?: return null }
    }

    private fun validCopy(value: Any?): Boolean =
        value is JSONArray && (0 until value.length()).all { copyPath(value.opt(it)) }

    internal fun copyPath(value: Any?): Boolean {
        if (value !is String || value.isEmpty() || value.startsWith("/") || '\u0000' in value) return false
        if (value.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
        return value.substringBeforeLast('/', ".") in REAL_DIRS && value !in BINARIES && value !in REAL_DIRS &&
            value !in STOCK_LINKS && value != DIST_JSON
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is JSONArray -> value.length() > 0
        is JSONObject -> value.length() > 0
        else -> true
    }

    internal fun unpack(tar: TarArchiveInputStream, dest: File) {
        val seen = HashSet<String>()
        while (true) {
            val entry = tar.nextEntry ?: break
            val rel = entryPath(entry) ?: continue
            require(seen.add(rel)) { "$rel appears twice" }
            val limit = if (rel == PACK_JSON) PACK_JSON_LIMIT else ASSET_LIMIT
            require(entry.size in 0..limit) { "$rel is too large" }
            val out = File(dest, rel)
            out.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("could not create $it") }
            FileOutputStream(out).use {
                copyBounded(tar, it, limit)
                it.fd.sync()
            }
        }
        require(seen == EXPECTED) { "the pack lacks ${(EXPECTED - seen).joinToString()}" }
    }

    internal fun entryPath(entry: TarArchiveEntry): String? {
        val name = entry.name
        require(!name.startsWith("/")) { "absolute path $name" }
        val parts = name.split('/').filter { it.isNotEmpty() }.let { if (it.firstOrNull() == ".") it.drop(1) else it }
        require(parts.none { it == "." || it == ".." }) { "unsafe path $name" }
        require(!entry.isSymbolicLink && !entry.isLink) { "link entry $name" }
        require(!entry.isCharacterDevice && !entry.isBlockDevice && !entry.isFIFO) { "device entry $name" }
        val rel = parts.joinToString("/")
        if (entry.isDirectory) {
            require(rel in DIRECTORIES) { "unexpected directory $name" }
            return null
        }
        require(entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM) { "unsupported entry type for $name" }
        require(!entry.isSparse) { "sparse entry $name" }
        require(rel in EXPECTED) { "unexpected entry $name" }
        return rel
    }

    internal fun installedIds(store: File): Set<String> = File(store, "packs").listFiles().orEmpty()
        .filter { ID.matches(it.name) && File(it, COMPLETE).isFile }
        .map { it.name }
        .toSet()

    internal fun removeRevoked(store: File, index: Index): List<String> {
        val revoked = index.packs.filter { it.revoked }.map { it.id }.toSet()
        if (revoked.isEmpty()) return emptyList()
        val packs = File(store, "packs")
        val removed = ArrayList<String>()
        synchronized(installLock) {
            for (id in revoked) {
                val dir = File(packs, id)
                if (!dir.exists() && !isLink(dir)) continue
                retire(packs, dir)
                removed.add(id)
            }
        }
        for (id in removed) {
            Log.i(TAG, "sync pack $id was revoked; removed")
            SessionEvents.record("sync.pack", mapOf("id" to id, "result" to "revoked"))
        }
        return removed
    }

    private fun bundledIds(context: Context): Set<String> =
        context.assets.list("droiddeck-esync/packs").orEmpty().filter { it.endsWith(".tzst") }.map { it.removeSuffix(".tzst") }.toSet()

    internal fun keep(store: File, index: Index, rows: List<Wanted>, tools: List<ToolState>, bundled: Set<String>): Set<String> {
        val ids = HashSet(bundled)
        tools.mapNotNullTo(ids) { it.packId }
        rows.mapNotNullTo(ids) { best(index.packs, it)?.id }
        File(store, "dist").listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val record = readText(File(dir, DIST_JSON))?.let { runCatching { JSONObject(it) }.getOrNull() }
            (record?.opt("pack") as? String)?.let { ids.add(it) }
        }
        return ids
    }

    internal fun prune(store: File, keep: Set<String>, now: Long): List<String> {
        val packs = File(store, "packs")
        val removed = ArrayList<String>()
        synchronized(installLock) {
            for (id in installedIds(store).sorted()) {
                val dir = File(packs, id)
                if (id in keep || now - dir.lastModified() < PRUNE_AGE_MS) continue
                retire(packs, dir)
                removed.add(id)
            }
        }
        for (id in removed) {
            Log.i(TAG, "sync pack $id is no longer used; removed")
            SessionEvents.record("sync.pack", mapOf("id" to id, "result" to "pruned"))
        }
        return removed
    }

    private fun retire(packs: File, dir: File) {
        val gone = File(packs, ".${dir.name}.del-${token()}")
        if (dir.renameTo(gone)) FileUtils.delete(gone) else FileUtils.delete(dir)
    }

    private fun sweep(context: Context, store: File) {
        val now = System.currentTimeMillis()
        synchronized(installLock) {
            File(store, "packs").listFiles()?.forEach { file ->
                if (RETIRED.matches(file.name) || TEMP.matches(file.name) && now - file.lastModified() > STALE_MS) FileUtils.delete(file)
            }
        }
        File(cacheDir(context), "tmp").listFiles()?.forEach { file ->
            if (now - file.lastModified() > STALE_MS) FileUtils.delete(file)
        }
    }

    private fun fetchBytes(url: String, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (fetch(url, limit, out, null)) out.toByteArray() else null
    }

    private fun fetch(url: String, limit: Long, out: OutputStream, progress: ((Long) -> Unit)?): Boolean =
        fetcher(url, limit, out, progress)

    private fun httpFetch(url: String, limit: Long, out: OutputStream, progress: ((Long) -> Unit)?): Boolean {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DroidDeck-Android")
            }
            val code = connection.responseCode
            if (code / 100 != 2) {
                Log.w(TAG, "$url -> HTTP $code")
                return false
            }
            if (connection.contentLengthLong > limit) {
                Log.w(TAG, "$url is ${connection.contentLengthLong} bytes, more than $limit")
                return false
            }
            connection.inputStream.use { input ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) {
                        Log.w(TAG, "$url sent more than $limit bytes")
                        return false
                    }
                    out.write(buffer, 0, n)
                    progress?.invoke(total)
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "GET $url: ${e.message}")
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun copyBounded(input: InputStream, out: OutputStream, limit: Long): Long {
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > limit) throw IOException("more than $limit bytes")
            out.write(buffer, 0, n)
        }
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        copyBounded(input, out, limit)
        return out.toByteArray()
    }

    private fun readFile(file: File, limit: Long): ByteArray? = try {
        if (!file.isFile || file.length() > limit) null else file.readBytes()
    } catch (e: IOException) {
        null
    }

    private fun readText(file: File): String? = readFile(file, INDEX_LIMIT)?.toString(Charsets.UTF_8)

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, ".${file.name}.tmp-${token()}")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) throw IOException("could not replace $file")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun tempFile(context: Context, id: String): File {
        val dir = File(cacheDir(context), "tmp")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create $dir")
        return File(dir, "$id.${token()}.tzst")
    }

    private fun isLink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }

    private fun token(): String {
        val bytes = ByteArray(8)
        synchronized(random) { random.nextBytes(bytes) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
