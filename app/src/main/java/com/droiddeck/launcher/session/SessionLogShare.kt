package com.droiddeck.launcher.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.BattleNet
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionLogShare {
    private val main = Handler(Looper.getMainLooper())

    var progress by mutableStateOf<Float?>(null)
        private set

    fun prepare(context: Context, folder: () -> File?, done: (File?) -> Unit) {
        if (progress != null) return
        progress = 0f
        Thread({
            val zip = runCatching { folder()?.let { dir -> zipFolder(context, dir) { p -> main.post { if (progress != null) progress = p } } } }
                .onFailure { Log.w("SessionLogShare", "could not package session logs", it) }
                .getOrNull()
            main.post { progress = null; done(zip) }
        }, "share-logs").start()
    }

    fun latest(context: Context): File? = SessionPaths.sessionFolders(context).lastOrNull()


    fun zipFolder(context: Context, folder: File, onProgress: ((Float) -> Unit)? = null): File? {
        if (!folder.isDirectory) return null
        val files = folder.walkTopDown().filter { it.isFile }
            .filterNot { it.parentFile?.name == "steam" && it.length() > STEAM_LOG_MAX_BYTES }.toList()
        if (files.isEmpty()) return null
        val out = shareDir(context).apply { deleteRecursively(); mkdirs() }
        val zip = File(out, "DroidDeck-${folder.name}.zip")
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        val live = liveSteamLogs(context, folder)
        val logs = LinuxRuntime.logDir(context)
        val stores = recent(File(logs, STORES_DIR))
        val tools = recent(File(logs, SessionPaths.TOOLS_DIR))
        val battleNet = battleNetLogs(context)
        val total = (files + live + stores + tools + battleNet.map { it.second }).sumOf { it.length() }.coerceAtLeast(1L)
        var done = 0L
        var shown = -1
        fun advance(f: File) {
            done += f.length()
            val percent = (done * 100 / total).toInt()
            if (percent != shown) { shown = percent; onProgress?.invoke(percent / 100f) }
        }
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            files.forEach { f -> addEntry(z, folder.name + "/" + f.relativeTo(folder).invariantSeparatorsPath, f); advance(f) }
            live.forEach { f -> addEntry(z, folder.name + "/steam/" + f.name, f); advance(f) }
            stores.forEach { f -> addEntry(z, "$STORES_DIR/" + f.name, f); advance(f) }
            tools.forEach { f -> addEntry(z, SessionPaths.TOOLS_DIR + "/" + f.name, f); advance(f) }
            battleNet.forEach { (name, f) -> addEntry(z, "$BATTLENET_DIR/$name", f); advance(f) }
        }
        return zip
    }

    /** Every entry is scrubbed line by line, whatever its size: a raw copy can carry tokens out of the device. */
    private fun addEntry(z: ZipOutputStream, name: String, f: File) {
        z.putNextEntry(ZipEntry(name))
        val w = z.bufferedWriter()
        LogRedactor.scrubForShare(f, w)
        w.flush()
        z.closeEntry()
    }

    private fun liveSteamLogs(context: Context, folder: File): List<File> {
        if (folder != SessionPaths.current() || File(folder, "steam").exists()) return emptyList()
        val logs = File(SessionPrefs.activeSteamRoot(context), "logs")
        return logs.listFiles()?.filter { it.isFile && it.length() <= STEAM_LOG_MAX_BYTES }.orEmpty()
    }

    private const val STEAM_LOG_MAX_BYTES = 8L * 1024 * 1024

    const val STORES_DIR = "stores"
    private const val RECENT_DAYS = 7

    private fun shareDir(context: Context): File = File(context.cacheDir, "share")

    private const val BATTLENET_DIR = "battlenet"

    private fun battleNetLogs(context: Context): List<Pair<String, File>> {
        val drive = File(BattleNet.prefix(context), "pfx/drive_c")
        val data = File(drive, "ProgramData/Battle.net")
        fun logsUnder(dir: File, prefix: String) = dir.listFiles { f -> f.isDirectory && f.name.startsWith(prefix) }.orEmpty().map { File(it, "Logs") }
        return newest("droiddeck", listOf(File(LinuxRuntime.rootDir(context), "root/.cache/droiddeck/battlenet")), 2, setOf("battlenet.log", "spawned.log")) +
            newest("client", listOf(File(drive, "users/steamuser/AppData/Local/Battle.net/Logs")), 6) +
            newest("agent", listOf(File(data, "Agent/Logs")) + logsUnder(File(data, "Agent"), "Agent."), 10, byDir = true) +
            newest("setup", logsUnder(File(data, "Setup"), ""), 3, byDir = true)
    }

    private fun newest(folder: String, dirs: List<File>, count: Int, names: Set<String>? = null, byDir: Boolean = false): List<Pair<String, File>> {
        val since = System.currentTimeMillis() - RECENT_DAYS * 86_400_000L
        return dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") && (names == null || f.name in names) && f.lastModified() >= since && f.length() <= STEAM_LOG_MAX_BYTES }.orEmpty().toList()
        }.sortedByDescending { it.lastModified() }.take(count).map { f ->
            (if (byDir) "$folder/${f.parentFile?.parentFile?.name}/${f.name}" else "$folder/${f.name}") to f
        }
    }

    private fun recent(dir: File): List<File> {
        val since = System.currentTimeMillis() - RECENT_DAYS * 86_400_000L
        return dir.listFiles { f -> f.isFile && f.lastModified() >= since }?.sortedBy { it.name }.orEmpty()
    }

    fun clear(context: Context) {
        shareDir(context).deleteRecursively()
        File(context.cacheDir, "shared-logs").deleteRecursively()
    }

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        return Intent.createChooser(send, context.getString(R.string.logshare_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
