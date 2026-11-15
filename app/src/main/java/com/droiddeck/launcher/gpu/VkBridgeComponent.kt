package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.core.SessionPart
import java.io.File

class VkBridgeComponent(private val socket: File, private val logFile: File) : SessionPart() {
    @Volatile private var pid = -1
    @Volatile private var exited = false

    override fun start() {
        stop()
        val binary = File(app().applicationInfo.nativeLibraryDir, BINARY)
        if (!binary.isFile) {
            Log.e(TAG, "server missing at $binary; the session will have no Vulkan")
            return
        }
        socket.parentFile?.mkdirs()
        val cacheDir = cacheDir(app())
        writeMode(cacheDir, com.droiddeck.launcher.session.SessionPrefs.vkBridgeMode(app()))
        if (VkBridgeNative.start(app(), socket, logFile, cacheDir)) {
            Log.i(TAG, "server ready in the app's process at $socket")
            return
        }
        socket.delete()
        exited = false
        val cmd = listOf(binary.absolutePath, "--socket", socket.absolutePath, "--log", logFile.absolutePath,
            "--cache-dir", cacheDir.absolutePath)
            .joinToString(" ") { it.replace("\\", "\\\\").replace(" ", "\\ ") }
        val env = arrayOf("HOME=" + app().filesDir)
        pid = HostProcess.start(cmd, env, app().filesDir, { status ->
            exited = true
            Log.w(TAG, "server exited with status $status")
            runCatching { logFile.appendText("== vkbridge server exited with status $status\n") }
        }, { line -> Log.i(TAG, line) })
        val began = System.currentTimeMillis()
        val deadline = began + 15_000L
        while (!socket.exists() && !exited && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50L) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        val waited = System.currentTimeMillis() - began
        Log.i(TAG, if (socket.exists()) "server ready (pid $pid) at $socket after $waited ms"
            else "server NOT ready (pid $pid, exited=$exited) after $waited ms; see $logFile")
        if (!socket.exists() && VkBridgeNative.start(app(), socket, logFile, cacheDir)) Log.i(TAG, "server ready in the app's process at $socket")
    }

    override fun stop() {
        if (pid > 0) {
            Process.killProcess(pid)
            pid = -1
        }
        if (!VkBridgeNative.serving) socket.delete()
    }

    override fun suspendPid(): Int = pid

    companion object {
        private const val TAG = "VkBridge"
        private const val CACHE_DIR = "vkbridge-pipelines"
        private const val MODE_FILE = "mode"

        fun cacheDir(context: Context): File {
            val dir = File(context.filesDir, CACHE_DIR)
            val old = File(context.cacheDir, CACHE_DIR)
            if (old.isDirectory) migrate(old, dir)
            dir.mkdirs()
            return dir
        }

        fun writeMode(dir: File, mode: String) {
            val file = File(dir, MODE_FILE)
            if (runCatching { file.readText().trim() }.getOrNull() == mode) return
            val tmp = File(dir, "$MODE_FILE.tmp")
            runCatching {
                tmp.writeText(mode + "\n")
                if (!tmp.renameTo(file)) error("rename failed")
                Log.i(TAG, "bridge mode: $mode")
            }.onFailure { Log.w(TAG, "could not write $file: $it"); tmp.delete() }
        }

        private fun migrate(old: File, dir: File) {
            val moved = if (!dir.exists()) old.renameTo(dir) else false
            if (!moved) {
                dir.mkdirs()
                old.listFiles()?.forEach { f ->
                    val to = File(dir, f.name)
                    if (to.exists()) return@forEach
                    if (!f.renameTo(to)) runCatching { f.copyRecursively(to, overwrite = false) }
                }
                old.deleteRecursively()
            }
            Log.i(TAG, "bridge cache moved from $old to $dir")
        }

        const val BINARY = "libvkbridge_server.so"
        const val GUEST_ICD = "/usr/local/share/vkbridge/droidbridge_icd.json"
        const val GUEST_LIB = "/usr/local/lib/vkbridge/libvulkan_droidbridge.so"
    }
}
