package com.droiddeck.launcher.core

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.util.function.Consumer

object HostProcess {
    private const val TAG = "HostProcess"
    private const val LINKER = "/system/bin/linker64"
    // sh -c WORKER_CD sh DIR NAME=VALUE... -- PROGRAM ARGS...
    private const val WORKER_CD = "cd \"\$1\" || exit 126; shift; " +
        "while [ \$# -gt 0 ] && [ \"\$1\" != -- ]; do export \"\$1\"; shift; done; shift; exec \"\$@\""

    /** Android 10+ refuses to execute app data files for targetSdk 29+; its linker still loads them. */
    @JvmStatic
    fun dataExecutable(path: String): List<String> =
        if (android.os.Build.VERSION.SDK_INT >= 29) listOf(LINKER, path) else listOf(path)

    @JvmStatic
    fun start(command: String, env: Array<String>?, dir: File?, onExit: Consumer<Int>?, onLine: Consumer<String>?): Int =
        start(command, env, dir, onExit, onLine, null)

    @JvmStatic
    fun start(command: String, env: Array<String>?, dir: File?, onExit: Consumer<Int>?, onLine: Consumer<String>?, outputFile: File?): Int {
        return start(command, env, dir, onExit, onLine, outputFile, null)
    }

    @JvmStatic
    fun start(command: String, env: Array<String>?, dir: File?, onExit: Consumer<Int>?, onLine: Consumer<String>?, outputFile: File?, onOutputComplete: Runnable?): Int {
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
            if (onLine == null) {
                if (outputFile == null) builder.redirectOutput(File("/dev/null"))
                else builder.redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile))
            }
            val process = builder.start()
            val pid = pidOf(process)
            Log.i(TAG, "started pid $pid: ${argv.first()} (${argv.size - 1} arguments)")
            if (onLine != null) {
                Thread({
                    try {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { onLine.accept(it) } }
                    } catch (e: Exception) {
                    } finally {
                        onOutputComplete?.run()
                    }
                }, "proc-out-$pid").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true; start() }
            }
            if (onExit != null) {
                Thread({
                    var status = -1
                    try { status = process.waitFor() } catch (e: InterruptedException) {   }
                    onExit.accept(status)
                }, "proc-wait-$pid").apply { priority = Thread.NORM_PRIORITY - 1; start() }
            }
            pid
        } catch (e: Exception) {
            Log.e(TAG, "could not start: ${argv.first()}", e)
            -1
        }
    }

    /**
     * As [start], but the program runs as the main process of one of the app's worker processes,
     * which Android does not count against the app's child processes (docs/phantom-processes.md).
     * Falls back to [start] when no worker is available. The exit status is the program's exit
     * code, or 128 plus the signal that ended it, as Process.waitFor() reports it.
     */
    @JvmStatic
    @JvmOverloads
    fun startUncounted(command: String, env: Array<String>?, dir: File?, onExit: Consumer<Int>?, onLine: Consumer<String>?,
                       outputFile: File? = null, onOutputComplete: Runnable? = null): Int {
        val context = com.droiddeck.launcher.runtime.GuestWorkers.context
        val argv = split(command)
        if (context == null || argv.isEmpty() || !com.droiddeck.launcher.runtime.GuestWorkers.enabled(context)) {
            return start(command, env, dir, onExit, onLine, outputFile, onOutputComplete)
        }
        val environment = LinkedHashMap(System.getenv())
        env?.forEach { line ->
            val eq = line.indexOf('=')
            if (eq > 0) environment[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val assignments = environment.map { (k, v) -> "$k=$v" }
        // A worker cannot chdir before its exec, so Android's shell does it. The shell starts with an
        // environment of its own (the program's LD_PRELOAD of glibc libraries would stop it loading)
        // and exports the program's before the exec.
        val launch = if (dir != null) {
            listOf("/system/bin/sh", "-c", WORKER_CD, "sh", dir.path) + assignments + listOf("--") + argv
        } else argv
        val workerEnv = if (dir != null) listOf("PATH=/system/bin") else assignments
        val input = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY)
        var reader: ParcelFileDescriptor? = null
        val output = when {
            onLine != null -> ParcelFileDescriptor.createPipe().let { reader = it[0]; it[1] }
            outputFile != null -> ParcelFileDescriptor.open(outputFile,
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND)
            else -> ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_WRITE_ONLY)
        }
        val pid = com.droiddeck.launcher.runtime.GuestWorkers.start(context, launch, workerEnv,
            listOf(input, output, output)) { status ->
            onExit?.accept(if (status < 0) -1 else if (status and 0x7f == 0) (status shr 8) and 0xff else 128 + (status and 0x7f))
        }
        input.close()
        output.close()
        if (pid <= 0) {
            reader?.close()
            return start(command, env, dir, onExit, onLine, outputFile, onOutputComplete)
        }
        Log.i(TAG, "started pid $pid outside the child-process count: ${argv.first()} (${argv.size - 1} arguments)")
        reader?.let { pipe ->
            Thread({
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().useLines { lines ->
                        lines.forEach { onLine?.accept(it) }
                    }
                } catch (e: Exception) {
                } finally {
                    onOutputComplete?.run()
                }
            }, "proc-out-$pid").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true; start() }
        }
        return pid
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
