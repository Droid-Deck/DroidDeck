package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Source games state VAC-secure or insecure only in their console, which reaches a file (console.log in the
 * mod folder) only with -condebug. The report quotes the lines it found and says inconclusive rather than guessing.
 */
object VacDiagnostics {
    private const val TAG = "VacDiagnostics"

    /**
     * Checked by the launch wrapper. Lines: session start (epoch seconds), the Steam client it ran, then
     * "length<tab>canonical path" for each console.log already there.
     */
    private const val MARKER = "root/.droiddeck-vac-diagnostics"

    private const val LAUNCHES = "root/.droiddeck-vac-launches"

    private const val CONSOLE_TAIL_BYTES = 4L * 1024 * 1024

    private const val MAX_QUOTED = 40

    private val INSECURE = Regex("insecure mode", RegexOption.IGNORE_CASE)
    // "Connecting to" is only an attempt: L4D2 refuses a VAC server with a dialog that never reaches the console.
    private val CONNECTED = Regex("^Connected to", RegexOption.IGNORE_CASE)
    private val ATTEMPTED = Regex("^Connecting to", RegexOption.IGNORE_CASE)
    private val NOTABLE = Regex("\\bVAC\\b|secure|Connected to|Connecting to|Disconnect|challenge|game coordinator", RegexOption.IGNORE_CASE)
    private val STEAM_VAC = Regex("\\bVAC\\b|anti-?cheat", RegexOption.IGNORE_CASE)

    fun enabled(context: Context): Boolean = SessionPrefs.vacDiagnostics(context)

    fun arm(context: Context) {
        val root = LinuxRuntime.rootDir(context)
        val marker = File(root, MARKER)
        try {
            File(root, LAUNCHES).delete()
            if (enabled(context)) {
                val arch = SessionPrefs.steamClientArch(context)
                // -condebug appends, so each console.log's length now is where this session's lines start.
                val sizes = consoleLogs(context, arch).joinToString("") { "${it.length()}\t${canonical(it)}\n" }
                marker.writeText("${System.currentTimeMillis() / 1000}\n$arch\n$sizes")
            } else marker.delete()
        } catch (e: Exception) {
            Log.w(TAG, "could not arm VAC diagnostics", e)
        }
    }

    private const val SNAPSHOT_MARKER = ".vac-marker"
    private const val SNAPSHOT_LAUNCHES = ".vac-launches"

    /** Call when a session stops, before the next [arm] replaces its marker and launch list. */
    fun snapshot(context: Context, dir: File) {
        val root = LinuxRuntime.rootDir(context)
        try {
            File(root, MARKER).takeIf { it.isFile }?.copyTo(File(dir, SNAPSHOT_MARKER), overwrite = true) ?: return
            File(root, LAUNCHES).takeIf { it.isFile }?.copyTo(File(dir, SNAPSHOT_LAUNCHES), overwrite = true)
        } catch (e: Exception) {
            Log.w(TAG, "could not keep this session's VAC diagnostics state", e)
        }
    }

    fun collect(context: Context, dir: File) {
        val root = LinuxRuntime.rootDir(context)
        val kept = File(dir, SNAPSHOT_MARKER).takeIf { it.isFile }
        val marker = kept ?: File(root, MARKER)
        val launchList = if (kept != null) File(dir, SNAPSHOT_LAUNCHES) else File(root, LAUNCHES)
        if (!marker.isFile) return
        try {
            val armed = marker.readLines().map { it.trim() }
            val since = (armed.getOrNull(0)?.toLongOrNull() ?: 0L) * 1000 - 5_000
            val arch = armed.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: SessionPrefs.steamClientArch(context)
            val armedSizes = armed.drop(2).mapNotNull { line ->
                val (size, path) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                size.toLongOrNull()?.let { path to it }
            }.toMap()
            val out = File(dir, "vac").apply { mkdirs() }
            val consoles = consoleLogs(context, arch).filter { writtenThisSession(it, since, armedSizes) }
            val report = StringBuilder()
            report.appendLine("VAC diagnostics - ${now()}")
            report.appendLine("Read from the game's own console (-condebug) and the Steam client's logs.")
            report.appendLine()
            report.appendLine("== Client")
            report.appendLine("  Client this session: " + if (arch == "x86_64") "x86-64 (Valve's x86-64 client under FEX)" else "arm64")
            report.appendLine("  Files in its folder (/${SessionPrefs.steamRoot(context, arch).relativeTo(root).path}):")
            clientFiles(context, arch).forEach { report.appendLine("    $it") }
            report.appendLine()
            report.appendLine("== Game launches this session (from the launch wrapper)")
            val launches = launchList.takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }
            if (launches.isEmpty()) report.appendLine("  none - no Windows game was launched through the client")
            launches.forEach { report.appendLine("  " + LogRedactor.redact(it)) }
            report.appendLine()
            report.appendLine("== Game consoles")
            if (consoles.isEmpty()) {
                report.appendLine("  No console.log was written this session. Only Source-engine games write one,")
                report.appendLine("  and only when started with -condebug (this setting adds it).")
            }
            val names = consoleNames(consoles)
            consoles.forEachIndexed { i, log ->
                val name = names[i]
                val lines = tail(log, armedSizes[canonical(log)] ?: 0L)
                File(out, "$name-console.log").bufferedWriter().use { w ->
                    lines.forEach { w.write(LogRedactor.redact(it)); w.newLine() }
                }
                report.appendLine("-- $name (vac/$name-console.log)")
                report.appendLine("   Verdict: " + verdict(lines))
                lines.filter { NOTABLE.containsMatchIn(it) }.takeLast(MAX_QUOTED)
                    .forEach { report.appendLine("   | " + LogRedactor.redact(it.trim())) }
                report.appendLine()
            }
            report.appendLine("== VAC in the Steam client's logs")
            val steamHits = File(dir, "steam").listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()
                .flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()).filter { STEAM_VAC.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" } }
            if (steamHits.isEmpty()) report.appendLine("  none - the client logged no VAC activity this session")
            steamHits.takeLast(MAX_QUOTED).forEach { report.appendLine("  $it") }
            File(dir, "vac.txt").writeText(report.toString())
            File(dir, SNAPSHOT_MARKER).delete()
            File(dir, SNAPSHOT_LAUNCHES).delete()
            Log.i(TAG, "VAC diagnostics: ${consoles.size} console log(s), ${steamHits.size} client VAC line(s), into $dir")
        } catch (e: Exception) {
            Log.w(TAG, "collecting VAC diagnostics", e)
        }
    }

    private fun verdict(lines: List<String>): String = when {
        lines.any { INSECURE.containsMatchIn(it) } ->
            "INSECURE - the game started in insecure mode and cannot join VAC-secured servers until it restarts."
        lines.any { CONNECTED.containsMatchIn(it) } ->
            "No insecure notice, and the game connected to a server. Confirm with the server's own info that it is VAC-secured."
        lines.any { ATTEMPTED.containsMatchIn(it) } ->
            "NOT CONNECTED - the game tried a server and never got in. If the screen said \"Connection Blocked: " +
                "Please remove plug-ins ... VAC secured server\" (Left 4 Dead 2's wording), the game is insecure; " +
                "that dialog never reaches the console."
        else ->
            "Inconclusive - the game never connected to a server. Join a VAC-secured server, then check again."
    }

    /** Source writes <game>/<mod>/console.log, Source 2 <game>/game/<mod>/console.log. */
    private fun consoleLogs(context: Context, arch: String): List<File> {
        val found = LinkedHashMap<String, File>()
        for (library in libraryRoots(context, arch)) {
            val games = File(library, "steamapps/common").listFiles { f -> f.isDirectory } ?: continue
            for (game in games) {
                val mods = (game.listFiles { f -> f.isDirectory }.orEmpty().toList() +
                    File(game, "game").listFiles { f -> f.isDirectory }.orEmpty().toList())
                for (mod in mods) {
                    val log = File(mod, "console.log")
                    if (log.isFile) found[canonical(log)] = log
                }
            }
        }
        return found.values.toList()
    }

    private fun libraryRoots(context: Context, arch: String): List<File> {
        val root = LinuxRuntime.rootDir(context)
        val steam = SessionPrefs.steamRoot(context, arch)
        val roots = mutableListOf(steam)
        (if (arch == "x86_64") GameStorage.x64CardLibrary(context) else GameStorage.effective(context)?.let { File(it.path) })
            ?.let { roots.add(it) }
        runCatching {
            Regex("\"path\"\\s+\"([^\"]+)\"").findAll(File(steam, "steamapps/libraryfolders.vdf").readText()).forEach {
                roots.add(File(root, it.groupValues[1].removePrefix("/")))
            }
        }
        return roots.filter { it.isDirectory }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
    }

    /** Two libraries can hold the same game and mod folder; a repeated name takes a tag from the log's path. */
    internal fun consoleNames(logs: List<File>): List<String> {
        val used = HashSet<String>()
        return logs.map { log ->
            val base = consoleName(log)
            if (used.add(base)) base else "$base-%08x".format(canonical(log).hashCode()).also { used.add(it) }
        }
    }

    private fun consoleName(log: File): String {
        val mod = log.parentFile
        val game = mod?.parentFile?.let { if (it.name == "game") it.parentFile else it }
        return "${game?.name ?: "game"}-${mod?.name ?: "mod"}".replace(Regex("[^A-Za-z0-9._ +-]+"), "_")
    }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.path)

    internal fun writtenThisSession(log: File, since: Long, armedSizes: Map<String, Long>): Boolean =
        log.lastModified() >= since && log.length() != armedSizes[canonical(log)]

    internal fun tail(file: File, from: Long = 0L): List<String> = try {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val since = from.takeIf { it <= length } ?: 0L
            val start = maxOf(since, length - CONSOLE_TAIL_BYTES)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8).lines().let { if (start > since) it.drop(1) else it }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read ${file.name}", e)
        emptyList()
    }

    private fun clientFiles(context: Context, arch: String): List<String> {
        val steam = SessionPrefs.steamRoot(context, arch)
        return listOf(
            "linuxarm64/steamclient.so" to "arm64 Linux client",
            "linux64/steamclient.so" to "x86-64 Linux client",
            "ubuntu12_64/steamclient.so" to "x86-64 Linux client (ubuntu12_64)",
            "steamrt64/steamclient.so" to "x86-64 Linux client (steamrt64)",
        ).map { (path, label) -> "$label: ${if (File(steam, path).isFile) "present" else "absent"} ($path)" }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
