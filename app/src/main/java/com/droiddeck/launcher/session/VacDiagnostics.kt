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
 * VAC diagnostics: what a session can show about whether a game ran VAC-secure, from the files
 * Valve's own software writes - nothing is inferred from our side of the launch.
 *
 * The verdict is the game's to give. A Source-engine game decides secure or insecure once, as it
 * starts, and says so only in its own console ("You are in insecure mode. You must restart before
 * you can connect to secure servers."). That console reaches a file only when the game runs with
 * -condebug, as console.log in the game's mod folder. With this on, the launch wrapper
 * (droiddeck-proton, bl_vacdiag) adds -condebug to Source titles and notes each launch; at the end
 * of the session this copies each console.log written during it into vac/, scrubbed, and writes
 * vac.txt: the game's own secure/insecure lines, any VAC lines in the client's logs, and which
 * client files are present.
 *
 * The SteamLite lesson applies: its "INSECURE - VAC may kick" warning was our own parser reading the
 * wrong log while the client was secure all along. So vac.txt quotes the lines it found and says
 * "inconclusive" when the game never reached a server, rather than guessing either way.
 */
object VacDiagnostics {
    private const val TAG = "VacDiagnostics"

    /** In the guest's home; the launch wrapper checks for it. Holds the session's start, epoch seconds. */
    private const val MARKER = "root/.droiddeck-vac-diagnostics"

    /** One line per game launch the wrapper saw: epoch, app id, -condebug added or not, the exe. */
    private const val LAUNCHES = "root/.droiddeck-vac-launches"

    /** The console log grows across launches; the end of it is the session that just ran. */
    private const val CONSOLE_TAIL_BYTES = 4L * 1024 * 1024

    private const val MAX_QUOTED = 40

    private val INSECURE = Regex("insecure mode", RegexOption.IGNORE_CASE)
    // "Connecting to" is only an attempt: L4D2 logs it, retries, and then refuses a VAC server with a
    // dialog ("Connection Blocked: Please remove plug-ins before attempting to connect to a VAC
    // secured server") that never reaches the console. Only "Connected to" means the game got in.
    private val CONNECTED = Regex("^Connected to", RegexOption.IGNORE_CASE)
    private val ATTEMPTED = Regex("^Connecting to", RegexOption.IGNORE_CASE)
    private val NOTABLE = Regex("\\bVAC\\b|secure|Connected to|Connecting to|Disconnect|challenge|game coordinator", RegexOption.IGNORE_CASE)
    private val STEAM_VAC = Regex("\\bVAC\\b|anti-?cheat", RegexOption.IGNORE_CASE)

    fun enabled(context: Context): Boolean = SessionPrefs.vacDiagnostics(context)

    /**
     * At session start, after the scripts are staged: arms the wrapper for this session (or disarms
     * it) and starts a fresh launch list, so the summary names only games this session ran.
     */
    fun arm(context: Context) {
        val root = LinuxRuntime.rootDir(context)
        val marker = File(root, MARKER)
        try {
            File(root, LAUNCHES).delete()
            if (enabled(context)) marker.writeText("${System.currentTimeMillis() / 1000}\n")
            else marker.delete()
        } catch (e: Exception) {
            Log.w(TAG, "could not arm VAC diagnostics", e)
        }
    }

    /** At the end of a session, into its folder. Does nothing unless the session was armed. */
    fun collect(context: Context, dir: File) {
        val root = LinuxRuntime.rootDir(context)
        val marker = File(root, MARKER)
        if (!marker.isFile) return
        try {
            val since = (marker.readText().trim().toLongOrNull() ?: 0L) * 1000 - 5_000
            val out = File(dir, "vac").apply { mkdirs() }
            val consoles = consoleLogs(context).filter { it.lastModified() >= since }
            val report = StringBuilder()
            report.appendLine("VAC diagnostics - ${now()}")
            report.appendLine("Read from the game's own console (-condebug) and the Steam client's logs.")
            report.appendLine()
            report.appendLine("== Client")
            clientFiles(root).forEach { report.appendLine("  $it") }
            report.appendLine()
            report.appendLine("== Game launches this session (from the launch wrapper)")
            val launches = File(root, LAUNCHES).takeIf { it.isFile }?.readLines().orEmpty().filter { it.isNotBlank() }
            if (launches.isEmpty()) report.appendLine("  none - no Windows game was launched through the client")
            launches.forEach { report.appendLine("  " + LogRedactor.redact(it)) }
            report.appendLine()
            report.appendLine("== Game consoles")
            if (consoles.isEmpty()) {
                report.appendLine("  No console.log was written this session. Only Source-engine games write one,")
                report.appendLine("  and only when started with -condebug (this setting adds it).")
            }
            consoles.forEach { log ->
                val name = consoleName(log)
                val lines = tail(log)
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

    /** Every console.log in a Steam library: <game>/<mod>/console.log (Source) and <game>/game/<mod>/console.log (Source 2). */
    private fun consoleLogs(context: Context): List<File> {
        val found = LinkedHashMap<String, File>()
        for (library in libraryRoots(context)) {
            val games = File(library, "steamapps/common").listFiles { f -> f.isDirectory } ?: continue
            for (game in games) {
                val mods = (game.listFiles { f -> f.isDirectory }.orEmpty().toList() +
                    File(game, "game").listFiles { f -> f.isDirectory }.orEmpty().toList())
                for (mod in mods) {
                    val log = File(mod, "console.log")
                    if (log.isFile) found[runCatching { log.canonicalPath }.getOrDefault(log.path)] = log
                }
            }
        }
        return found.values.toList()
    }

    private fun libraryRoots(context: Context): List<File> {
        val root = LinuxRuntime.rootDir(context)
        val steam = SessionPrefs.activeSteamRoot(context)
        val roots = mutableListOf(steam)
        GameStorage.effective(context)?.let { roots.add(File(it.path)) }
        runCatching {
            Regex("\"path\"\\s+\"([^\"]+)\"").findAll(File(steam, "steamapps/libraryfolders.vdf").readText()).forEach {
                roots.add(File(root, it.groupValues[1].removePrefix("/")))
            }
        }
        return roots.filter { it.isDirectory }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }
    }

    /** "Team Fortress 2-tf": the game folder and the mod, safe as a file name. */
    private fun consoleName(log: File): String {
        val mod = log.parentFile
        val game = mod?.parentFile?.let { if (it.name == "game") it.parentFile else it }
        return "${game?.name ?: "game"}-${mod?.name ?: "mod"}".replace(Regex("[^A-Za-z0-9._ +-]+"), "_")
    }

    private fun tail(file: File): List<String> = try {
        RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - CONSOLE_TAIL_BYTES).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8).lines().let { if (start > 0) it.drop(1) else it }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Which Steam client builds are on disk - the arm64 one has no VAC module of its own to run. */
    private fun clientFiles(root: File): List<String> {
        val steam = File(root, "root/.local/share/Steam")
        return listOf(
            "linuxarm64/steamclient.so" to "arm64 Linux client",
            "linux64/steamclient.so" to "x86-64 Linux client",
            "ubuntu12_64/steamclient.so" to "x86-64 Linux client (ubuntu12_64)",
        ).map { (path, label) -> "$label: ${if (File(steam, path).isFile) "present" else "absent"} ($path)" }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
