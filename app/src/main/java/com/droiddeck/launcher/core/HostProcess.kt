package com.droiddeck.launcher.core

import android.util.Log
import java.io.File
import java.util.function.Consumer

/**
 * Starts a process on the Android side - proot, the audio daemon, logcat - and hands back its pid.
 *
 * The pid matters: stopping a session means killing that exact process (proot runs with
 * --kill-on-exit, so the guest tree goes with it), and java.lang.Process does not expose it before
 * API 33, so it is read from the implementation's private field. Output can be drained line by
 * line to a callback; without one it goes to /dev/null, never to a pipe nobody reads, which would
 * block the child the moment the pipe filled.
 */
object HostProcess {
    private const val TAG = "HostProcess"

    /**
     * @param command the program and its arguments as one line; a backslash escapes a space, since
     *                paths under /data are full of them.
     * @param env `NAME=VALUE` lines added to the app's own environment (proot needs nothing from
     *            the inherited set, and the guest starts with `env -i` anyway), or null.
     * @param onExit told the exit status from a waiting thread, or null.
     * @param onLine told each line of stdout and stderr, or null to discard both.
     * @return the pid, or -1 when the process could not be started.
     */
    @JvmStatic
    fun start(command: String, env: Array<String>?, dir: File?, onExit: Consumer<Int>?, onLine: Consumer<String>?): Int {
        val argv = split(command)
        if (argv.isEmpty()) return -1
        return try {
            val builder = ProcessBuilder(argv)
            if (dir != null) builder.directory(dir)
            env?.forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) builder.environment()[line.substring(0, eq)] = line.substring(eq + 1)
            }
            builder.redirectErrorStream(true)
            if (onLine == null) builder.redirectOutput(File("/dev/null"))
            val process = builder.start()
            val pid = pidOf(process)
            Log.i(TAG, "started pid $pid: ${argv.first()} (${argv.size - 1} arguments)")
            if (onLine != null) {
                Thread({
                    try {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { onLine.accept(it) } }
                    } catch (e: Exception) {
                        // The process ended or the pipe closed; nothing more to read.
                    }
                }, "proc-out-$pid").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true; start() }
            }
            if (onExit != null) {
                Thread({
                    var status = -1
                    try { status = process.waitFor() } catch (e: InterruptedException) { /* reported as -1 */ }
                    onExit.accept(status)
                }, "proc-wait-$pid").apply { priority = Thread.NORM_PRIORITY - 1; start() }
            }
            pid
        } catch (e: Exception) {
            Log.e(TAG, "could not start: ${argv.first()}", e)
            -1
        }
    }

    @JvmStatic
    fun pidOf(process: Process): Int = try {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        val pid = field.getInt(process)
        field.isAccessible = false
        pid
    } catch (e: Exception) {
        -1
    }

    /** Splits a command line on spaces; a backslash keeps the space that follows it. */
    @JvmStatic
    fun split(command: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in command) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == ' ' -> if (current.isNotEmpty()) { parts.add(current.toString()); current.setLength(0) }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) parts.add(current.toString())
        return parts
    }
}
