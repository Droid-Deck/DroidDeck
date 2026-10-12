package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The last thing that runs when our own code throws on any thread: the stack trace into the
 * session's folder, then the folder's ending ([SessionArtifacts.collect]), then Android's own
 * handler so the crash is still reported and still lands in the crash buffer. Bounded, because
 * a handler that hangs turns a crash into a frozen app: the collect gets a few seconds and the
 * process dies regardless.
 *
 * A native crash (the compositor, a JNI call) never reaches here - the process is gone before
 * Java sees anything. [SessionArtifacts.finishAbandoned] at the next start covers that case.
 */
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
                // The session's tree would outlive us otherwise, and block the next launch.
                OrphanReaper.reap("app crash")
            } catch (e: Throwable) {
                // Nothing more to do for a crash inside the crash handler.
            } finally {
                previous?.uncaughtException(thread, error) ?: Runtime.getRuntime().halt(2)
            }
        }
    }
}
