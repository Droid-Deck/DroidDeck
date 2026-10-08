package com.droiddeck.launcher.stores.download

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoresState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One queue for every store's downloads, [parallel] of them running at a time (1..3, Setup and
 * the Downloads page), the rest waiting as data until a slot frees. A store hands in a
 * [DownloadJob] - its whole install: manifest, files, verify, post-install - and reports back
 * through the job's [JobHandle]; the queue owns the states, the registry row the UI draws and the
 * foreground service that keeps the process alive while anything runs.
 *
 * Pause for these engines is "stop now, keep the files": every store's install skips files that
 * are already complete and verified, so resuming is simply running the job again. Cancel removes
 * what was downloaded (the job's own cleanup) and the row.
 *
 * Thread-safe: one [lock] guards the table; jobs run on their own threads, outside it.
 */
object DownloadQueue {
    private const val TAG = "StoreDownloads"

    /** The work for one download. [run] blocks until it is over; [cancel] asks a running job to stop. */
    interface DownloadJob {
        /** Runs the install; returns the install folder's path on success, throws or returns null on failure. */
        fun run(handle: JobHandle): String?
        /** Stops a running job; [deleteFiles] false keeps what was fetched for a later resume. */
        fun cancel(deleteFiles: Boolean)
    }

    /** What a running job reports with. */
    class JobHandle internal constructor(val key: String, internal val cancelled: AtomicBoolean) {
        val isCancelled: Boolean get() = cancelled.get()
        fun stage(stage: DownloadStage, detail: String = "") = update(key) { it.copy(stage = stage, detail = detail.ifEmpty { it.detail }, state = DownloadState.RUNNING) }
        /** Bytes so far (negative = unchanged), the total (<= 0 = unchanged), a line, the speed (negative = unchanged). */
        fun progress(bytesDone: Long, bytesTotal: Long, detail: String? = null, speedBps: Long = -1L) = update(key) {
            val done = if (bytesDone >= 0) bytesDone else it.bytesDone
            val total = if (bytesTotal > 0) bytesTotal else it.bytesTotal
            val speed = if (speedBps >= 0) speedBps else it.speedBps
            val eta = if (speed > 0 && total > done) (total - done) / speed else -1L
            it.copy(state = DownloadState.RUNNING, stage = DownloadStage.DOWNLOAD, bytesDone = done, bytesTotal = total, detail = detail ?: it.detail, speedBps = speed, etaSeconds = eta)
        }
        fun log(line: String) = StoresState.logLine(line)
    }

    private class Item(var entry: DownloadEntry, val factory: () -> DownloadJob, var job: DownloadJob? = null, val cancelled: AtomicBoolean = AtomicBoolean(false), var pauseRequested: Boolean = false)

    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()
    private var running = 0

    fun parallel(context: Context): Int = SessionPrefs.gameStoresParallel(context)

    fun setParallel(context: Context, count: Int) {
        SessionPrefs.setGameStoresParallel(context, count)
        advance(context.applicationContext)
    }

    fun entry(key: String): DownloadEntry? = synchronized(lock) { items[key]?.entry }

    fun isActive(key: String): Boolean = entry(key)?.isActive == true

    /**
     * Queues a download, or starts it when a slot is free. A key already active is left alone; a
     * finished or failed row with the same key is replaced.
     */
    fun enqueue(context: Context, entry: DownloadEntry, factory: () -> DownloadJob) {
        val app = context.applicationContext
        synchronized(lock) {
            items[entry.key]?.let { if (it.entry.isActive) { Log.i(TAG, "${entry.key}: already queued"); return } }
            items.remove(entry.key)
            items[entry.key] = Item(entry.copy(state = DownloadState.QUEUED, stage = DownloadStage.MANIFEST, startedAt = System.currentTimeMillis()), factory)
            renumber()
        }
        StoresState.logLine("${entry.store.label}: queued \"${entry.name}\"" + if (entry.bytesTotal > 0) " (${com.droiddeck.launcher.stores.formatBytes(entry.bytesTotal)})" else "")
        publish(app)
        advance(app)
    }

    fun pause(key: String) {
        val item = synchronized(lock) { items[key] } ?: return
        synchronized(lock) {
            if (item.entry.state == DownloadState.QUEUED) { item.entry = item.entry.copy(state = DownloadState.PAUSED, queuePosition = 0); renumber(); publishLocked(); return }
            if (item.entry.state != DownloadState.RUNNING) return
            item.pauseRequested = true
        }
        item.cancelled.set(true)
        item.job?.cancel(deleteFiles = false)
        StoresState.logLine("paused \"${item.entry.name}\"")
    }

    fun resume(context: Context, key: String) {
        val app = context.applicationContext
        synchronized(lock) {
            val item = items[key] ?: return
            if (item.entry.state != DownloadState.PAUSED && item.entry.state != DownloadState.FAILED) return
            items.remove(key)
            items[key] = Item(item.entry.copy(state = DownloadState.QUEUED, speedBps = 0, etaSeconds = -1, error = null, startedAt = System.currentTimeMillis()), item.factory)
            renumber()
            publishLocked()
        }
        StoresState.logLine("resumed \"${entry(key)?.name}\"")
        advance(app)
    }

    fun retry(context: Context, key: String) = resume(context, key)

    fun cancel(context: Context, key: String) {
        val app = context.applicationContext
        val item: Item
        val wasRunning: Boolean
        synchronized(lock) {
            item = items[key] ?: return
            wasRunning = item.entry.state == DownloadState.RUNNING
            if (!wasRunning) {
                item.entry = item.entry.copy(state = DownloadState.CANCELLED, queuePosition = 0, finishedAt = System.currentTimeMillis())
                renumber()
                publishLocked()
            }
        }
        if (wasRunning) {
            item.cancelled.set(true)
            item.job?.cancel(deleteFiles = true)
        } else {
            // Never started: nothing is on disk, but the job knows where it would have gone.
            Thread({ runCatching { item.factory().cancel(deleteFiles = true) } }, "store-dl-cancel").start()
        }
        StoresState.logLine("cancelled \"${item.entry.name}\"")
        StoreDownloadService.finish(app, key)
        advance(app)
    }

    /** Drops a finished, failed or cancelled row from the list. */
    fun dismiss(key: String) {
        synchronized(lock) {
            val item = items[key] ?: return
            if (item.entry.isActive) return
            items.remove(key)
            publishLocked()
        }
    }

    private fun renumber() {
        var pos = 1
        for (item in items.values) if (item.entry.state == DownloadState.QUEUED) item.entry = item.entry.copy(queuePosition = pos++)
    }

    private fun update(key: String, transform: (DownloadEntry) -> DownloadEntry) {
        synchronized(lock) {
            val item = items[key] ?: return
            item.entry = transform(item.entry)
            publishLocked()
        }
    }

    private fun publish(context: Context) { synchronized(lock) { publishLocked() }; StoreDownloadService.sync(context) }

    /** Newest first: what was started last sits on top, as the preview lists them. Caller holds [lock]. */
    private fun publishLocked() {
        val list = items.values.map { it.entry }.sortedByDescending { it.startedAt }
        StoresState.post { StoresState.downloads = list }
    }

    /** Starts queued items while slots are free. */
    private fun advance(context: Context) {
        while (true) {
            val next: Item
            synchronized(lock) {
                if (running >= parallel(context)) return
                next = items.values.firstOrNull { it.entry.state == DownloadState.QUEUED } ?: return
                running++
                next.entry = next.entry.copy(state = DownloadState.RUNNING, stage = DownloadStage.MANIFEST, queuePosition = 0)
                renumber()
                publishLocked()
            }
            StoreDownloadService.start(context)
            Thread({ runItem(context, next) }, "store-dl-${next.entry.key}").start()
        }
    }

    private fun runItem(context: Context, item: Item) {
        val handle = JobHandle(item.entry.key, item.cancelled)
        var result: String? = null
        var error: String? = null
        try {
            val job = item.factory()
            synchronized(lock) { item.job = job }
            StoresState.logLine("${item.entry.store.label}: fetching manifest for \"${item.entry.name}\"")
            result = job.run(handle)
            if (result == null && !item.cancelled.get()) error = "install failed"
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "${item.entry.key} failed", t)
        }
        synchronized(lock) {
            running--
            val now = System.currentTimeMillis()
            item.entry = when {
                item.cancelled.get() && item.pauseRequested -> item.entry.copy(state = DownloadState.PAUSED, speedBps = 0, etaSeconds = -1)
                item.cancelled.get() -> item.entry.copy(state = DownloadState.CANCELLED, finishedAt = now, speedBps = 0, etaSeconds = -1)
                result != null -> item.entry.copy(state = DownloadState.INSTALLED, stage = DownloadStage.DONE, installPath = result, bytesDone = item.entry.bytesTotal, speedBps = 0, etaSeconds = -1, finishedAt = now)
                else -> item.entry.copy(state = DownloadState.FAILED, error = error, speedBps = 0, etaSeconds = -1, finishedAt = now)
            }
            item.job = null
            publishLocked()
        }
        when (item.entry.state) {
            DownloadState.INSTALLED -> {
                StoresState.logLine("installed \"${item.entry.name}\" → ${result}")
                // The folder is on disk and registered: the Installed tab and the Games list follow.
                StoresState.notifyLibraryChanged(context)
            }
            DownloadState.FAILED -> StoresState.logLine("${item.entry.store.label}: \"${item.entry.name}\" failed: $error")
            DownloadState.CANCELLED -> StoresState.notifyLibraryChanged(context)
            else -> {}
        }
        StoreDownloadService.finish(context, item.entry.key)
        advance(context)
    }
}
