package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The stores' log on disk, app-private: `filesDir/logs/stores/stores-<yyyy-MM-dd>.log` (beside the
 * session folders, where every log lives), one file a
 * day, the last [KEEP_DAYS] kept, each capped near [MAX_BYTES] (the full one moves to `.1`). Lines
 * arrive already redacted ([StoreLog.redactLine]). Written on one background thread, in order. The
 * session's Share logs zip carries these files, so they can be attached to a report when asked.
 */
object StoreLogFiles {
    private const val TAG = "StoreLogFiles"
    private const val KEEP_DAYS = 7
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val NAME = Regex("""stores-(\d{4}-\d{2}-\d{2})\.log(\.1)?""")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "store-log").apply { isDaemon = true } }

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "logs/stores")

    private val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val flushQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "store-log-flush").apply { isDaemon = true }
    }

    /**
     * Queues one stamped line for today's file. Any thread; nothing touches the disk here. Lines are
     * written in batches, at most every half second, on [writer] in order.
     */
    fun append(context: Context, line: String) {
        val app = context.applicationContext
        pending.add(line)
        if (flushQueued.compareAndSet(false, true)) {
            flusher.schedule({ flushQueued.set(false); writer.execute { flush(app) } }, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun flush(app: Context) {
        if (pending.isEmpty()) return
        val batch = StringBuilder()
        while (true) { val l = pending.poll() ?: break; batch.append(l).append('\n') }
        try {
            val dir = dir(app).apply { mkdirs() }
            val file = File(dir, "stores-${synchronized(DAY) { DAY.format(Date()) }}.log")
            if (file.length() > MAX_BYTES) {
                val old = File(dir, file.name + ".1")
                old.delete()
                file.renameTo(old)
            }
            file.appendText(batch.toString())
        } catch (e: Exception) {
            Log.w(TAG, "could not write the stores log: ${e.message}")
        }
    }

    /** Drops the files older than [KEEP_DAYS] days. Called as the app starts. */
    fun prune(context: Context) {
        val app = context.applicationContext
        writer.execute {
            val cutoff = synchronized(DAY) { DAY.format(Date(System.currentTimeMillis() - (KEEP_DAYS - 1) * 86_400_000L)) }
            dir(app).listFiles()?.forEach { f ->
                val day = NAME.matchEntire(f.name)?.groupValues?.get(1) ?: return@forEach
                if (day < cutoff) f.delete()
            }
        }
    }

    /** The files there are, oldest first, for a log share. */
    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && NAME.matches(f.name) }?.sortedBy { it.name }.orEmpty()
}
