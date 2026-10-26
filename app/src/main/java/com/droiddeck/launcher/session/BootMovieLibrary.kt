package com.droiddeck.launcher.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import org.json.JSONObject
import java.io.File

/**
 * The boot movies a person can pick from: DroidDeck's own two, a movie imported before this list existed, every `.webm` in
 * Steam's custom movies folder (imports, SteamDeckRepo and movies put there by hand; all of them can be removed), Steam's own
 * three startup movies and the movies bought in Steam's points shop (neither can be removed).
 */
object BootMovieLibrary {
    private const val TAG = "BootMovieLibrary"
    const val PREFIX = "file:"

    /** A movie Steam itself keeps outside its custom movies folder, such as one bought in its points shop: `steam:<path Steam names>`. */
    const val STEAM_PREFIX = "steam:"

    /** Held while the movie list is read and changed, so two imports at once cannot overwrite each other's entries. */
    private val lock = Any()

    /** Names DroidDeck stages the chosen movie under; they are copies, not movies of their own. */
    internal val STAGED = setOf("bigpicture_startup.webm", "steam_os_startup.webm", "droiddeck-startup.webm")

    const val SOURCE_DROIDDECK = "droiddeck"
    const val SOURCE_IMPORT = "import"
    const val SOURCE_REPO = "repo"
    const val SOURCE_STEAM = "steam"
    const val SOURCE_POINTSHOP = "pointshop"

    class Entry(val id: String, val title: String, val source: String, val file: File?, val removable: Boolean)

    internal fun moviesDir(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/config/uioverrides/movies")

    /**
     * The folders whose custom movies are listed, found and removed: [moviesDir], where imports are put and managed, then the
     * x86-64 client's. The order never depends on which client is running, so a saved choice keeps meaning the same file.
     */
    internal fun customMovieDirs(context: Context): List<File> =
        listOf(moviesDir(context), File(SessionPrefs.steamRoot(context, "x86_64"), "config/uioverrides/movies")).distinctBy { it.path }

    /** Where imported and installed movies are kept for good; Steam's folder holds a copy, which is put back if it goes missing. */
    internal fun libraryDir(context: Context) = File(context.filesDir, "boot-movie/library")

    private fun metaFile(context: Context) = File(context.filesDir, "boot-movie/movies.json")

    internal fun steamRoot(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")

    /** The running (else selected) Steam client's config, the one whose startup movie choice is read and recorded. */
    internal fun steamConfig(context: Context) = File(SessionPrefs.activeSteamRoot(context), "config/config.vdf")

    private fun thumbsDir(context: Context) = File(context.filesDir, "boot-movie/thumbs")

    fun idOf(name: String) = PREFIX + name

    /** Every Steam client folder: the usual one and the x86-64 client's. */
    internal fun steamRoots(context: Context): List<File> = listOf(
        steamRoot(context), File(LinuxRuntime.rootDir(context), "root/.droiddeck-x64/.local/share/Steam"),
    ).filter { it.isDirectory }

    private fun steamFile(context: Context, id: String): File? {
        val path = id.removePrefix(STEAM_PREFIX)
        if (!path.startsWith("/") || path.contains("..") || path.contains('\\') || !path.endsWith(".webm", ignoreCase = true)) return null
        return steamRoots(context).firstNotNullOfOrNull { root ->
            listOf("config", "steamui", "").map { File(root, it + path) }.firstOrNull { it.isFile && it.length() > 0L }
        }
    }

    fun file(context: Context, id: String): File? {
        if (id.startsWith(STEAM_PREFIX)) return steamFile(context, id)
        if (!id.startsWith(PREFIX)) return null
        val name = id.removePrefix(PREFIX)
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name in STAGED) return null
        return customMovieDirs(context).map { File(it, name) }.firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun isMovie(file: File) = isWebm(file) && file.name !in STAGED

    private fun isWebm(file: File) = file.isFile && file.length() > 0L && file.name.endsWith(".webm", ignoreCase = true) && !file.name.startsWith(".")

    private fun readMeta(context: Context): JSONObject =
        runCatching { JSONObject(metaFile(context).readText()) }.getOrDefault(JSONObject())

    /** Changes the movie list one writer at a time; [change] gets the list and returns whether to save it. */
    private fun editMeta(context: Context, change: (JSONObject) -> Boolean) = synchronized(lock) {
        val meta = readMeta(context)
        if (change(meta)) {
            val file = metaFile(context)
            file.parentFile?.mkdirs()
            val staged = File(file.parentFile, file.name + ".staged")
            runCatching {
                staged.writeText(meta.toString())
                if (!staged.renameTo(file)) staged.delete()
            }.onFailure { staged.delete(); Log.w(TAG, "could not write the movie list", it) }
        }
    }

    /** The post a movie was installed from, if it came from SteamDeckRepo. */
    fun repoId(context: Context, name: String): String? =
        readMeta(context).optJSONObject(name)?.optString("repo")?.takeIf { it.isNotEmpty() }

    fun installedRepoIds(context: Context): Set<String> {
        val meta = readMeta(context)
        return meta.keys().asSequence().filter { file(context, idOf(it)) != null }
            .mapNotNull { meta.optJSONObject(it)?.optString("repo")?.takeIf { id -> id.isNotEmpty() } }.toSet()
    }

    fun nameForRepo(context: Context, repoId: String): String? {
        val meta = readMeta(context)
        return meta.keys().asSequence().firstOrNull { file(context, idOf(it)) != null && meta.optJSONObject(it)?.optString("repo") == repoId }
    }

    fun list(context: Context): List<Entry> {
        val out = ArrayList<Entry>()
        out.add(Entry(BootMovie.DROIDDECK_30, "DroidDeck · 30 fps", SOURCE_DROIDDECK, null, removable = false))
        out.add(Entry(BootMovie.DROIDDECK_60, "DroidDeck · 60 fps", SOURCE_DROIDDECK, null, removable = false))
        BootMovie.customName(context)?.let { out.add(Entry(BootMovie.CUSTOM, it, SOURCE_IMPORT, BootMovie.legacyCustomFile(context), removable = true)) }
        restore(context)
        val meta = readMeta(context)
        val names = HashSet<String>()
        customMovieDirs(context).flatMap { dir -> dir.listFiles { f -> isMovie(f) }.orEmpty().toList() }
            .filter { names.add(it.name) }.sortedBy { it.name.lowercase() }.forEach { f ->
            val info = meta.optJSONObject(f.name)
            val source = info?.optString("source")?.takeIf { it.isNotEmpty() } ?: SOURCE_STEAM
            val title = info?.optString("title")?.takeIf { it.isNotEmpty() } ?: f.nameWithoutExtension
            // Everything in Steam's custom movies folder is the person's own (imported, from SteamDeckRepo or put there by hand).
            out.add(Entry(idOf(f.name), title, if (source == SOURCE_STEAM) SOURCE_IMPORT else source, f, removable = true))
        }
        val steam = LinkedHashMap<String, File>()
        SessionFiles.startupMoviePath(steamConfig(context))?.takeIf { !it.startsWith(OVERRIDES) }?.let { path ->
            steamFile(context, STEAM_PREFIX + path)?.let { steam[STEAM_PREFIX + path] = it }
        }
        val bought = HashMap<String, String>()
        for (root in steamRoots(context)) {
            File(root, "steamui/movies").listFiles { f -> isWebm(f) && f.nameWithoutExtension.lowercase() in NATIVE }.orEmpty()
                .sortedBy { it.name.lowercase() }.forEach { steam.putIfAbsent(STEAM_PREFIX + "/movies/" + it.name, it) }
            val scan = pointShopMovies(root)
            bought.putAll(scan.titles)
            for (found in scan.files) steam.putIfAbsent(STEAM_PREFIX + "/" + found.relativeTo(root).invariantSeparatorsPath, found)
        }
        val seen = HashSet<String>()
        for ((id, file) in steam) {
            val native = file.nameWithoutExtension.lowercase() in NATIVE
            if ((file.name in STAGED && !native) || !seen.add(file.canonicalPath)) continue
            // Steam names a bought movie by an item number and a hash; the points shop's own title is shown when Steam has it.
            val title = if (native) NATIVE[file.nameWithoutExtension.lowercase()] ?: file.nameWithoutExtension else bought[file.path] ?: boughtId(file)?.let { "#$it" } ?: file.nameWithoutExtension
            out.add(Entry(id, title, if (native) SOURCE_STEAM else SOURCE_POINTSHOP, file, removable = false))
        }
        return out
    }

    /** The four startup movies Steam ships with; Steam's movies folder holds many more videos that are not startup movies. */
    private val NATIVE = mapOf(
        "deck_startup" to "Deck startup", "oled_startup" to "Oled startup", "startup_machine" to "Machine startup",
        "steam_os_startup" to "SteamOS startup",
    )

    private val skippedFolders = setOf("uioverrides", "steamapps", "librarycache", "httpcache", "logs", "compatdata", "shadercache")

    private val scanLock = Any()
    private class Scan(val at: Long, val files: List<File>, val titles: Map<String, String>)

    private val scans = HashMap<String, Scan>()

    /** Forgets the last search for Steam's own movies. */
    internal fun forgetScan() = synchronized(scanLock) { scans.clear() }

    /**
     * Movies Steam keeps outside its custom movies folder, such as ones bought in its points shop: every `.webm` below the
     * client's config, cache and user folders. The search is repeated at most every 15 seconds for each client folder.
     */
    private fun pointShopMovies(root: File): Scan = synchronized(scanLock) {
        val now = System.currentTimeMillis()
        scans[root.path]?.let { if (now - it.at < 15_000L) return it }
        val found = listOf("config", "userdata", "appcache", "steamui").flatMap { top ->
            val base = File(root, top)
            if (!base.isDirectory) return@flatMap emptyList()
            base.walkTopDown().maxDepth(5)
                .onEnter { dir -> dir.name !in skippedFolders && !(top == "steamui" && dir.parentFile == base && dir.name == "movies") }
                .take(20_000).filter { isMovie(it) }.toList()
        }.sortedBy { it.path.lowercase() }
        val titles = HashMap<String, String>()
        for (file in found) boughtId(file)?.let { id -> boughtTitle(root, id)?.let { titles[file.path] = it } }
        val scan = Scan(now, found, titles)
        scans[root.path] = scan
        scan
    }

    /** The item number in the name Steam gives a bought movie, `<item number>_<hash>.webm`; null for any other name. */
    private fun boughtId(file: File) = Regex("^(\\d+)_").find(file.nameWithoutExtension)?.groupValues?.get(1)

    /** What the points shop calls a bought movie, from the list of startup movies Steam keeps in each user's local config. */
    private fun boughtTitle(root: File, id: String): String? {
        val key = "communityitemid\\\":\\\"$id\\\""
        val title = Regex("item_title\\\\\":\\\\\"([^\\\\\"]*)")
        for (user in File(root, "userdata").listFiles { f -> f.isDirectory }.orEmpty()) {
            val text = runCatching { File(user, "config/localconfig.vdf").readText() }.getOrNull() ?: continue
            val at = text.indexOf(key)
            if (at < 0) continue
            title.find(text.substring(at, (at + 3000).coerceAtMost(text.length)))?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private const val OVERRIDES = "/uioverrides/movies/"

    /** Puts back, in Steam's folder, any movie of DroidDeck's that went missing there, such as after the runtime was reset. */
    internal fun restore(context: Context) = synchronized(lock) {
        migrateLegacyMeta(context)
        val meta = readMeta(context)
        val library = libraryDir(context)
        meta.keys().asSequence().toList().forEach { name ->
            val kept = File(library, name)
            if (name.contains('/') || !kept.isFile || kept.length() == 0L) return@forEach
            val copy = File(moviesDir(context), name)
            if (!copy.isFile || copy.length() != kept.length()) mirror(kept, copy)
        }
    }

    /** Takes in the movie notes an earlier version kept inside Steam's movies folder, so those movies keep their title and source. */
    private fun migrateLegacyMeta(context: Context) {
        val legacy = File(moviesDir(context), ".droiddeck-movies.json")
        if (!legacy.isFile) return
        val old = runCatching { JSONObject(legacy.readText()) }.getOrNull() ?: return
        editMeta(context) { meta ->
            var changed = false
            old.keys().asSequence().toList().forEach { name ->
                val info = old.optJSONObject(name) ?: return@forEach
                if (meta.optJSONObject(name) == null && File(moviesDir(context), name).isFile) { meta.put(name, info); changed = true }
            }
            changed
        }
        legacy.delete()
    }

    /** Copies [kept] to [copy] through a staging name, so Steam never sees half a movie. */
    private fun mirror(kept: File, copy: File): Boolean {
        val staged = File(copy.parentFile, copy.name + ".${System.nanoTime()}.staged")
        return try {
            copy.parentFile?.mkdirs()
            kept.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
            staged.setReadable(true, false) && staged.renameTo(copy)
        } catch (e: Exception) {
            Log.w(TAG, "could not copy ${kept.name} into Steam's movies", e)
            false
        } finally {
            staged.delete()
        }
    }

    fun title(context: Context, id: String): String = list(context).firstOrNull { it.id == id }?.title ?: id

    /** The file a movie id plays from; DroidDeck's own movies live in the app and are unpacked on demand. */
    fun fileOf(context: Context, entry: Entry): File? = entry.file ?: BootMovie.assetFile(context, entry.id)

    /** A free `.webm` name for a movie called [title]; the same title twice gets " (2)", " (3)"… */
    fun freeName(context: Context, title: String): String {
        val base = title.replace(Regex("[^\\p{L}\\p{N} ._()\\-]"), "").replace(Regex("\\s+"), " ").trim().trim('.').take(60).ifEmpty { "movie" }
        val dirs = customMovieDirs(context)
        val library = libraryDir(context)
        var name = "$base.webm"
        var n = 2
        while (dirs.any { File(it, name).exists() } || File(library, name).exists() || name in STAGED) name = "$base ($n).webm".also { n++ }
        return name
    }

    /**
     * Keeps the finished movie [staged] (a file in [libraryDir]) under a free name, copies it into Steam's folder and notes its
     * title, source and cover. Returns the name, or null when it could not be kept. Runs one movie at a time.
     */
    internal fun commit(context: Context, staged: File, title: String, source: String, repoId: String? = null, cover: ByteArray? = null): String? =
        synchronized(lock) {
            val name = freeName(context, title)
            val kept = File(libraryDir(context), name)
            if (!staged.renameTo(kept)) return null
            if (!mirror(kept, File(moviesDir(context), name))) { kept.delete(); return null }
            register(context, name, title, source, repoId, cover)
            name
        }

    /** Notes a movie that was just put into Steam's folder: what to call it, where it came from and its cover. */
    fun register(context: Context, name: String, title: String, source: String, repoId: String? = null, cover: ByteArray? = null) {
        editMeta(context) { meta ->
            val info = JSONObject().put("title", title).put("source", source)
            if (repoId != null) info.put("repo", repoId)
            meta.put(name, info)
            true
        }
        if (cover != null) runCatching {
            thumbsDir(context).mkdirs()
            File(thumbsDir(context), coverName(name)).writeBytes(cover)
        }
    }

    fun remove(context: Context, id: String): Boolean {
        val removed = when {
            id == BootMovie.CUSTOM -> { BootMovie.removeCustom(context); true }
            else -> {
                // Only movies in Steam's custom movies folder are ever deleted; Steam's own and points shop movies are left alone.
                if (!id.startsWith(PREFIX)) return false
                val name = id.removePrefix(PREFIX)
                if (name.isEmpty() || name.contains('/') || name.contains('\\') || name in STAGED) return false
                val copies = customMovieDirs(context).map { File(it, name) }
                // A name can exist in more than one client's folder; the entry is the first one found (see customMovieDirs), and only that file is deleted.
                val target = copies.firstOrNull { it.exists() }
                val managed = File(libraryDir(context), name)
                if (readMeta(context).optJSONObject(name) == null && target == null && !managed.isFile) return false
                synchronized(lock) {
                    val main = File(moviesDir(context), name)
                    val another = copies.any { it != target && it.exists() }
                    val ownsRecord = target == null || target.path == main.path || (!another && !managed.isFile)
                    var gone = target == null || target.delete()
                    if (gone && ownsRecord) {
                        gone = !managed.exists() || managed.delete()
                        if (gone) {
                            editMeta(context) { it.remove(name) != null }
                            File(thumbsDir(context), coverName(name)).delete()
                            thumbsDir(context).listFiles { f -> f.name.startsWith("frame-" + safe(name) + "-") }?.forEach { it.delete() }
                        }
                    }
                    if (gone) {
                        val prefixes = listOfNotNull(target, main.takeIf { ownsRecord }).map { variantPrefix(it) }
                        File(context.filesDir, "boot-movie/variants").listFiles { f -> prefixes.any { f.name.startsWith(it) } }?.forEach { it.delete() }
                    }
                    gone
                }
            }
        }
        if (removed && SessionPrefs.bootMovie(context) == id) BootMovie.select(context, BootMovie.DROIDDECK_30)
        return removed
    }

    /** What every processed copy of [source] starts with; the hash of its full path keeps same-named files in different places apart. */
    internal fun variantPrefix(source: File) = safe(source.name) + "." + Integer.toHexString(source.absolutePath.hashCode()) + "-"

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun coverName(name: String) = "cover-" + safe(name)

    /** How long a movie runs, in whole seconds; 0 when it cannot be read. */
    fun durationSeconds(file: File?): Int {
        if (file == null || !file.isFile) return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            ((ms + 500) / 1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The first frame that is not nearly black out of several taken through the movie, else the brightest; a movie may open dark. */
    private fun brightestFrame(retriever: MediaMetadataRetriever, durationMs: Long): Bitmap? {
        var best: Bitmap? = null
        var bestLuma = -1
        for (fraction in doubleArrayOf(0.33, 0.5, 0.66, 0.2, 0.8, 0.1, 0.9)) {
            val frame = runCatching { retriever.getFrameAtTime((durationMs * 1000L * fraction).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull() ?: continue
            val luma = averageLuma(frame)
            if (luma >= MIN_COVER_LUMA) return frame
            if (luma > bestLuma) { best = frame; bestLuma = luma }
        }
        return best ?: runCatching { retriever.frameAtTime }.getOrNull()
    }

    private fun averageLuma(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 9, true)
        var sum = 0L
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            sum += (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
        }
        return (sum / (small.width * small.height)).toInt()
    }

    private const val MIN_COVER_LUMA = 24

    /** A cover for a movie: the picture SteamDeckRepo gave it, or one frame taken from the video and remembered. */
    fun cover(context: Context, entry: Entry): File? {
        val file = fileOf(context, entry) ?: return null
        val name = if (entry.id.startsWith(PREFIX)) file.name else entry.id
        // Points Shop covers once kept a picture that was not always the movie's own; those files are not used.
        val given = File(thumbsDir(context), if (entry.source == SOURCE_POINTSHOP) "v2-" + coverName(name) else coverName(name))
        if (given.isFile && given.length() > 0L) return given
        val key = "frame-" + safe(name) + "-v2-" + file.length() + "-" + file.lastModified() + ".jpg"
        val frame = File(thumbsDir(context), key)
        if (frame.isFile && frame.length() > 0L) return frame
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitmap = brightestFrame(retriever, ms) ?: return null
            val width = 480
            val scaled = if (bitmap.width > width) Bitmap.createScaledBitmap(bitmap, width, (bitmap.height * width / bitmap.width).coerceAtLeast(1), true) else bitmap
            thumbsDir(context).mkdirs()
            thumbsDir(context).listFiles { f -> f.name.startsWith("frame-" + safe(name) + "-") }?.forEach { it.delete() }
            val staged = File(thumbsDir(context), "$key.staged")
            staged.outputStream().use { scaled.compress(CompressFormat.JPEG, 82, it) }
            if (staged.renameTo(frame)) frame else null
        } catch (e: Exception) {
            Log.w(TAG, "no cover for ${file.name}", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
