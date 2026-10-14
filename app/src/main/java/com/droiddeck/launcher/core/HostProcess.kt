package com.droiddeck.launcher.core

import android.util.Log
import java.io.File
import java.util.function.Consumer

object HostProcess {
    private const val TAG = "HostProcess"

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
