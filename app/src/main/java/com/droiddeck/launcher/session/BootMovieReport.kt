package com.droiddeck.launcher.session

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What DroidDeck and Steam know about boot movies, in one text file: Steam's startup movie setting, every movie file and
 * where it lives, DroidDeck's own state, and a short trace of what each session start did to Steam's config.
 */
object BootMovieReport {
    private const val TAG = "BootMovieReport"
    private const val MAX_TRACE_LINES = 200
    private const val MAX_HITS = 60
    private val KNOWN_TITLES = listOf("Turret Factory")
    private val SKIPPED = setOf("steamapps", "compatdata", "shadercache", "logs")
    private val BINARY = setOf("webm", "jpg", "jpeg", "png", "ico", "mp4", "ogg", "mp3", "wav", "ttf", "woff", "woff2", "gif", "webp", "bin", "so", "dll", "exe", "zip", "pak")
    private val lock = Any()

    private fun traceFile(context: Context) = File(context.filesDir, "boot-movie/apply.log")

    /** Adds a line to the short trace kept for the report; only the latest lines are kept. */
    fun note(context: Context, line: String) {
        synchronized(lock) {
            runCatching {
                val file = traceFile(context)
                file.parentFile?.mkdirs()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val lines = (if (file.isFile) file.readLines() else emptyList()) + "$stamp $line"
                file.writeText(lines.takeLast(MAX_TRACE_LINES).joinToString("\n") + "\n")
            }
        }
    }

    /** Writes the report to Downloads/DroidDeck, or to the app's own folder when that is not possible; null when the report cannot be built or neither folder works. */
    fun write(context: Context): File? {
        val text = runCatching { build(context) }.getOrElse {
            Log.w(TAG, "could not build the report", it)
            return null
        }
        val folders = listOfNotNull(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DroidDeck"),
            context.getExternalFilesDir(null),
        )
        for (folder in folders) {
            val file = File(folder, "boot-movie-report.txt")
            val saved = runCatching { folder.mkdirs(); file.writeText(text); true }.getOrDefault(false)
            if (saved) return file
            Log.w(TAG, "could not write the report to ${file.path}")
        }
        return null
    }

    private fun describe(file: File) =
        "${file.path}  ${file.length()} bytes  ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))}"

    internal fun build(context: Context): String {
        val out = StringBuilder()
        fun line(text: String = "") { out.append(text).append('\n') }
        fun listing(title: String, folder: File) {
            line("-- $title: ${folder.path}")
            val files = folder.listFiles()?.sortedBy { it.name.lowercase() }
            if (files == null) line("   (missing)") else if (files.isEmpty()) line("   (empty)")
            else files.forEach { line("   " + describe(it)) }
        }

        line("DroidDeck boot movie report, ${Date()}")
        line()
        line("== DroidDeck state")
        line("choice=${BootMovie.choice(context)}  saved choice=${SessionPrefs.bootMovie(context)}  pending pick=${SessionPrefs.bootMovieApplyNext(context)}")
        line("last Steam path seen=${SessionPrefs.bootMovieSteamSeen(context)}")
        line("sound=${SessionPrefs.bootMovieSound(context)}  volume=${SessionPrefs.bootMovieVolume(context)}  seconds=${SessionPrefs.bootMovieSeconds(context)}")
        line("session running=${SessionState.running}")
        line()
        line("== The movie list as shown")
        BootMovieLibrary.list(context).forEach { line("${it.id}  title=${it.title}  source=${it.source}  removable=${it.removable}  file=${it.file?.path}") }
        line()
        line("== DroidDeck folders")
        listing("library", BootMovieLibrary.libraryDir(context))
        listing("processed copies", File(context.filesDir, "boot-movie/variants"))
        val meta = File(context.filesDir, "boot-movie/movies.json")
        line("-- movie list file: ${if (meta.isFile) meta.readText().take(8000) else "(missing)"}")

        val roots = BootMovieLibrary.steamRoots(context)
        val movieIds = HashSet<String>()
        for (root in roots) {
            line()
            line("== Steam folder ${root.path}")
            val config = File(root, "config/config.vdf")
            line("config.vdf: ${if (config.isFile) describe(config) else "(missing)"}")
            val text = runCatching { config.readText() }.getOrNull()
            if (text != null) {
                line("-- StartupMovie block:")
                (SessionFiles.startupMovieBlock(config) ?: "   (none)").lines().forEach { line("   $it") }
                line("-- Customization section with the lines before it:")
                (SessionFiles.customizationBlock(config) ?: "   (none)").lines().forEach { line("   $it") }
                line("-- every line of config.vdf that mentions a movie:")
                text.lines().filter { it.contains("movie", ignoreCase = true) }.take(40).forEach { line("   ${it.trim()}") }
                Regex("\"MovieID\"\\s+\"([^\"]*)\"").find(text)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() && it != "0" }?.let { movieIds.add(it) }
            }
            listing("custom movies", File(root, "config/uioverrides/movies"))
            listing("Steam's own movies", File(root, "steamui/movies"))
            listing("config/movies", File(root, "config/movies"))
            line("-- every .webm below config, userdata, appcache and steamui:")
            var shown = 0
            for (top in listOf("config", "userdata", "appcache", "steamui")) {
                val base = File(root, top)
                if (!base.isDirectory) continue
                for (file in base.walkTopDown().maxDepth(8).onEnter { it.name !in SKIPPED }) {
                    if (!file.isFile || !file.name.endsWith(".webm", ignoreCase = true)) continue
                    if (shown++ >= 300) break
                    line("   " + describe(file))
                    Regex("^(\\d+)_[0-9a-fA-F]+").find(file.name)?.groupValues?.get(1)?.let { movieIds.add(it) }
                }
            }
        }

        line()
        line("== Where Steam keeps movie names (text found in Steam's files)")
        val needles = (KNOWN_TITLES + movieIds).distinct()
        line("looking for: ${needles.joinToString(", ")}")
        search(roots, needles) { line("   $it") }

        line()
        line("== What each session start did to Steam's config (latest last)")
        val trace = traceFile(context)
        if (trace.isFile) trace.readLines().forEach { line("   $it") } else line("   (nothing yet: start a session after picking a movie)")
        return out.toString()
    }

    private fun search(roots: List<File>, needles: List<String>, report: (String) -> Unit) {
        if (needles.isEmpty()) return
        val deadline = System.currentTimeMillis() + 25_000L
        var scanned = 0
        var hits = 0
        outer@ for (root in roots) {
            for (top in listOf("config", "userdata", "appcache", "steamui")) {
                val base = File(root, top)
                if (!base.isDirectory) continue
                for (file in base.walkTopDown().maxDepth(8).onEnter { it.name !in SKIPPED }) {
                    if (!file.isFile || file.length() > (2L shl 20) || file.extension.lowercase() in BINARY) continue
                    if (++scanned > 20_000 || System.currentTimeMillis() > deadline || hits >= MAX_HITS) break@outer
                    val text = runCatching { String(file.readBytes(), Charsets.ISO_8859_1) }.getOrNull() ?: continue
                    for (needle in needles) {
                        val at = text.indexOf(needle)
                        if (at < 0) continue
                        hits++
                        val around = text.substring((at - 60).coerceAtLeast(0), (at + needle.length + 100).coerceAtMost(text.length))
                        report("$needle in ${file.path}: " + around.map { if (it in ' '..'~') it else '.' }.joinToString(""))
                    }
                }
            }
        }
        report("(searched $scanned files, $hits matches)")
    }
}
