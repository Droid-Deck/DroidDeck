package com.droiddeck.launcher.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips the most recent session's log folder and hands it to Android's share sheet. Works where
 * the folder could not be written to Downloads (no storage permission, or a device that refuses
 * it) and landed in the app's private files instead, which a user cannot otherwise reach.
 */
object SessionLogShare {
    /** The newest session folder in either place logs are written, or null if there is none. */
    fun latest(context: Context): File? = SessionPaths.sessionFolders(context).lastOrNull()

    /** Builds the zip (blocking). Returns null when there is no session to share. */
    fun zipLatest(context: Context): File? = latest(context)?.let { zipFolder(context, it) }

    /** Builds a zip for one specific session folder (blocking). */
    fun zipFolder(context: Context, folder: File): File? {
        if (!folder.isDirectory) return null
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) return null
        val out = File(context.cacheDir, "shared-logs").apply { deleteRecursively(); mkdirs() }
        val zip = File(out, "DroidDeck-${folder.name}.zip")
        // Scrubbed on the way into the zip: a session shared while it runs has not had its end-of-
        // session pass yet, and the redactor changes nothing in a line that is already clean.
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            files.forEach { f -> addEntry(z, folder.name + "/" + f.relativeTo(folder).path, f) }
            liveSteamLogs(context, folder).forEach { f -> addEntry(z, folder.name + "/steam/" + f.name, f) }
        }
        return zip
    }

    private fun addEntry(z: ZipOutputStream, name: String, f: File) {
        z.putNextEntry(ZipEntry(name))
        if (LogRedactor.isText(f)) {
            val w = z.bufferedWriter()
            LogRedactor.scrubTo(f, w)
            w.flush()
        } else {
            f.inputStream().use { it.copyTo(z) }
        }
        z.closeEntry()
    }

    /**
     * The client's own logs as they stand, for a session shared while it runs. The session script
     * copies them into steam/ only as it exits, so a zip made from the drawer had none - and
     * controller.txt (which pad the client opened, the touch mode it set) is what a controller or
     * touch report needs most. Only for the running session: an older folder would get this
     * session's logs. The same files the script copies; nothing holding credentials is in logs/.
     */
    private fun liveSteamLogs(context: Context, folder: File): List<File> {
        if (folder != SessionPaths.current() || File(folder, "steam").exists()) return emptyList()
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        return logs.listFiles()?.filter { it.isFile && it.length() <= STEAM_LOG_MAX_BYTES }.orEmpty()
    }

    /** The client's content and bootstrap logs grow large over months and say nothing about a session. */
    private const val STEAM_LOG_MAX_BYTES = 8L * 1024 * 1024

    fun shareIntent(context: Context, zip: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, zip.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        return Intent.createChooser(send, "Share session logs").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
