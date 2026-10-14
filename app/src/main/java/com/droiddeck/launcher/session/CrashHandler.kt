package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashHandler {
    private const val TAG = "CrashHandler"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Log.e(TAG, "uncaught on ${thread.name}", error)
                if (QuestExperimental.isSupported()) runCatching {
                    File(context.filesDir, "quest-last-crash.txt").writeText(
                        "${java.time.Instant.now()} thread=${thread.name}\n" +
                            StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                    )
                }
                val dir = SessionPaths.current()
                if (dir != null && dir.isDirectory) {
                    val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                    File(dir, "crash-app.txt").writeText(
                        "The app itself crashed on thread \"${thread.name}\":\n\n$trace"
                    )
                    val worker = Thread({ SessionArtifacts.collect(context, dir, "app crash") }, "crash-collect")
                    worker.start()
                    worker.join(8000)
                }
                OrphanReaper.reap("app crash")
            } catch (e: Throwable) {
            } finally {
                previous?.uncaughtException(thread, error) ?: Runtime.getRuntime().halt(2)
            }
        }
    }
}
