package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.core.SessionPart
import java.io.File

/**
 * The Vulkan bridge server (external/MaliBridge) for one session.
 *
 * On a GPU without Adreno's KGSL nothing inside the Linux runtime can reach the GPU: Turnip needs
 * KGSL, and the phone's own driver is a bionic library a glibc program cannot load. The server is
 * a bionic process the app starts here - executed from the native library directory like the
 * DirectAudio relay - which loads the system Vulkan loader and runs the Vulkan calls the
 * session's programs send it through the bridge ICD over a Unix socket. It is the app's child, so
 * it dies with the app (it also asks for that with PR_SET_PDEATHSIG).
 *
 * Started before the guest and waited for: the server runs a self-test first (which memory
 * sharing works on this GPU), and only then listens; a program that starts before the socket
 * exists would find no Vulkan driver.
 */
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
        // Pipeline cache kept across sessions (Mali recompiles everything in a new process otherwise).
        val cacheDir = cacheDir(app())
        // Setup's "Safe mode", for a new server and for the in-app one that is reused (it reads
        // the file again at each client): the JNI call stays as it is.
        writeMode(cacheDir, com.droiddeck.launcher.session.SessionPrefs.vkBridgeMode(app()))
        // A separate process by default: a crash in the GPU driver (seen once inside Mali's
        // vkBeginCommandBuffer) then ends the session, not the app. In-process gave the same driver
        // and the same 119 extensions on the Tab S10+; it remains the fallback.
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
        // The self-test takes about a second; a cold driver can take longer.
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
        // The in-app server outlives sessions: its socket stays.
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

        /**
         * The server's own directory (--cache-dir): pipeline caches, the startup probe's device
         * profile, stuck-pipelines and stuck-spirv/, and the env file of test switches. In the
         * app's files, which Android never trims: it empties the app's cacheDir when storage runs
         * low, and once took all of this from under a running server. Nothing trims it here; the
         * server bounds what it keeps (64 MB per pipeline cache file, and in newer servers a total
         * limit, VKBRIDGE_PCACHE_TOTAL_MB). A directory left in cacheDir by an older build is moved
         * over once.
         */
        fun cacheDir(context: Context): File {
            val dir = File(context.filesDir, CACHE_DIR)
            val old = File(context.cacheDir, CACHE_DIR)
            if (old.isDirectory) migrate(old, dir)
            dir.mkdirs()
            return dir
        }

        /**
         * <cache dir>/mode: "safe" or "performance", one word, written atomically (the server may
         * read it at any moment). VKBRIDGE_MODE in <cache dir>/env or the server's environment
         * wins over it (developer overrides).
         */
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

        /** Moves old's contents into dir (what dir already has wins), then removes old. */
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

        /** The server, named as a library so Android extracts it and lets the app run it. */
        const val BINARY = "libvkbridge_server.so"
        /** Inside the runtime (staged by SessionFiles). */
        const val GUEST_ICD = "/usr/local/share/vkbridge/droidbridge_icd.json"
        const val GUEST_LIB = "/usr/local/lib/vkbridge/libvulkan_droidbridge.so"
    }
}
