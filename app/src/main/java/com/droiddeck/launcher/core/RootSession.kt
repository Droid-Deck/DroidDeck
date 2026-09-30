package com.droiddeck.launcher.core

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.function.Consumer

/**
 * The rooted half of the session: a real uid 0 where the device has one, so the guest can be
 * entered with mount(2) and chroot(2) instead of proot's syscall rewriting.
 *
 * Nothing here is required. A device without a root manager answers [isAvailable] false and the
 * session runs exactly as before, through proot; a device with one gets the guest without a tracer
 * in the middle. Every call is made from the session thread, never the main thread: a root manager
 * may show its own prompt and a user may take seconds to answer it.
 */
object RootSession {
    private const val TAG = "RootSession"
    /** How long a root manager gets to answer before the session is started the unrooted way. */
    private const val PROBE_TIMEOUT_MS = 12_000L
    /** How long the runner gets to write the guest's pid before it is assumed not to have started. */
    private const val PID_TIMEOUT_MS = 8_000L

    /**
     * Whether a root manager can be reached, as last probed. Cached: the probe may prompt, so it is
     * asked once per install, and the answer is remembered in the session preferences.
     */
    @Volatile private var available: Boolean? = null

    /** The cached answer, or null before anything has asked. */
    fun cached(): Boolean? = available

    /**
     * Whether the session should be entered with chroot. False unless the device is rooted and the
     * user has left the switch on: a rooted device whose owner wants the old path keeps it.
     */
    fun useForSession(context: Context): Boolean = isAvailable(context) && SessionPrefs.rootSession(context)

    /**
     * Probes for a root manager, once, and remembers the answer. Called from the session thread
     * while the session starts, and from the settings screen when it opens; the second call is
     * free.
     */
    fun isAvailable(context: Context): Boolean {
        available?.let { return it }
        synchronized(this) {
            available?.let { return it }
            val rooted = probe()
            available = rooted
            SessionPrefs.setRootAvailable(context, rooted)
            Log.i(TAG, if (rooted) "root: available, the session can be entered with chroot" else "root: none, the session runs under proot")
            return rooted
        }
    }

    /** Forgets the cached answer, so the next question probes again. */
    fun forget() {
        synchronized(this) { available = null }
    }

    private fun probe(): Boolean {
        val output = StringBuilder()
        val status = run(arrayOf("id"), null, { output.append(it) }, PROBE_TIMEOUT_MS)
        val id = output.toString()
        return status == 0 && id.contains("uid=0")
    }

    /**
     * Runs {@code argv} as root through the device's root manager, and returns the exit status, or
     * null when the manager refused or nothing answered in time.
     *
     * @param onLine each line of the command's output, or null.
     */
    fun run(argv: Array<String>, environment: Map<String, String>?, onLine: Consumer<String>?, timeoutMs: Long): Int? {
        val command = shellCommand(argv)
        val builder = try {
            ProcessBuilder("su", "-c", command)
        } catch (t: Throwable) {
            Log.w(TAG, "root: no su to run through", t)
            return null
        }
        environment?.forEach { (key, value) -> builder.environment()[key] = value }
        builder.redirectErrorStream(true)
        val process = try {
            builder.start()
        } catch (t: Throwable) {
            Log.w(TAG, "root: could not start su", t)
            return null
        }
        try {
            if (onLine != null) {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { onLine.accept(it) } }
            } else {
                process.inputStream.close()
            }
            return if (process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.exitValue()
            } else {
                Log.w(TAG, "root: the command did not finish in ${timeoutMs}ms")
                process.destroy()
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "root: the command failed", t)
            return null
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * Runs {@code argv} as root without waiting for it to finish, and returns the pid of the
     * process that was started - the root manager's own, not the guest's. The guest writes its pid
     * to {@code pidFile} (the runner's --pidfile), which [waitForGuestPid] reads.
     */
    fun start(argv: Array<String>, environment: Map<String, String>?, onExit: Consumer<Int>?, onLine: Consumer<String>?): Int {
        // Two layers: the root shell splits the command it is handed, and HostProcess splits the
        // line it is given. Each is quoted for the one that reads it.
        val line = "su -c " + hostProcessQuote(shellCommand(argv))
        val pid = HostProcess.start(line, environment?.entries?.map { "${it.key}=${it.value}" }?.toTypedArray(), null, onExit, onLine)
        Log.i(TAG, "root: su pid $pid for the guest")
        return pid
    }

    /**
     * The guest's own pid, from the file the runner wrote before it chrooted. Read after [start],
     * because the process the app knows is the root manager's: it is the guest's pid that has to be
     * watched, signalled and torn down.
     */
    fun waitForGuestPid(pidFile: File, timeoutMs: Long = PID_TIMEOUT_MS): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pid = pidFile.takeIf { it.isFile }?.let { file ->
                runCatching { file.readText().trim().toIntOrNull() }.getOrNull()
            }
            if (pid != null && pid > 1) return pid
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
        }
        Log.w(TAG, "root: the guest never reported its pid")
        return -1
    }

    /** The runner's arguments as one line for a POSIX shell, each argument quoted for it. */
    private fun shellCommand(argv: Array<String>): String = argv.joinToString(" ") { argument ->
        "'" + argument.replace("'", "'\\''") + "'"
    }

    /** The same line for HostProcess.split(), which unescapes backslashes and spaces only. */
    private fun hostProcessQuote(command: String): String =
        command.replace("\\", "\\\\").replace(" ", "\\ ")
}
