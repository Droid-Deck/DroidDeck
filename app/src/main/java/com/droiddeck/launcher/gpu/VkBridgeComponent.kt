package com.droiddeck.launcher.gpu

import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.core.SessionPart
import java.io.File

/**
 * The Vulkan bridge server (tools/vkbridge, app/src/main/cpp/vkbridge) for one session.
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
        val cacheDir = File(app().cacheDir, "vkbridge-pipelines").apply { mkdirs() }
        if (VkBridgeNative.start(socket, logFile, cacheDir)) {
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
        val deadline = System.currentTimeMillis() + 15_000L
        while (!socket.exists() && !exited && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50L) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        Log.i(TAG, if (socket.exists()) "server ready (pid $pid) at $socket" else "server NOT ready (pid $pid, exited=$exited); see $logFile")
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
        /** The server, named as a library so Android extracts it and lets the app run it. */
        const val BINARY = "libvkbridge_server.so"
        /** Inside the runtime (staged by SessionFiles). */
        const val GUEST_ICD = "/usr/local/share/vkbridge/droidbridge_icd.json"
        const val GUEST_LIB = "/usr/local/lib/vkbridge/libvulkan_droidbridge.so"
    }
}
