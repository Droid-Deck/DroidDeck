package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File

object LogMigration {
    private const val TAG = "LogMigration"
    private const val MARKER = ".moved-from-downloads"

    @Synchronized
    fun run(context: Context) {
        val target = LinuxRuntime.logDir(context).apply { mkdirs() }
        val marker = File(target, MARKER)
        if (marker.exists()) return
        val legacy = LinuxRuntime.legacyLogDir()
        val items = if (legacy.isDirectory) legacy.listFiles() else emptyArray()
        if (items == null) { Log.w(TAG, "$legacy cannot be listed; logs there stay until it can"); return }
        var moved = 0
        val movedFolders = ArrayList<File>()
        for (f in items) {
            val isLog = SessionPaths.isSessionFolder(f) ||
                (f.isDirectory && f.name == SessionPaths.TOOLS_DIR) ||
                (f.isFile && f.name.endsWith(".log"))
            if (!isLog) continue
            val dest = File(if (f.isFile) File(target, SessionPaths.TOOLS_DIR).apply { mkdirs() } else target, f.name)
            if (dest.exists() && f.name != SessionPaths.TOOLS_DIR) continue
            if (move(f, dest)) {
                moved++
                if (SessionPaths.isSessionFolder(dest)) movedFolders.add(dest)
            } else Log.w(TAG, "could not move ${f.name}; left in place")
        }
        SessionArtifacts.unmarkMoved(movedFolders)
        try { marker.writeText("moved $moved item(s)\n") } catch (e: Exception) { Log.w(TAG, "could not mark the move: ${e.message}") }
        if (moved > 0) Log.i(TAG, "moved $moved log item(s) from $legacy into $target")
    }

    private fun move(src: File, dest: File): Boolean {
        if (src.renameTo(dest)) return true
        return try {
            copy(src, dest)
            FileUtils.delete(src)
            true
        } catch (e: Exception) {
            Log.w(TAG, "copy of ${src.name} failed: ${e.message}")
            false
        }
    }

    private fun copy(src: File, dest: File) {
        if (src.isDirectory) {
            dest.mkdirs()
            src.listFiles()?.forEach { copy(it, File(dest, it.name)) }
        } else {
            dest.parentFile?.mkdirs()
            src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }
}
