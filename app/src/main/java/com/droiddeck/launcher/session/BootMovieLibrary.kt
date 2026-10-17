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
    private const val META = ".droiddeck-movies.json"

    /** Names DroidDeck stages the chosen movie under; they are copies, not movies of their own. */
    internal val STAGED = setOf("bigpicture_startup.webm", "steam_os_startup.webm", "droiddeck-startup.webm")

    const val SOURCE_DROIDDECK = "droiddeck"
    const val SOURCE_IMPORT = "import"
    const val SOURCE_REPO = "repo"
    const val SOURCE_STEAM = "steam"

    class Entry(val id: String, val title: String, val source: String, val file: File?, val removable: Boolean)

    internal fun moviesDir(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/config/uioverrides/movies")

    internal fun steamConfig(context: Context) = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/config/config.vdf")

    private fun thumbsDir(context: Context) = File(context.filesDir, "boot-movie/thumbs")

    fun idOf(name: String) = PREFIX + name

    fun file(context: Context, id: String): File? {
        if (!id.startsWith(PREFIX)) return null
        val name = id.removePrefix(PREFIX)
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name in STAGED) return null
        return File(moviesDir(context), name).takeIf { it.isFile && it.length() > 0L }
    }

    private fun isMovie(file: File) = file.isFile && file.length() > 0L && file.name.endsWith(".webm", ignoreCase = true) &&
        file.name !in STAGED && !file.name.startsWith(".")

    private fun readMeta(context: Context): JSONObject =
        runCatching { JSONObject(File(moviesDir(context), META).readText()) }.getOrDefault(JSONObject())

    private fun writeMeta(context: Context, meta: JSONObject) {
        val dir = moviesDir(context).apply { mkdirs() }
        val staged = File(dir, "$META.staged")
        runCatching {
            staged.writeText(meta.toString())
            if (!staged.renameTo(File(dir, META))) staged.delete()
        }.onFailure { staged.delete(); Log.w(TAG, "could not write the movie list", it) }
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
        val meta = readMeta(context)
        moviesDir(context).listFiles { f -> isMovie(f) }.orEmpty().sortedBy { it.name.lowercase() }.forEach { f ->
            val info = meta.optJSONObject(f.name)
            val source = info?.optString("source")?.takeIf { it.isNotEmpty() } ?: SOURCE_STEAM
            val title = info?.optString("title")?.takeIf { it.isNotEmpty() } ?: f.nameWithoutExtension
            out.add(Entry(idOf(f.name), title, source, f, removable = true))
        }
        return out
    }

    fun title(context: Context, id: String): String = list(context).firstOrNull { it.id == id }?.title ?: id

    /** The file a movie id plays from; DroidDeck's own movies live in the app and are unpacked on demand. */
    fun fileOf(context: Context, entry: Entry): File? = entry.file ?: BootMovie.assetFile(context, entry.id)

    /** A free `.webm` name for a movie called [title]; the same title twice gets " (2)", " (3)"… */
    fun freeName(context: Context, title: String): String {
        val base = title.replace(Regex("[^\\p{L}\\p{N} ._()\\-]"), "").replace(Regex("\\s+"), " ").trim().trim('.').take(60).ifEmpty { "movie" }
        val dir = moviesDir(context)
        var name = "$base.webm"
        var n = 2
        while (File(dir, name).exists() || name in STAGED) name = "$base ($n).webm".also { n++ }
        return name
    }

    /** Notes a movie that was just put into Steam's folder: what to call it, where it came from and its cover. */
    fun register(context: Context, name: String, title: String, source: String, repoId: String? = null, cover: ByteArray? = null) {
        val meta = readMeta(context)
        val info = JSONObject().put("title", title).put("source", source)
        if (repoId != null) info.put("repo", repoId)
        meta.put(name, info)
        writeMeta(context, meta)
        if (cover != null) runCatching {
            thumbsDir(context).mkdirs()
            File(thumbsDir(context), coverName(name)).writeBytes(cover)
        }
    }

    fun remove(context: Context, id: String): Boolean {
        val removed = when {
            id == BootMovie.CUSTOM -> { BootMovie.removeCustom(context); true }
            else -> {
                val file = file(context, id) ?: return false
                val name = file.name
                val gone = file.delete()
                if (gone) {
                    val meta = readMeta(context)
                    if (meta.remove(name) != null) writeMeta(context, meta)
                    File(thumbsDir(context), coverName(name)).delete()
                    thumbsDir(context).listFiles { f -> f.name.startsWith("frame-" + safe(name) + "-") }?.forEach { it.delete() }
                    File(context.filesDir, "boot-movie/variants").listFiles { f -> f.name.startsWith(variantPrefix(file)) }?.forEach { it.delete() }
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
