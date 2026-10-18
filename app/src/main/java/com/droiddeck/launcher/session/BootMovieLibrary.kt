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
 * The boot movies a person can pick from: DroidDeck's own two, a movie imported before this list existed, and every
 * `.webm` in Steam's own movies folder (the folder Steam's startup movie picker lists too), whether it came from Steam,
 * from an import or from SteamDeckRepo.
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

    class Entry(val id: String, val title: String, val source: String, val file: File?, val removable: Boolean)

    internal fun moviesDir(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/config/uioverrides/movies")

    /** Where imported and installed movies are kept for good; Steam's folder holds a copy, which is put back if it goes missing. */
    internal fun libraryDir(context: Context) = File(context.filesDir, "boot-movie/library")

    private fun metaFile(context: Context) = File(context.filesDir, "boot-movie/movies.json")

    internal fun steamRoot(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam")

    internal fun steamConfig(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/config/config.vdf")

    private fun thumbsDir(context: Context) = File(context.filesDir, "boot-movie/thumbs")

    fun idOf(name: String) = PREFIX + name

    private fun steamFile(context: Context, id: String): File? {
        val path = id.removePrefix(STEAM_PREFIX)
        if (!path.startsWith("/") || path.contains("..") || path.contains('\\') || !path.endsWith(".webm", ignoreCase = true)) return null
        val root = steamRoot(context)
        return listOf("config", "steamui").map { File(root, it + path) }.firstOrNull { it.isFile && it.length() > 0L }
    }

    fun file(context: Context, id: String): File? {
        if (id.startsWith(STEAM_PREFIX)) return steamFile(context, id)
        if (!id.startsWith(PREFIX)) return null
        val name = id.removePrefix(PREFIX)
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name in STAGED) return null
        return File(moviesDir(context), name).takeIf { it.isFile && it.length() > 0L }
    }

    private fun isMovie(file: File) = file.isFile && file.length() > 0L && file.name.endsWith(".webm", ignoreCase = true) &&
        file.name !in STAGED && !file.name.startsWith(".")

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
        moviesDir(context).listFiles { f -> isMovie(f) }.orEmpty().sortedBy { it.name.lowercase() }.forEach { f ->
            val info = meta.optJSONObject(f.name)
            val source = info?.optString("source")?.takeIf { it.isNotEmpty() } ?: SOURCE_STEAM
            val title = info?.optString("title")?.takeIf { it.isNotEmpty() } ?: f.nameWithoutExtension
            // Only what DroidDeck put here can be removed; every other movie in the folder is Steam's own or the person's.
            out.add(Entry(idOf(f.name), title, source, f, removable = info != null && source != SOURCE_STEAM))
        }
        val seen = HashSet<String>()
        val others = ArrayList<String>()
        SessionFiles.startupMoviePath(steamConfig(context))?.takeIf { !it.startsWith(OVERRIDES) }?.let { others.add(it) }
        for ((folder, prefix) in listOf(File(steamRoot(context), "config/movies") to "/movies/", File(steamRoot(context), "steamui/movies") to "/movies/")) {
            folder.listFiles { f -> isMovie(f) }.orEmpty().sortedBy { it.name.lowercase() }.forEach { others.add(prefix + it.name) }
        }
        for (path in others) {
            val id = STEAM_PREFIX + path
            val file = steamFile(context, id) ?: continue
            if (!seen.add(id) || file.name in STAGED) continue
            out.add(Entry(id, file.nameWithoutExtension, SOURCE_STEAM, file, removable = false))
        }
        return out
    }

    private const val OVERRIDES = "/uioverrides/movies/"

    /** Puts back, in Steam's folder, any movie of DroidDeck's that went missing there, such as after the runtime was reset. */
    internal fun restore(context: Context) {
        val meta = readMeta(context)
        val library = libraryDir(context)
        meta.keys().asSequence().toList().forEach { name ->
            val kept = File(library, name)
            if (name.contains('/') || !kept.isFile || kept.length() == 0L) return@forEach
            val copy = File(moviesDir(context), name)
            if (!copy.isFile || copy.length() != kept.length()) mirror(kept, copy)
        }
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
        val dir = moviesDir(context)
        val library = libraryDir(context)
        var name = "$base.webm"
        var n = 2
        while (File(dir, name).exists() || File(library, name).exists() || name in STAGED) name = "$base ($n).webm".also { n++ }
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
                // Only a movie DroidDeck itself put here is ever deleted; Steam's own movies are left alone.
                if (!id.startsWith(PREFIX)) return false
                val name = id.removePrefix(PREFIX)
                if (name.isEmpty() || name.contains('/') || name.contains('\\') || name in STAGED || readMeta(context).optJSONObject(name) == null) return false
                val kept = File(libraryDir(context), name)
                val copy = File(moviesDir(context), name)
                val gone = (!copy.exists() || copy.delete()) && (!kept.exists() || kept.delete())
                if (gone) {
                    editMeta(context) { it.remove(name) != null }
                    File(thumbsDir(context), coverName(name)).delete()
                    thumbsDir(context).listFiles { f -> f.name.startsWith("frame-" + safe(name) + "-") }?.forEach { it.delete() }
                    File(context.filesDir, "boot-movie/variants").listFiles { f -> f.name.startsWith(variantPrefix(copy)) }?.forEach { it.delete() }
                }
                gone
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

    /** A cover for a movie: the picture SteamDeckRepo gave it, or one frame taken from the video and remembered. */
    fun cover(context: Context, entry: Entry): File? {
        val file = fileOf(context, entry) ?: return null
        val name = if (entry.id.startsWith(PREFIX)) file.name else entry.id
        val given = File(thumbsDir(context), coverName(name))
        if (given.isFile && given.length() > 0L) return given
        val key = "frame-" + safe(name) + "-" + file.length() + "-" + file.lastModified() + ".jpg"
        val frame = File(thumbsDir(context), key)
        if (frame.isFile && frame.length() > 0L) return frame
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitmap = retriever.getFrameAtTime(ms * 1000L / 3, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
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
