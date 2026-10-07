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
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionLogShare {
    private val main = Handler(Looper.getMainLooper())

    /**
     * How far the zip for a share has got, 0..1, or null when none is being made. The Share logs
     * buttons show it in place of their label, so a tap is answered at once.
     */
    var progress by mutableStateOf<Float?>(null)
        private set

    /**
     * Zips [folder] on a worker thread with [progress] following along, then hands the zip (null
     * if there was nothing to zip or it failed) to [done] on the main thread. Call on the main
     * thread; a tap while a zip is being made is ignored.
     */
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

    /** The newest session folder in either place logs are written, or null if there is none. */
    fun latest(context: Context): File? = SessionPaths.sessionFolders(context).lastOrNull()

    @Synchronized
    fun zipFolder(context: Context, folder: File, onProgress: ((Float) -> Unit)? = null): File? {
        if (!folder.isDirectory || java.nio.file.Files.isSymbolicLink(folder.toPath())) return null
        val files = SessionLogFiles.candidates(folder).map { Triple(folder, it, it.relativeTo(folder).invariantSeparatorsPath) }
        val liveRoot = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        val live = if (folder == SessionPaths.current()) SessionLogFiles.steamNames
            .filter { name -> files.none { it.third == "steam/$name" } }
            .map { File(liveRoot, it) }.filter { java.nio.file.Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
            .map { Triple(liveRoot, it, "steam/${it.name}") } else emptyList()
        val sources = files + live
        if (sources.isEmpty()) return null
        val out = File(context.cacheDir, "shared-logs").apply { SessionLogFiles.deleteTree(this); check(mkdirs()) }
        val name = folder.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val zip = File(out, "DroidDeck-$name.zip")
        val partial = File(out, ".export")
        val stage = File.createTempFile("redacted-", ".tmp", context.cacheDir)
        val omitted = ArrayList<String>()
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { z ->
                sources.forEachIndexed { index, (root, src, relative) ->
                    val limit = if (relative.startsWith("steam/")) SessionLogFiles.STEAM_MAX_BYTES else SessionLogFiles.MAX_BYTES
                    val failure = runCatching {
                        stage.bufferedWriter().use { SessionLogFiles.scrubTo(root, src, it, limit) }
                    }.exceptionOrNull()
                    if (failure == null) {
                        z.putNextEntry(ZipEntry("$name/$relative"))
                        stage.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    } else {
                        omitted += "$relative: omitted because it could not be safely sanitized."
                    }
                    onProgress?.invoke((index + 1).toFloat() / sources.size)
                }
                if (omitted.isNotEmpty()) {
                    z.putNextEntry(ZipEntry("$name/omitted.txt"))
                    z.write((omitted.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
            }
            check(partial.renameTo(zip))
            return zip
        } catch (e: Exception) {
            partial.delete()
            zip.delete()
            throw e
        } finally {
            stage.delete()
        }
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
