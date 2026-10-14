package com.droiddeck.launcher.core

import android.os.Process
import android.util.Log
import java.io.BufferedWriter
import java.io.File

object SessionLogCapture {
    private const val TAG = "SessionLogCapture"

    private var pid = -1
    private var writer: BufferedWriter? = null
    private var target: File? = null

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
            pid = HostProcess.start(
                "/system/bin/logcat -v threadtime -T 1 --pid=" + Process.myPid(),
                null, target.parentFile, null,
            ) { line ->
                synchronized(this) {
                    try {
                        writer?.apply { write(line); newLine(); flush() }
                    } catch (e: Exception) {
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
        }
        writer = null
    }

    fun dumpCrashBuffer(target: File) {
        try {
            val lines = StringBuilder()
            val done = java.util.concurrent.CountDownLatch(1)
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
