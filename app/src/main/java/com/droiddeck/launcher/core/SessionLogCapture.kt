package com.droiddeck.launcher.core

import android.os.Process
import android.util.Log
import java.io.BufferedWriter
import java.io.File

/**
 * `app.log`: what the app itself said while the session ran, and `crash.log`: what Android's crash
 * buffer holds if something died hard.
 *
 * Everything this app decides is reported through `Log` - which driver was chosen and why, the
 * audio line, a rival client being stopped, the session's exit status, a helper that was missing.
 * None of it reaches a user's folder, so a report used to arrive with the settings visible in
 * `device.txt` and no record of what actually happened. An app may always read back its own log
 * entries, so the simplest complete answer is to run `logcat` filtered to our own pid and keep
 * what it says.
 *
 * `logcat -b crash` earns its own file: proot once died before `main` over a missing library, and
 * that appeared in the crash buffer and *nowhere else* - not in the session log, not in the main
 * buffer. It is the first thing to read when a session "exits instantly".
 *
 * Both are best-effort. Where a ROM refuses to hand an app its own entries the files are written
 * with a line saying so, which is better than their absence looking like silence.
 */
object SessionLogCapture {
    private const val TAG = "SessionLogCapture"

    private var pid = -1
    private var writer: BufferedWriter? = null
    private var target: File? = null

    /** Start mirroring this process's log lines into [target]. Safe to call twice. */
    @Synchronized
    fun start(target: File) {
        stop()
        try {
            val out = target.bufferedWriter()
            out.write("The app's own log for this session (logcat, this process only).\n")
            out.write("Lines the app wrote before the session folder existed are in logcat only.\n\n")
            out.flush()
            writer = out
            this.target = target
            // -T 1 starts at the newest line rather than replaying the whole buffer; -v threadtime
            // keeps the timestamps and thread ids that make two logs line up.
            pid = HostProcess.start(
                "/system/bin/logcat -v threadtime -T 1 --pid=" + Process.myPid(),
                null, target.parentFile, null,
            ) { line ->
                synchronized(this) {
                    try {
                        if (writer === out) out.apply { write(line); newLine(); flush() }
                    } catch (e: Exception) {
                        // A full or unmounted card must not take the session with it.
                    }
                }
            }
            if (pid == -1) {
                out.write("logcat could not be started; this ROM may not hand an app its own entries.\n")
                out.flush()
            }
            Log.i(TAG, "app log -> $target (logcat pid $pid)")
        } catch (e: Exception) {
            Log.w(TAG, "could not start the app log", e)
        }
    }

    /** Stops the capture only if it is the one writing into [dir]: a newer session's is left alone. */
    @Synchronized
    fun stopFor(dir: File) {
        val t = target ?: return
        if (t.parentFile?.absolutePath == dir.absolutePath) stop()
    }

    @Synchronized
    fun stop() {
        target = null
        if (pid != -1) {
            try {
                Process.killProcess(pid)
            } catch (e: Exception) {
                Log.w(TAG, "logcat would not stop", e)
            }
            pid = -1
        }
        try {
            writer?.close()
        } catch (e: Exception) {
            // nothing useful to do with a failure to close a log
        }
        writer = null
    }

    /**
     * Dump Android's crash buffer into [target]. Called at teardown: a session that was killed is
     * exactly when this matters, and the buffer keeps its entries after the process is gone.
     */
    fun dumpCrashBuffer(target: File) {
        try {
            val lines = StringBuilder()
            val done = java.util.concurrent.CountDownLatch(1)
            // Wait on the process ENDING, not on the first line arriving: the lines come through a
            // callback on another thread, so a poll for "is it empty yet" declared an empty buffer
            // every time and still cost the wait. exec() returns a pid, never an exit status - the
            // first version printed that pid as "rc", which was nonsense in the file.
            val pid = HostProcess.start(
                "/system/bin/logcat -b crash -d -v threadtime -t 400", null, target.parentFile,
                { done.countDown() },
            ) { line -> synchronized(lines) { lines.append(line).append('\n') } }
            val finished = pid != -1 && done.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val body = synchronized(lines) { lines.toString() }
            target.writeText(
                "Android's crash buffer, as it stood when this session ended.\n" +
                    "Not only this app: anything on the device that crashed is in here, which is the\n" +
                    "point - a session killed by the system leaves its trace here and nowhere else.\n\n" +
                    when {
                        body.isNotEmpty() -> body
                        pid == -1 -> "(logcat could not be started at all)\n"
                        !finished -> "(logcat did not finish within 10 s; nothing captured)\n"
                        else -> "(the crash buffer is empty - nothing on the device has crashed recently)\n"
                    }
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not dump the crash buffer", e)
        }
    }
}
