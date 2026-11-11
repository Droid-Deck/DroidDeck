package com.droiddeck.launcher.frontend

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Soundtrack {
    class Track(
        val file: File, val title: String, val disc: Int, val number: Int, val durationMs: Long, val artist: String = "",
        val discLabel: String = disc.toString(),
    ) {
        val id: String get() = file.path
    }

    class Album(val disc: Map<Int, List<Track>>, val artist: String, val cover: File?) {
        val tracks: List<Track> get() = disc.values.flatten()
        val discs: List<Int> get() = disc.keys.toList()
        val multiDisc: Boolean get() = disc.size > 1
        val totalMs: Long get() = tracks.sumOf { it.durationMs }
    }

    class Raw(
        val file: File, val folder: String, val tagDisc: Int?, val tagTrack: Int?, val tagTitle: String?, val tagArtist: String?,
        val durationMs: Long, val size: Long = file.length(),
    )

    val EXTENSIONS = setOf("mp3", "flac", "ogg", "oga", "opus", "wav", "m4a", "aac")
    private val LOSSLESS = setOf("flac", "wav")
    private val COVERS = listOf("AlbumArtwork", "albumartwork", "cover", "Cover", "folder", "Folder", "front", "Front", "artwork", "Artwork")
    private val COVER_EXT = listOf("png", "jpg", "jpeg", "webp")
    private val LEADING = Regex("^\\s*\\d+\\s*[.\\-_)]*\\s+")
    private val DIGITS = Regex("^\\s*(\\d+)")

    fun isAudio(file: File): Boolean = file.isFile && file.extension.lowercase() in EXTENSIONS

    fun cleanTitle(fileName: String): String {
        val base = fileName.substringBeforeLast('.', fileName).trim()
        val tail = if (" - " in base) base.substringAfterLast(" - ") else base
        val stripped = tail.replace(LEADING, "").trim()
        return stripped.ifEmpty { tail.ifEmpty { base } }
    }

    internal fun leadingNumber(fileName: String): Int? = DIGITS.find(fileName.substringAfterLast(" - "))?.groupValues?.get(1)?.toIntOrNull()

    private fun identity(r: Raw): String {
        val d = r.tagDisc
        val t = r.tagTrack
        if (d != null && t != null) return "n:$d:$t"
        val title = (r.tagTitle?.takeIf { it.isNotBlank() } ?: cleanTitle(r.file.name)).lowercase().filter { it.isLetterOrDigit() }
        return "t:$title:${leadingNumber(r.file.name)}"
    }

    private fun better(a: Raw, b: Raw): Raw {
        val la = a.file.extension.lowercase() in LOSSLESS
        val lb = b.file.extension.lowercase() in LOSSLESS
        return when {
            la != lb -> if (la) a else b
            a.size != b.size -> if (a.size > b.size) a else b
            else -> if (a.file.path <= b.file.path) a else b
        }
    }

    fun group(raws: List<Raw>): Map<Int, List<Track>> {
        if (raws.isEmpty()) return emptyMap()
        val unique = LinkedHashMap<String, Raw>()
        val home = HashMap<String, String>()
        val disc = HashMap<String, Int>()
        val track = HashMap<String, Int>()
        val title = HashMap<String, String>()
        for (r in raws) {
            val id = identity(r)
            unique[id] = unique[id]?.let { better(it, r) } ?: r
            if (r.folder.isNotEmpty() && home[id].isNullOrEmpty()) home[id] = r.folder
            r.tagDisc?.let { disc.putIfAbsent(id, it) }
            r.tagTrack?.let { track.putIfAbsent(id, it) }
            r.tagTitle?.takeIf { it.isNotBlank() }?.let { title.putIfAbsent(id, it) }
        }
        val kept = unique.map { (id, r) ->
            Raw(
                r.file, home[id] ?: r.folder, disc[id] ?: r.tagDisc, track[id] ?: r.tagTrack, title[id] ?: r.tagTitle, r.tagArtist,
                r.durationMs, r.size,
            )
        }
        val folders = kept.filter { it.tagDisc == null }.map { it.folder }.distinct().sortedWith(naturalOrder)
        val discs = kept.groupBy { r ->
            r.tagDisc ?: if (folders.size <= 1 && kept.none { it.tagDisc != null }) 0 else folders.indexOf(r.folder) + 1
        }
        return discs.toSortedMap().mapValues { (disc, list) ->
            list.sortedWith(
                compareBy<Raw>({ leadingNumber(it.file.name) ?: it.tagTrack ?: Int.MAX_VALUE }, { it.tagTrack ?: Int.MAX_VALUE }, { it.file.name.lowercase() }),
            ).mapIndexed { i, r ->
                Track(
                    r.file, r.tagTitle?.takeIf { it.isNotBlank() } ?: cleanTitle(r.file.name), disc, i + 1,
                    r.durationMs, r.tagArtist.orEmpty(), labelOf(r, disc),
                )
            }
        }
    }

    private val FOLDER_LABEL = Regex("[0-9][0-9 +&,.\\-]*")

    private fun labelOf(r: Raw, disc: Int): String {
        if (r.tagDisc != null) return disc.toString()
        val name = r.folder.substringAfterLast('/')
        return if (FOLDER_LABEL.matches(name)) name else disc.toString()
    }

    private val naturalOrder = Comparator<String> { a, b ->
        val x = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val y = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        var i = 0
        var out = 0
        while (out == 0 && i < minOf(x.size, y.size)) {
            val nx = x[i].toBigIntegerOrNull()
            val ny = y[i].toBigIntegerOrNull()
            out = if (nx != null && ny != null) nx.compareTo(ny) else x[i].compareTo(y[i])
            i++
        }
        if (out != 0) out else x.size.compareTo(y.size)
    }

    fun cover(dir: File): File? {
        for (name in COVERS) for (ext in COVER_EXT) File(dir, "$name.$ext").takeIf { it.isFile }?.let { return it }
        return dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in COVER_EXT }?.minByOrNull { it.name.lowercase() }
    }

    private fun parseNumber(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()

    private fun read(file: File, root: File): Raw {
        val folder = file.parentFile?.let { parent ->
            if (parent.path == root.path) "" else parent.path.removePrefix(root.path).trimStart('/')
        }.orEmpty()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            Raw(
                file, folder,
                parseNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                parseNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            Raw(file, folder, null, null, null, null, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun cacheFile(context: Context, root: File) = File(context.filesDir, "soundtrack-index/${root.path.hashCode().toUInt()}.json")

    private fun fromCache(context: Context, root: File, files: List<File>): List<Raw>? = runCatching {
        val f = cacheFile(context, root).takeIf { it.isFile } ?: return null
        val json = JSONObject(f.readText())
        if (json.optString("root") != root.path) return null
        val rows = json.getJSONArray("rows")
        val byPath = HashMap<String, JSONObject>()
        for (i in 0 until rows.length()) rows.getJSONObject(i).let { byPath[it.getString("p")] = it }
        if (byPath.size != files.size) return null
        files.map { file ->
            val row = byPath[file.path] ?: return null
            if (row.getLong("s") != file.length() || row.getLong("m") != file.lastModified()) return null
            Raw(
                file, row.getString("f"), row.optInt("d", -1).takeIf { it >= 0 }, row.optInt("n", -1).takeIf { it >= 0 },
                row.optString("t").takeIf { it.isNotEmpty() }, row.optString("a").takeIf { it.isNotEmpty() }, row.optLong("l"),
            )
        }
    }.getOrNull()

    private fun toCache(context: Context, root: File, raws: List<Raw>) {
        runCatching {
            val rows = JSONArray()
            for (r in raws) rows.put(
                JSONObject().put("p", r.file.path).put("s", r.file.length()).put("m", r.file.lastModified()).put("f", r.folder)
                    .put("d", r.tagDisc ?: -1).put("n", r.tagTrack ?: -1).put("t", r.tagTitle.orEmpty()).put("a", r.tagArtist.orEmpty())
                    .put("l", r.durationMs),
            )
            val f = cacheFile(context, root)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("root", root.path).put("rows", rows).toString())
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    fun audioFiles(root: File): List<File> {
        val out = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (c in children.sortedBy { it.name.lowercase() }) {
                if (c.isDirectory) { if (depth < 4) walk(c, depth + 1) } else if (isAudio(c)) out += c
            }
        }
        walk(root, 0)
        return out
    }

    private fun coverCache(context: Context, root: File): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(root.path.toByteArray(Charsets.UTF_8))
        val key = digest.take(16).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "soundtrack-index/cover-$key.jpg")
    }

    fun cachedCover(context: Context, root: File): File? = coverCache(context, root).takeIf { it.isFile && it.length() > 0L }

    private fun embeddedBytes(file: File): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            retriever.embeddedPicture
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun hashOf(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }

    private fun embeddedCover(context: Context, root: File, files: List<File>): File? {
        cachedCover(context, root)?.let { return it }
        val miss = File(coverCache(context, root).path + ".none")
        if (runCatching { miss.isFile && miss.readText().trim() == files.size.toString() }.getOrDefault(false)) return null
        for (file in files.take(8)) {
            val bytes = embeddedBytes(file) ?: continue
            val out = coverCache(context, root)
            runCatching {
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }.onSuccess { return out }
        }
        runCatching {
            miss.parentFile?.mkdirs()
            miss.writeText(files.size.toString())
        }
        return null
    }

    fun firstCover(context: Context, root: File): File? = cover(root) ?: embeddedCover(context, root, audioFiles(root))

    fun trackCover(context: Context, file: File): File? {
        val dir = File(context.filesDir, "soundtrack-index/track-covers")
        val marker = File(dir, "t-" + hashOf("${file.path}|${file.length()}|${file.lastModified()}".toByteArray(Charsets.UTF_8)))
        if (marker.isFile) {
            val name = runCatching { marker.readText().trim() }.getOrDefault("")
            if (name.isEmpty()) return null
            File(dir, name).takeIf { it.isFile && it.length() > 0L }?.let { return it }
        }
        val bytes = embeddedBytes(file)
        return runCatching {
            dir.mkdirs()
            if (bytes == null) {
                marker.writeText("")
                null
            } else {
                val out = File(dir, "c-" + hashOf(bytes) + ".jpg")
                if (!out.isFile || out.length() == 0L) out.writeBytes(bytes)
                marker.writeText(out.name)
                out
            }
        }.getOrNull()
    }

    fun load(context: Context, root: File, coverHint: File? = null): Album {
        val files = audioFiles(root)
        val raws = fromCache(context, root, files) ?: files.map { read(it, root) }.also { toCache(context, root, it) }
        val disc = group(raws)
        val artist = disc.values.flatten().map { it.artist }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        return Album(disc, artist, coverHint ?: cover(root) ?: embeddedCover(context, root, files))
    }

    fun formatLength(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
